import Foundation

/// Bytes of a phone file from an offset, on a connection of its own (RESUME, 6), for playing media while it arrives.
/// Read until `remaining` is 0, or close it early: the player asks for another range when it seeks.
public final class FileRange {
    private let channel: TLSChannel
    /// The file's whole size, as the phone said.
    public let size: Int64
    /// Where the next byte read comes from.
    public private(set) var offset: Int64

    init(channel: TLSChannel, size: Int64, offset: Int64) {
        self.channel = channel; self.size = size; self.offset = offset
    }

    public var remaining: Int64 { size - offset }

    /// The next bytes, at most `limit` of them; empty at the end of the file.
    public func read(upTo limit: Int = 65_536) async throws -> Data {
        let count: Int64 = min(Int64(max(limit, 1)), remaining)
        guard count > 0 else { return Data() }
        let chunk: Data = try await channel.read(Int(count))
        offset += Int64(chunk.count)
        return chunk
    }

    public func close() { channel.close() }

    deinit { channel.close() }
}

extension BridgeClient {
    /// Opens `entry` at `offset` (RESUME). Throws `BridgeError.fileChanged` when the phone's file is no longer the size
    /// it was listed with.
    public func range(of entry: Entry, from offset: Int64) async throws -> FileRange {
        guard offset >= 0, offset <= entry.size else { throw BridgeError.message("Invalid position in the file.") }
        let channel = try await open(operation: 6, id: entry.id, extra: Wire.integer(offset), connectTimeout: 10)
        do {
            try await channel.status()
            let total: UInt64 = try await channel.number(8)
            guard total <= UInt64(Wire.maxFile) else { throw BridgeError.message("Unsupported file size.") }
            guard Int64(total) == entry.size else { throw BridgeError.fileChanged }
            return FileRange(channel: channel, size: Int64(total), offset: offset)
        } catch {
            channel.close()
            throw error
        }
    }
}
