import Foundation
import MTPCore

/// What the browser window needs from a connection, whether Wi-Fi (OB01) or USB (MTP).
public protocol FileSource {
    func list(id: String) async throws -> [Entry]
    func download(_ entry: Entry, to destination: URL, progress: @MainActor (Int64, Int64) -> Void) async throws
    func upload(_ source: URL, directoryID: String, progress: @MainActor (Int64, Int64) -> Void) async throws
    func disconnect() async
}

extension BridgeClient: FileSource {
    public func disconnect() async {}
}

/// Browses and transfers files on a USB-connected phone in File transfer (MTP) mode.
/// Entry IDs: "" lists storages, "s:<storage>" a storage's top level, "o:<storage>:<handle>" an object.
public final class MTPClient: FileSource, @unchecked Sendable {
    public let name: String
    private let queue = DispatchQueue(label: "org.openbridge.mtp")
    private let reconnect: () throws -> MTPSession
    private var session: MTPSession?
    private static let chunk = 1 << 20
    private static let progressStep: Int64 = 1 << 20

    init(name: String, session: MTPSession?, reconnect: @escaping () throws -> MTPSession) {
        self.name = name; self.session = session; self.reconnect = reconnect
    }

    #if canImport(IOUSBHost)
    /// Finds the first phone connected over USB in File transfer mode.
    public static func connectUSB() async throws -> MTPClient {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let (session, info) = try USBTransport.openSession()
                    continuation.resume(returning: MTPClient(name: info.displayName, session: session,
                                                             reconnect: { try USBTransport.openSession().0 }))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
    #endif

    // MARK: FileSource

    public func list(id: String = "") async throws -> [Entry] {
        let location = try Location(id)
        return try await run { session in
            guard let storage = location.storage else {
                return try session.storages().map { Entry(id: "s:\($0.id)", name: $0.name, directory: true, size: 0) }
            }
            return try session.children(storage: storage, parent: location.handle).compactMap { item -> Entry? in
                // Skip names that cannot be saved safely on the Mac rather than failing the whole folder.
                guard (try? Wire.validateName(item.info.filename)) != nil else { return nil }
                let size = item.info.isFolder ? 0 : Int64(clamping: try session.size(of: item.handle, info: item.info))
                return Entry(id: "o:\(storage):\(item.handle)", name: item.info.filename, directory: item.info.isFolder, size: size)
            }.sorted { ($0.directory ? 0 : 1, $0.name.lowercased()) < ($1.directory ? 0 : 1, $1.name.lowercased()) }
        }
    }

    public func download(_ entry: Entry, to destination: URL, progress: @MainActor (Int64, Int64) -> Void) async throws {
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            throw BridgeError.message("\(destination.lastPathComponent) already exists. Choose a new name; OpenBridge never replaces files.")
        }
        let location = try Location(entry.id)
        guard location.storage != nil, location.handle != MTPSession.root, !entry.directory else {
            throw BridgeError.message("Choose a file to download.")
        }
        let (stream, total) = try await run { session -> (MTPInbound, UInt64) in
            let size = try session.size(of: location.handle, info: try session.info(location.handle))
            return (try session.beginDownload(location.handle), size)
        }
        let manager = FileManager.default
        let temporary = destination.deletingLastPathComponent().appendingPathComponent(".openbridge-\(UUID().uuidString).part")
        guard manager.createFile(atPath: temporary.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            await abandon()
            throw BridgeError.message("Cannot create download in the selected folder.")
        }
        defer { try? manager.removeItem(at: temporary) }
        let file = try FileHandle(forWritingTo: temporary)
        do {
            var received: Int64 = 0, reported: Int64 = 0
            await progress(0, Int64(clamping: total))
            while true {
                try Task.checkCancellation()
                guard let chunk = try await run({ _ in try stream.next() }) else { break }
                try file.write(contentsOf: chunk)
                received += Int64(chunk.count)
                if received - reported >= MTPClient.progressStep { reported = received; await progress(received, Int64(clamping: total)) }
            }
            _ = try await run { _ in try stream.finish() }
            guard UInt64(received) == total else { throw BridgeError.message("The phone sent \(received) of \(total) bytes. Try again.") }
            await progress(received, Int64(clamping: total))
            try file.synchronize()
            try file.close()
        } catch {
            try? file.close()
            if !MTPClient.keepsSession(error) { await abandon() }
            throw error
        }
        try manager.moveItem(at: temporary, to: destination)
    }

