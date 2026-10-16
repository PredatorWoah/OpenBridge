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

    private func open(operation: UInt8, id: String) async throws -> TLSChannel {
        let channel = TLSChannel(host: host, port: port, fingerprint: fingerprint)
        do {
            try await channel.connect()
            try await channel.send(Data("OB01".utf8) + secret + Data([operation]) + Wire.string(id))
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

    public func download(_ entry: Entry, to destination: URL, progress: @MainActor (Int64, Int64) -> Void) async throws {
        // Fail before transferring anything; the final move below would refuse to replace it anyway.
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            throw BridgeError.message("\(destination.lastPathComponent) already exists. Choose a new name; OpenBridge never replaces files.")
        }
        let channel = try await open(operation: 2, id: entry.id)
        defer { channel.close() }
        try await channel.status()
        let length = try await channel.number(8)
        guard length <= Wire.maxFile else { throw BridgeError.message("Unsupported file size.") }
        let manager = FileManager.default
        // Sibling temporary file permits a same-volume atomic move without replacing an existing file.
        let temporary = destination.deletingLastPathComponent().appendingPathComponent(".openbridge-\(UUID().uuidString).part")
        guard manager.createFile(atPath: temporary.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
            throw BridgeError.message("Cannot create download in the selected folder.")
        }
        defer { try? manager.removeItem(at: temporary) }
        let file = try FileHandle(forWritingTo: temporary)
        do {
            var received: Int64 = 0, reported: Int64 = 0
            await progress(0, Int64(length))
            while received < length {
                try Task.checkCancellation()
                let chunk = try await channel.read(Int(min(65_536, length - UInt64(received))))
                try file.write(contentsOf: chunk)
                received += Int64(chunk.count)
                if received - reported >= Self.progressStep || received == Int64(length) {
                    reported = received
                    await progress(received, Int64(length))
                }
            }
            try file.synchronize()
            try file.close()
        } catch { try? file.close(); throw error }
        // FileManager.moveItem fails if destination exists; never replace it.
        try manager.moveItem(at: temporary, to: destination)
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
