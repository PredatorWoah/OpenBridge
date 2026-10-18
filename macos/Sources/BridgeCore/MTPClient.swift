import Foundation
import MTPCore

/// What the browser window needs from a connection, whether Wi-Fi (OB01) or USB (MTP).
public protocol FileSource {
    func list(id: String) async throws -> [Entry]
    func download(_ entry: Entry, to destination: URL, progress: @MainActor (Int64, Int64) -> Void) async throws
    func upload(_ source: URL, directoryID: String, progress: @MainActor (Int64, Int64) -> Void) async throws
    /// Creates an empty folder and returns it; never reuses an existing name.
    func makeFolder(named name: String, in directoryID: String) async throws -> Entry
    /// A small JPEG preview of a photo or video.
    func thumbnail(_ entry: Entry, size: Int) async throws -> Data
    /// Deletes a file, or a folder with everything in it.
    func delete(_ entry: Entry) async throws
    /// Renames in place; returns the item afterwards.
    func rename(_ entry: Entry, to name: String) async throws -> Entry
    /// A folder's total size; sources that can't count cheaply throw.
    func measure(_ folder: Entry) async throws -> FolderSize
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
    /// The phone this client was opened on. Entry IDs and upload handles are only meaningful on it.
    private let identity: MTPDeviceIdentity
    private let reconnect: () throws -> MTPSession
    private var session: MTPSession?
    private static let chunk = 1 << 20
    private static let progressStep: Int64 = 1 << 20
    /// Previews that broke the USB connection. Some phones choke on GetThumb for some files; after two,
    /// previews stay off for this connection so browsing stays reliable. Only touched on `queue`.
    private var previewFailures = 0

    init(name: String, identity: MTPDeviceIdentity, session: MTPSession?, reconnect: @escaping () throws -> MTPSession) {
        self.name = name; self.identity = identity; self.session = session; self.reconnect = reconnect
    }

