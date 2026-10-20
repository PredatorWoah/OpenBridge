import Foundation
import CryptoKit

public struct BridgeClient {
    public let host: String
    public let port: UInt16
    /// SHA-256 of the phone's certificate, pinned on every connection.
    public let fingerprint: Data
    /// This Mac's secret for the phone, given at pairing. Saved so the phone reconnects without a code.
    public let secret: Data
    /// Report progress about once per MiB (and always at completion) instead of on every 64 KiB chunk.
    private static let progressStep: Int64 = 1 << 20

    public init(host: String, port: String, fingerprint: String, secret: String) throws {
        guard Wire.privateIPv4(host), let number = UInt16(port), number > 0 else {
            throw BridgeError.message("Enter the phone's private Wi-Fi IPv4 address and port (1–65535).")
        }
        self.host = host; self.port = number
        self.fingerprint = try Wire.hex(fingerprint)
        self.secret = try Wire.hex(secret)
    }

    /// A phone saved earlier, at its current address.
    public init(saved host: String, port: UInt16, fingerprint: Data, secret: Data) throws {
        guard Wire.privateIPv4(host), port > 0, fingerprint.count == 32, secret.count == 32 else {
            throw BridgeError.message("The saved phone's address isn't valid. Pair again.")
        }
        self.host = host; self.port = port; self.fingerprint = fingerprint; self.secret = secret
    }

    /// A phone reached by Fast USB, through an `ADBTunnel` on this Mac's loopback address. Only this initializer accepts
    /// 127.0.0.1; the pin and secret are checked exactly as over Wi‑Fi, end to end through the tunnel.
    public init(tunnel port: UInt16, fingerprint: Data, secret: Data) throws {
        guard port > 0, fingerprint.count == 32, secret.count == 32 else {
            throw BridgeError.message("The saved phone's details aren't valid. Pair again.")
        }
        self.host = BridgeClient.loopback; self.port = port; self.fingerprint = fingerprint; self.secret = secret
    }

    static let loopback = "127.0.0.1"

    /// Whether this connection goes through Fast USB rather than Wi‑Fi.
    public var overTunnel: Bool { host == BridgeClient.loopback }

    /// The certificate fingerprint of whatever answers on a Fast USB tunnel's `port`. Nothing is sent but the TLS
    /// handshake, so the Mac learns which phone it is before deciding whether to send a secret or pair.
    public static func fingerprint(tunnel port: UInt16, timeout: TimeInterval = 5) async throws -> Data {
        let channel = TLSChannel(host: loopback, port: port, fingerprint: nil)
        defer { channel.close() }
        try await channel.connect(timeout: timeout)
        guard let seen = channel.peerFingerprint else { throw BridgeError.message("The phone sent no certificate.") }
        return seen
    }

    /// The certificate fingerprint of whatever answers at a private Wi‑Fi address, with only the TLS handshake. Used to
    /// find a phone sharing on its own hotspot, where it doesn't announce itself but is this Mac's gateway.
    public static func fingerprint(host: String, port: UInt16, timeout: TimeInterval = 2) async throws -> Data {
        guard Wire.privateIPv4(host), port > 0 else { throw BridgeError.message("Not a private Wi-Fi IPv4 address.") }
        let channel = TLSChannel(host: host, port: port, fingerprint: nil)
        defer { channel.close() }
        try await channel.connect(timeout: timeout)
        guard let seen = channel.peerFingerprint else { throw BridgeError.message("The phone sent no certificate.") }
        return seen
    }

    /// The short ID the phone announces on the network: the first 8 bytes of its certificate fingerprint.
    public var phoneID: String { fingerprint.prefix(8).map { String(format: "%02x", $0) }.joined() }

    // Internal injection for loopback TLS interoperability tests; public initialization validates LAN addresses.
    init(testHost: String, port: UInt16, fingerprint: Data, secret: Data) {
        self.host = testHost; self.port = port; self.fingerprint = fingerprint; self.secret = secret
    }

    private func open(operation: UInt8, id: String, extra: Data = Data(), connectTimeout: TimeInterval = 30) async throws -> TLSChannel {
        let channel = TLSChannel(host: host, port: port, fingerprint: fingerprint)
        do {
            try await channel.connect(timeout: connectTimeout)
            try await channel.send(Data("OB01".utf8) + secret + Data([operation]) + Wire.string(id) + extra)
            return channel
        } catch { channel.close(); throw error }
    }

    public func list(id: String = "") async throws -> [Entry] { try await list(id: id, quick: false) }

