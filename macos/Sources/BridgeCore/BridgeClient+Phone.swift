import Foundation

/// Operations 19 to 23 (phone app 1.2.0, INFO revision 23): what's happening on the phone, answering its
/// notifications, ringing it, opening a link on it and sending it a file without choosing a folder. Check the
/// revision before using them; older phones answer "Unsupported operation".
extension BridgeClient {
    /// The revision from which the phone answers operations 19 to 23.
    public static let phoneFeaturesRevision = 23

    /// Events after `cursor` (0 the first time), waiting up to `wait` milliseconds for one (EVENTS). Kinds this Mac
    /// doesn't know are skipped by their length.
    public func events(cursor: Int64, wait: Int32) async throws -> EventBatch {
        let channel = try await open(operation: 19, id: "", extra: EventCodec.eventsRequest(cursor: cursor, wait: wait))
        defer { channel.close() }
        try await channel.status(timeout: 35)
        let next: Int64 = Int64(bitPattern: try await channel.number(8))
        let count: UInt64 = try await channel.number(4)
        guard next >= 0, count <= UInt64(EventCodec.maxEvents) else { throw EventCodec.invalid }
        var events: [PhoneEvent] = []
        for _ in 0..<Int(count) {
            let kind: UInt8 = UInt8(try await channel.number(1))
            let length: UInt64 = try await channel.number(4)
            guard length <= UInt64(EventCodec.maxBody) else { throw EventCodec.invalid }
            let body: Data = try await channel.read(Int(length))
            if let event = try EventCodec.event(kind: kind, body: body) { events.append(event) }
        }
        return EventBatch(cursor: next, events: events)
    }

    /// Dismisses a notification on the phone, or answers it (NOTIFICATION ACTION).
    public func notificationAction(key: String, _ action: NotificationAction) async throws {
        try EventCodec.validateKey(key)
        let extra: Data = try EventCodec.actionRequest(action)
        let channel = try await open(operation: 20, id: key, extra: extra)
        defer { channel.close() }
        try await channel.status()
    }

    /// The revision from which the phone sends MEDIA events and answers MEDIA CONTROL (phone app 1.3.0).
    public static let mediaRevision = 24

    /// Plays, pauses, skips or seeks what's playing on the phone (MEDIA CONTROL, 24).
    public func mediaControl(_ command: MediaCommand) async throws {
        let extra: Data = try EventCodec.mediaRequest(command)
        let channel = try await open(operation: 24, id: "", extra: extra)
        defer { channel.close() }
        try await channel.status()
    }

    /// Rings the phone at full alarm volume for up to 60 seconds, or stops it (RING).
    public func ring(_ on: Bool) async throws {
        let channel = try await open(operation: 21, id: "", extra: Data([on ? 1 : 0]))
        defer { channel.close() }
        try await channel.status()
    }

    /// Shows a link on the phone as a notification that opens it (OPEN URL). Only http and https.
    public func openURL(_ link: String) async throws {
        let extra: Data = try EventCodec.openURLRequest(link)
        let channel = try await open(operation: 22, id: "", extra: extra)
        defer { channel.close() }
        try await channel.status()
    }

    /// Sends a file without choosing a folder (SEND): the phone saves it in Download/OpenBridge (or the shared folder)
    /// under a free name, never replacing a file, and returns that name. An interrupted send of the same file
    /// continues where it stopped, as with `upload`.
    public func sendFile(_ source: URL, progress: @MainActor (Int64, Int64) -> Void) async throws -> String {
        let name: String = Wire.phoneName(source.lastPathComponent)
        try Wire.validateName(name)
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey, .isSymbolicLinkKey, .contentModificationDateKey]
        let values: URLResourceValues = try source.resourceValues(forKeys: keys)
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let rawSize = values.fileSize, rawSize >= 0, Int64(rawSize) <= Wire.maxFile else {
            throw BridgeError.message("Only regular files up to 64 GiB can be sent to the phone.")
        }
        let size = Int64(rawSize)
        let key: Data = try Self.contentKey(source, size: size, modified: values.contentModificationDate)
        let header: Data = Wire.string(name) + Wire.integer(size) + key
        let saved: String? = try await send(source, operation: 23, directoryID: "", header: header, size: size,
                                            resumable: true, named: true, progress: progress)
        return saved ?? name
    }
}
