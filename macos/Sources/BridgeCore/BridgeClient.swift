import Foundation

public struct BridgeClient {
    private let host: String
    private let port: UInt16
    private let fingerprint: Data
    private let secret: Data
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

    // Internal injection for loopback TLS interoperability tests; public initialization validates LAN addresses.
    init(testHost: String, port: UInt16, fingerprint: Data, secret: Data) {
        self.host = testHost; self.port = port; self.fingerprint = fingerprint; self.secret = secret
    }

    private func open(operation: UInt8, id: String, extra: Data = Data()) async throws -> TLSChannel {
        let channel = TLSChannel(host: host, port: port, fingerprint: fingerprint)
        do {
            try await channel.connect()
            try await channel.send(Data("OB01".utf8) + secret + Data([operation]) + Wire.string(id) + extra)
            return channel
        } catch { channel.close(); throw error }
    }

    public func list(id: String = "") async throws -> [Entry] {
        let channel = try await open(operation: 1, id: id)
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

    public func upload(_ source: URL, directoryID: String, progress: @MainActor (Int64, Int64) -> Void) async throws {
        let name = source.lastPathComponent
        try Wire.validateName(name)
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let rawSize = values.fileSize, rawSize >= 0, rawSize <= Wire.maxFile else {
            throw BridgeError.message("Choose a regular file no larger than 64 GiB.")
        }
        let size = Int64(rawSize)
        let file = try FileHandle(forReadingFrom: source)
        defer { try? file.close() }
        let channel = try await open(operation: 3, id: directoryID)
        defer { channel.close() }
        try await channel.send(Wire.string(name) + Wire.integer(size))
        try await channel.status()
        var sent: Int64 = 0, reported: Int64 = 0
        await progress(0, size)
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