    /// Lists a folder. `quick` gives up on connecting after 3 seconds, to look for a saved phone without waiting.
    public func list(id: String, quick: Bool) async throws -> [Entry] {
        let channel = try await open(operation: 1, id: id, connectTimeout: quick ? 3 : 30)
        defer { channel.close() }
        try await channel.status()
        let count = try await channel.number(4)
        guard count <= 10_000 else { throw BridgeError.message("Folder has too many entries.") }
        var result: [Entry] = []
        var ids = Set<String>()
        for _ in 0..<Int(count) {
            let id = try await channel.string(limit: 128)
            let name = try await channel.string(limit: 255)
            try Wire.validateName(name)
            let directory = try await channel.number(1)
            let size = try await channel.number(8)
            guard !id.isEmpty, ids.insert(id).inserted, directory <= 1, size <= UInt64(Int64.max) else {
                throw BridgeError.message("Invalid directory entry.")
            }
            result.append(Entry(id: id, name: name, directory: directory == 1, size: Int64(size)))
        }
        return result
    }

    /// Downloads `entry`, continuing an earlier interrupted download to the same place when there is one.
    public func download(_ entry: Entry, to destination: URL, progress: @MainActor (Int64, Int64) -> Void) async throws {
        let partial = try PartialFile.open(for: destination, size: entry.size)
        do {
            let channel: TLSChannel
            if partial.offset > 0 {
                channel = try await open(operation: 6, id: entry.id, extra: Wire.integer(partial.offset))
                do { try await channel.status() } catch BridgeError.message(let text) where text.contains("Unsupported operation") {
                    // A phone app from before resuming existed: start over.
                    channel.close(); partial.end(after: CancellationError())
                    return try await download(entry, to: destination, progress: progress)
                }
            } else {
                channel = try await open(operation: 2, id: entry.id)
                try await channel.status()
            }
            defer { channel.close() }
            let total = try await channel.number(8)
            guard total <= Wire.maxFile else { throw BridgeError.message("Unsupported file size.") }
            guard Int64(total) == entry.size else { throw BridgeError.fileChanged }
            var received = partial.offset, reported = partial.offset
            await progress(received, Int64(total))
            while received < Int64(total) {
                try Task.checkCancellation()
                let chunk = try await channel.read(Int(min(65_536, Int64(total) - received)))
                try partial.write(chunk)
                received += Int64(chunk.count)
                if received - reported >= Self.progressStep || received == Int64(total) {
                    reported = received
                    await progress(received, Int64(total))
                }
            }
            try partial.finish(at: destination)
        } catch {
            partial.end(after: error)
            throw error
        }
    }

    /// Creates an empty folder named `name` inside `directoryID` and returns it.
    public func makeFolder(named name: String, in directoryID: String) async throws -> Entry {
        try Wire.validateName(name)
        let channel = try await open(operation: 4, id: directoryID, extra: Wire.string(name))
        defer { channel.close() }
        try await channel.status()
        let id = try await channel.string(limit: 128)
        guard !id.isEmpty else { throw BridgeError.message("Invalid folder ID.") }
        return Entry(id: id, name: name, directory: true, size: 0)
    }

    /// A JPEG preview of a photo or video, at most `size` pixels on its longer side.
    public func thumbnail(_ entry: Entry, size: Int) async throws -> Data {
        let channel = try await open(operation: 5, id: entry.id, extra: Wire.integer(Int32(min(max(size, 32), 1024))))
        defer { channel.close() }
        try await channel.status()
        let length = try await channel.number(4)
        guard length > 0, length <= 1 << 20 else { throw BridgeError.message("Invalid preview.") }
        return try await channel.read(Int(length))
    }

    /// Files sent from the phone (Share › OpenBridge), waiting up to `wait` milliseconds for one to arrive.
    public func outbox(wait: Int32) async throws -> [Entry] {
        let channel = try await open(operation: 13, id: "", extra: Wire.integer(min(max(wait, 0), 25_000)))
        defer { channel.close() }
        try await channel.status(timeout: 35)
        let count = try await channel.number(4)
        guard count <= 1_000 else { throw BridgeError.message("Invalid reply.") }
        var result: [Entry] = []
        for _ in 0..<Int(count) {
            let id = try await channel.string(limit: 128)
            let name = try await channel.string(limit: 255)
            try Wire.validateName(name)
            let size = try await channel.number(8)
            guard !id.isEmpty, size <= UInt64(Wire.maxFile) else { throw BridgeError.message("Invalid reply.") }
            result.append(Entry(id: id, name: name, directory: false, size: Int64(size)))
        }
        return result
    }