    #if canImport(IOUSBHost)
    /// Finds the first phone connected over USB in File transfer mode.
    public static func connectUSB() async throws -> MTPClient {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let (session, info) = try USBTransport.openSession()
                    let identity = info.identity
                    continuation.resume(returning: MTPClient(name: info.displayName, identity: identity, session: session,
                                                             reconnect: { try USBTransport.openSession(matching: identity).0 }))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    /// Name of a phone plugged in and in File transfer mode, if any. Does not open or claim it.
    public static func attachedPhone() -> String? { USBTransport.attachedPhone() }
    #endif

    // MARK: FileSource

    public func list(id: String = "") async throws -> [Entry] {
        let location = try Location(id)
        do { return try await list(location) }
        catch where !MTPClient.keepsSession(error) {
            // A USB hiccup drops the session; listing is safe to repeat, so reconnect once and try again
            // instead of making you press Refresh.
            try await Task.sleep(nanoseconds: 500_000_000)
            return try await list(location)
        }
    }

    private func list(_ location: Location) async throws -> [Entry] {
        try await run { session in
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

    /// Downloads `entry`, continuing an earlier interrupted download to the same place when there is one.
    public func download(_ entry: Entry, to destination: URL, progress: @MainActor (Int64, Int64) -> Void) async throws {
        let location = try Location(entry.id)
        guard location.storage != nil, location.handle != MTPSession.root, !entry.directory else {
            throw BridgeError.message("Choose a file to download.")
        }
        let total = try await run { session in try session.size(of: location.handle, info: try session.info(location.handle)) }
        guard Int64(clamping: total) == entry.size else { throw BridgeError.fileChanged }
        let partial = try PartialFile.open(for: destination, size: entry.size)
        var received = UInt64(partial.offset)
        do {
            await progress(Int64(received), entry.size)
            if received == 0 {
                // A whole-object download works on every phone.
                let stream = try await run { try $0.beginDownload(location.handle) }
                received = try await drain(stream, into: partial, from: 0, total: total, progress: progress)
            } else {
                // Continue in pieces of at most 1 GiB; one request can carry under 4 GiB.
                while received < total {
                    let count = UInt32(min(total - received, 1 << 30))
                    let offset = received
                    let stream: MTPInbound
                    do { stream = try await run { try $0.beginPartialDownload(location.handle, offset: offset, count: count) } }
                    catch MTPError.response(0x2005) {
                        // This phone can't continue a download: start over.
                        partial.end(after: CancellationError())
                        return try await download(entry, to: destination, progress: progress)
                    }
                    let got = try await drain(stream, into: partial, from: received, total: total, progress: progress) - received
                    guard got == UInt64(count) else { throw BridgeError.message("The phone sent \(got) of \(count) bytes. Try again.") }
                    received += got
                }
            }
            guard received == total else { throw BridgeError.message("The phone sent \(received) of \(total) bytes. Try again.") }
            await progress(Int64(clamping: received), entry.size)
            try partial.finish(at: destination)
        } catch {
            partial.end(after: error)
            if !MTPClient.keepsSession(error) { await abandon() }
            throw error
        }
    }

    /// Writes one data phase to `partial` and reads its response. Returns the new byte count.
    private func drain(_ stream: MTPInbound, into partial: PartialFile, from start: UInt64, total: UInt64,
                       progress: @MainActor (Int64, Int64) -> Void) async throws -> UInt64 {
        var received = start, reported = start
        while true {
            try Task.checkCancellation()
            guard let chunk = try await run({ _ in try stream.next() }) else { break }
            try partial.write(chunk)
            received += UInt64(chunk.count)
            if received - reported >= UInt64(MTPClient.progressStep) {
                reported = received
                await progress(Int64(clamping: received), Int64(clamping: total))
            }
        }
        _ = try await run { _ in try stream.finish() }
        return received
    }

    public func makeFolder(named name: String, in directoryID: String) async throws -> Entry {
        try Wire.validateName(name)
        let location = try Location(directoryID)
        guard let storage = location.storage else { throw BridgeError.message("Open a storage on the phone first.") }
        let existing = Set(try await list(id: directoryID).map { $0.name.lowercased() })
        guard !existing.contains(name.lowercased()) else { throw BridgeError.message("A file or folder named “\(name)” already exists on the phone.") }
        let handle = try await run { try $0.createFolder(storage: storage, parent: location.handle, name: name) }
        return Entry(id: "o:\(storage):\(handle)", name: name, directory: true, size: 0)
    }

    public func thumbnail(_ entry: Entry, size: Int) async throws -> Data {
        let location = try Location(entry.id)
        guard location.storage != nil, !entry.directory else { throw BridgeError.message("No preview.") }
        return try await run { session in
            guard self.previewFailures < 2 else { throw BridgeError.message("No preview.") }
            do { return try session.thumbnail(location.handle) }
            catch {
                if !MTPClient.keepsSession(error) { self.previewFailures += 1 }
                throw error
            }
        }
    }

    public func delete(_ entry: Entry) async throws {
        let location = try Location(entry.id)
        guard location.storage != nil, location.handle != MTPSession.root else { throw BridgeError.message("The storage itself can't be deleted.") }
        // Android deletes a folder with everything in it.
        _ = try await run { try $0.delete(location.handle) }
    }

    public func rename(_ entry: Entry, to name: String) async throws -> Entry {
        try Wire.validateName(name)
        guard name.utf16.count < 255 else { throw BridgeError.message("That name is too long for the phone.") }
        let location = try Location(entry.id)
        guard let storage = location.storage, location.handle != MTPSession.root else { throw BridgeError.message("The storage itself can't be renamed.") }
        if name == entry.name { return entry }
        let parent = try await run { try $0.info(location.handle).parent }
        let folder = parent == 0 || parent == MTPSession.root ? "s:\(storage)" : "o:\(storage):\(parent)"
        // Never take a name that exists; a change of letter case of its own name is fine.
        let taken = Set(try await list(id: folder).filter { $0.id != entry.id }.map { $0.name.lowercased() })
        guard !taken.contains(name.lowercased()) else { throw BridgeError.message("A file or folder named “\(name)” already exists on the phone.") }
        try await run { try $0.rename(location.handle, to: name) }
        return Entry(id: entry.id, name: name, directory: entry.directory, size: entry.size)
    }

    public func measure(_ folder: Entry) async throws -> FolderSize {
        // MTP has no folder sizes, and walking a folder over the cable would hold up browsing.
        throw BridgeError.message("Folder sizes aren't available over USB.")
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
        // Fail before sending anything if the phone reports too little space (0 or max means unknown).
        let free = try await run { session in try session.storages().first { $0.id == storage }?.freeSpace }
        if let free, free > 0, free < UInt64.max, size > free {
            throw BridgeError.message("Not enough space on the phone: \(ByteCountFormatter.string(fromByteCount: Int64(clamping: free), countStyle: .file)) free.")
        }
        // Phones' shared storage ignores letter case, so compare names that way. Never replace a file.
        let existing = Set(try await list(id: directoryID).map { $0.name.lowercased() })
        guard !existing.contains(name.lowercased()) else { throw BridgeError.message("Filename already exists on the phone.") }

        let file = try FileHandle(forReadingFrom: source)
        defer { try? file.close() }
        // The object exists on the phone from here on; every failure below must go through cleanup.
        let created = try await run { try $0.createObject(storage: storage, parent: location.handle, name: name, size: size) }
        do {
            let stream = try await run { try $0.beginSend(created) }
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
            // Remove the partial file on a fresh session, but only on the same phone and only if the handle
            // still names that same object.
            let removed = (try? await run { try $0.deleteIfUnchanged(created) }) ?? false
            if removed { throw error }
            throw BridgeError.message("\(error.localizedDescription) A partial copy of “\(name)” may remain on the phone; delete it there before retrying.")
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
                    let current = try self.session ?? self.reconnectSamePhone()
                    self.session = current
                    continuation.resume(returning: try work(current))
                } catch {
                    if !MTPClient.keepsSession(error) { self.session?.abandon(); self.session = nil }
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Reconnects and proves it is the same phone; IDs on screen and upload handles belong to that phone only.
    private func reconnectSamePhone() throws -> MTPSession {
        let candidate = try reconnect()
        guard (try? candidate.deviceInfo().identity) == identity else {
            candidate.close()
            throw BridgeError.message("A different phone is connected now. Disconnect and connect again.")
        }
        return candidate
    }

    /// Resets the phone after a cancelled or broken transfer; the next operation reconnects.
    private func abandon() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async { self.session?.abandon(); self.session = nil; continuation.resume() }
        }
    }
}