    public func upload(_ source: URL, directoryID: String, progress: @MainActor (Int64, Int64) -> Void) async throws {
        let name = source.lastPathComponent
        try Wire.validateName(name)
        guard name.utf16.count < 255 else { throw BridgeError.message("That filename is too long for the phone.") }
        let location = try Location(directoryID)
        guard let storage = location.storage else { throw BridgeError.message("Open a storage on the phone first, then upload.") }
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true, let rawSize = values.fileSize, rawSize >= 0 else {
            throw BridgeError.message("Choose a regular file.")
        }
        let size = UInt64(rawSize)
        guard size <= MTPSession.maxSingleUpload else { throw BridgeError.message("Files of 4 GiB or more cannot be sent over USB yet. Use Wi-Fi.") }
        // Phones' shared storage ignores letter case, so compare names that way. Never replace a file.
        let existing = Set(try await list(id: directoryID).map { $0.name.lowercased() })
        guard !existing.contains(name.lowercased()) else { throw BridgeError.message("Filename already exists on the phone.") }

        let file = try FileHandle(forReadingFrom: source)
        defer { try? file.close() }
        let (handle, stream) = try await run { try $0.beginUpload(storage: storage, parent: location.handle, name: name, size: size) }
        do {
            var sent: Int64 = 0, reported: Int64 = 0
            await progress(0, Int64(size))
            while UInt64(sent) < size {
                try Task.checkCancellation()
                guard let chunk = try file.read(upToCount: Int(min(UInt64(MTPClient.chunk), size - UInt64(sent)))), !chunk.isEmpty else {
                    throw BridgeError.message("Source file changed during upload.")
                }
                try await run { _ in try stream.write(chunk) }
                sent += Int64(chunk.count)
                if sent - reported >= MTPClient.progressStep { reported = sent; await progress(sent, Int64(size)) }
            }
            _ = try await run { _ in try stream.finish() }
            await progress(sent, Int64(size))
        } catch {
            await abandon()
            // Remove the partial file the phone created, on a fresh session.
            _ = try? await run { try $0.delete(handle) }
            throw error
        }
    }

    public func disconnect() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async { self.session?.close(); self.session = nil; continuation.resume() }
        }
    }

    // MARK: Internals

    private struct Location {
        let storage: UInt32?
        let handle: UInt32
        init(_ id: String) throws {
            let parts = id.split(separator: ":", omittingEmptySubsequences: false)
            switch (parts.first, parts.count) {
            case (nil, _), ("", 1): storage = nil; handle = MTPSession.root
            case ("s", 2): guard let s = UInt32(parts[1]) else { throw Location.invalid }; storage = s; handle = MTPSession.root
            case ("o", 3): guard let s = UInt32(parts[1]), let h = UInt32(parts[2]) else { throw Location.invalid }; storage = s; handle = h
            default: throw Location.invalid
            }
        }
        static let invalid = BridgeError.message("Unknown item; refresh the folder.")
    }

    private static func keepsSession(_ error: Error) -> Bool {
        (error as? MTPError)?.keepsSession ?? (error is BridgeError)
    }

    /// Runs blocking MTP work on the connection's queue, reconnecting if a previous failure dropped the session.
    private func run<T>(_ work: @escaping (MTPSession) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    let current = try self.session ?? self.reconnect()
                    self.session = current
                    continuation.resume(returning: try work(current))
                } catch {
                    if !MTPClient.keepsSession(error) { self.session?.abandon(); self.session = nil }
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Resets the phone after a cancelled or broken transfer; the next operation reconnects.
    private func abandon() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async { self.session?.abandon(); self.session = nil; continuation.resume() }
        }
    }
}