    /// Saves a file sent from the phone, continuing an interrupted copy, then lets the phone drop it.
    public func receive(_ entry: Entry, to destination: URL) async throws {
        let partial = try PartialFile.open(for: destination, size: entry.size)
        do {
            let channel = try await open(operation: 14, id: entry.id, extra: Wire.integer(partial.offset))
            defer { channel.close() }
            try await channel.status()
            guard Int64(try await channel.number(8)) == entry.size else { throw BridgeError.fileChanged }
            var received = partial.offset
            while received < entry.size {
                try Task.checkCancellation()
                let chunk = try await channel.read(Int(min(65_536, entry.size - received)))
                try partial.write(chunk)
                received += Int64(chunk.count)
            }
            try partial.finish(at: destination)
        } catch {
            partial.end(after: error)
            throw error
        }
        try await done(entry)
    }

    /// Files and texts sent from the phone, waiting up to `wait` milliseconds for one (OUTBOX ALL). Phones before 0.13.0
    /// answer "Unsupported operation"; then `outbox(wait:)` still collects files.
    public func outboxAll(wait: Int32) async throws -> [OutboxItem] {
        let channel = try await open(operation: 17, id: "", extra: Wire.integer(min(max(wait, 0), 25_000)))
        defer { channel.close() }
        try await channel.status(timeout: 35)
        let count = try await channel.number(4)
        guard count <= 1_000 else { throw BridgeError.message("Invalid reply.") }
        var result: [OutboxItem] = []
        for _ in 0..<Int(count) {
            let id = try await channel.string(limit: 128)
            let name = try await channel.string(limit: 255)
            let kind = try await channel.number(1)
            let size = try await channel.number(8)
            guard !id.isEmpty, kind <= 1 else { throw BridgeError.message("Invalid reply.") }
            if kind == 0 {
                // A file's name becomes a file name on this Mac; a text's is only a label.
                try Wire.validateName(name)
                guard size <= UInt64(Wire.maxFile) else { throw BridgeError.message("Invalid reply.") }
            } else {
                guard size <= UInt64(Wire.maxClipboard) else { throw BridgeError.message("Invalid reply.") }
            }
            result.append(OutboxItem(entry: Entry(id: id, name: name, directory: false, size: Int64(size)), text: kind == 1))
        }
        return result
    }

    /// A text sent from the phone, read whole (OUTBOX GET from the start). The phone keeps it until `done`.
    public func text(_ item: Entry) async throws -> String {
        let channel = try await open(operation: 14, id: item.id, extra: Wire.integer(Int64(0)))
        defer { channel.close() }
        try await channel.status()
        let size = try await channel.number(8)
        guard size <= UInt64(Wire.maxClipboard) else { throw BridgeError.message("That text is too long.") }
        guard let text = String(data: try await channel.read(Int(size)), encoding: .utf8) else {
            throw BridgeError.message("The phone sent text that isn't valid UTF-8.")
        }
        return text
    }

    /// The Mac has what the phone sent (a file saved completely, or a text): the phone drops its copy.
    public func done(_ item: Entry) async throws {
        let channel = try await open(operation: 15, id: item.id)
        defer { channel.close() }
        try await channel.status()
    }

    /// The phone app's version, the highest operation it answers, and the phone's name (INFO, from 0.13.0).
    public func info() async throws -> PhoneInfo {
        let channel = try await open(operation: 18, id: "")
        defer { channel.close() }
        try await channel.status()
        let version = try await channel.string(limit: 64)
        let revision = Int32(bitPattern: UInt32(truncatingIfNeeded: try await channel.number(4)))
        let name = try await channel.string(limit: 128)
        return PhoneInfo(version: version, revision: Int(revision), name: name)
    }

    /// Deletes a file, or a folder with everything in it.
    public func delete(_ entry: Entry) async throws {
        let channel = try await open(operation: 9, id: entry.id)
        defer { channel.close() }
        try await channel.status()
    }

    /// Renames in place; returns the item afterwards (its ID may change).
    public func rename(_ entry: Entry, to name: String) async throws -> Entry {
        try Wire.validateName(name)
        let channel = try await open(operation: 10, id: entry.id, extra: Wire.string(name))
        defer { channel.close() }
        try await channel.status()
        let id = try await channel.string(limit: 128)
        guard !id.isEmpty else { throw BridgeError.message("Invalid ID.") }
        return Entry(id: id, name: name, directory: entry.directory, size: entry.size)
    }

    /// The total size of a folder, counted on the phone.
    public func measure(_ folder: Entry) async throws -> FolderSize {
        let channel = try await open(operation: 11, id: folder.id)
        defer { channel.close() }
        try await channel.status()
        let bytes = try await channel.number(8), files = try await channel.number(8), complete = try await channel.number(1)
        guard bytes <= UInt64(Int64.max), files <= UInt64(Int64.max), complete <= 1 else { throw BridgeError.message("Invalid folder size.") }
        return FolderSize(bytes: Int64(bytes), files: Int64(files), complete: complete == 1)
    }

    /// Files and folders whose names contain `query`, anywhere below `folderID`, searched on the phone.
    public func search(in folderID: String, for query: String) async throws -> SearchResult {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, text.utf8.count <= 255 else { throw BridgeError.message("Type something to search for.") }
        let channel = try await open(operation: 16, id: folderID, extra: Wire.string(text))
        defer { channel.close() }
        try await channel.status(timeout: 30)
        let count = try await channel.number(4)
        guard count <= 1_000 else { throw BridgeError.message("Invalid search reply.") }
        var found: [Found] = []
        for _ in 0..<Int(count) {
            let id = try await channel.string(limit: 128)
            let name = try await channel.string(limit: 255)
            try Wire.validateName(name)
            let directory = try await channel.number(1)
            let size = try await channel.number(8)
            let location = try await channel.string(limit: 1024)
            guard !id.isEmpty, directory <= 1, size <= UInt64(Int64.max) else { throw BridgeError.message("Invalid search reply.") }
            found.append(Found(entry: Entry(id: id, name: name, directory: directory == 1, size: Int64(size)), location: location))
        }
        return SearchResult(found: found, complete: try await channel.number(1) == 1)
    }

    /// The phone's clipboard text.
    public func clipboard() async throws -> String {
        let channel = try await open(operation: 7, id: "")
        defer { channel.close() }
        try await channel.status()
        return try await channel.string(limit: Wire.maxClipboard)
    }

    /// Puts `text` on the phone's clipboard.
    public func setClipboard(_ text: String) async throws {
        guard text.utf8.count <= Wire.maxClipboard else { throw BridgeError.message("That text is too long for the phone's clipboard.") }
        let channel = try await open(operation: 8, id: "", extra: Wire.string(text))
        defer { channel.close() }
        try await channel.status()
    }

    /// Uploads `source`. If an earlier upload of the same file to the same folder was cut off, the phone kept what
    /// arrived and this continues from there (phones before 0.12.0 start over).
    public func upload(_ source: URL, directoryID: String, progress: @MainActor (Int64, Int64) -> Void) async throws {
        let name = source.lastPathComponent
        try Wire.validateName(name)
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .isSymbolicLinkKey, .contentModificationDateKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let rawSize = values.fileSize, rawSize >= 0, rawSize <= Wire.maxFile else {
            throw BridgeError.message("Choose a regular file no larger than 64 GiB.")
        }
        let size = Int64(rawSize)
        let key = try Self.contentKey(source, size: size, modified: values.contentModificationDate)
        do {
            try await send(source, operation: 12, directoryID: directoryID, header: Wire.string(name) + Wire.integer(size) + key,
                           size: size, resumable: true, progress: progress)
        } catch BridgeError.message(let text) where text.contains("Unsupported operation") {
            try await send(source, operation: 3, directoryID: directoryID, header: Wire.string(name) + Wire.integer(size),
                           size: size, resumable: false, progress: progress)
        }
    }

    /// A 32-byte summary of a file for resumable uploads: size, modification time and first MiB. A file changed since
    /// an upload was cut off gets a new one, so the phone starts over instead of mixing old and new bytes.
    static func contentKey(_ source: URL, size: Int64, modified: Date?) throws -> Data {
        var hasher = SHA256()
        hasher.update(data: Data("\(size):\(Int64((modified?.timeIntervalSince1970 ?? 0) * 1000)):".utf8))
        let file = try FileHandle(forReadingFrom: source)
        defer { try? file.close() }
        if let start = try file.read(upToCount: 1 << 20) { hasher.update(data: start) }
        return Data(hasher.finalize())
    }

    private func send(_ source: URL, operation: UInt8, directoryID: String, header: Data, size: Int64, resumable: Bool,
                      progress: @MainActor (Int64, Int64) -> Void) async throws {
        let file = try FileHandle(forReadingFrom: source)
        defer { try? file.close() }
        let channel = try await open(operation: operation, id: directoryID)
        defer { channel.close() }
        try await channel.send(header)
        try await channel.status()
        let have = resumable ? Int64(clamping: try await channel.number(8)) : 0
        guard have >= 0, have <= size else { throw BridgeError.message("Invalid resume position.") }
        try file.seek(toOffset: UInt64(have))
        var sent = have, reported = have
        await progress(have, size)
        while sent < size {
            try Task.checkCancellation()
            guard let chunk = try file.read(upToCount: Int(min(65_536, size - sent))), !chunk.isEmpty else {
                throw BridgeError.message("Source file changed during upload.")
            }
            try await channel.send(chunk)
            sent += Int64(chunk.count)
            if sent - reported >= Self.progressStep || sent == size {
                reported = sent
                await progress(sent, size)
            }
        }
        // A completed send is not success until Android confirms publication.
        try await channel.status()
    }
}
