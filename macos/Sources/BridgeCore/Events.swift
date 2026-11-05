import Foundation

/// A notification on the phone's screen, as EVENTS (19) describes it. The same key again replaces the earlier one.
public struct PhoneNotification: Identifiable, Equatable {
    public let key: String
    public let app: String
    public let package: String
    public let title: String
    public let text: String
    /// When the phone posted it.
    public let time: Date
    /// It can be answered with a reply (flags bit 0).
    public let canReply: Bool
    /// It can be dismissed from the computer (flags bit 1).
    public let canDismiss: Bool
    /// A one-time code the phone found in the title or text, or empty.
    public let code: String

    public var id: String { key }

    public init(key: String, app: String, package: String, title: String, text: String, time: Date,
                canReply: Bool, canDismiss: Bool, code: String) {
        self.key = key; self.app = app; self.package = package; self.title = title; self.text = text
        self.time = time; self.canReply = canReply; self.canDismiss = canDismiss; self.code = code
    }
}

/// The phone's battery: a level from 0 to 100, and whether it's charging.
public struct BatteryState: Equatable {
    public let level: Int
    public let charging: Bool
    public init(level: Int, charging: Bool) { self.level = level; self.charging = charging }
}

/// What's playing on the phone (EVENTS kind 4 MEDIA, from phone app 1.3.0).
public struct MediaState: Equatable {
    public enum Playback: UInt8 {
        case none = 0, playing = 1, paused = 2
    }

    public let app: String
    public let title: String
    public let artist: String
    /// Milliseconds, or nil when the phone doesn't know.
    public let duration: Int64?
    /// Milliseconds when the phone sent this, or nil when unknown.
    public let position: Int64?
    public let playback: Playback
    public let canPlayPause: Bool
    public let canNext: Bool
    public let canPrevious: Bool
    public let canSeek: Bool

    public init(app: String, title: String, artist: String, duration: Int64?, position: Int64?, playback: Playback,
                canPlayPause: Bool, canNext: Bool, canPrevious: Bool, canSeek: Bool) {
        self.app = app; self.title = title; self.artist = artist
        self.duration = duration; self.position = position; self.playback = playback
        self.canPlayPause = canPlayPause; self.canNext = canNext; self.canPrevious = canPrevious; self.canSeek = canSeek
    }

    /// Something is playing or paused.
    public var active: Bool { playback != .none }

    /// The position `elapsed` seconds after the phone sent it: advancing while playing, never past the end.
    public func position(after elapsed: TimeInterval) -> Int64? {
        guard let position else { return nil }
        var now: Int64 = position
        if playback == .playing && elapsed > 0 { now += Int64((elapsed * 1000).rounded()) }
        if let duration, duration > 0 { now = min(now, duration) }
        return max(now, 0)
    }

    /// The same media at another position and state, as after a control from this Mac.
    public func with(position: Int64?, playback: Playback) -> MediaState {
        MediaState(app: app, title: title, artist: artist, duration: duration, position: position, playback: playback,
                   canPlayPause: canPlayPause, canNext: canNext, canPrevious: canPrevious, canSeek: canSeek)
    }
}

/// What a computer can ask of the phone's media (MEDIA CONTROL, 24).
public enum MediaCommand: Equatable {
    case play, pause, playPause, next, previous
    /// Milliseconds from the start.
    case seek(Int64)

    var code: UInt8 {
        switch self {
        case .play: return 1
        case .pause: return 2
        case .playPause: return 3
        case .next: return 4
        case .previous: return 5
        case .seek: return 6
        }
    }
}

/// One event from EVENTS. Kinds this Mac doesn't know are skipped while reading, so they never appear here.
public enum PhoneEvent: Equatable {
    /// Forget every notification shown from this phone; the current state follows.
    case reset
    case notification(PhoneNotification)
    /// The notification with this key is gone from the phone.
    case removed(String)
    case battery(BatteryState)
    /// What's playing changed (or nothing plays any more: `playback` is `.none`).
    case media(MediaState)
}

/// What one EVENTS reply said: the cursor to ask from next time, and the events this Mac understands, in order.
public struct EventBatch: Equatable {
    public let cursor: Int64
    public let events: [PhoneEvent]
    public init(cursor: Int64, events: [PhoneEvent]) { self.cursor = cursor; self.events = events }
}

/// What a computer can do with a phone notification (NOTIFICATION ACTION, 20).
public enum NotificationAction: Equatable {
    case dismiss
    case reply(String)
}

/// Reading and writing the bodies of operations 19 to 23. Separate from the connection, so it's tested on its own.
public enum EventCodec {
    public static let maxEvents = 200
    public static let maxBody = 65_536
    public static let maxWait: Int32 = 25_000
    public static let maxKey = 256
    public static let maxReply = 4_096
    public static let maxURL = 2_048

    /// One event's body. Returns nil for a kind this Mac doesn't know (skipped, as the protocol asks). Bytes after
    /// the fields of a known kind are ignored, so a later phone may add fields at the end.
    public static func event(kind: UInt8, body: Data) throws -> PhoneEvent? {
        var reader = ByteReader(body)
        switch kind {
        case 0:
            return .reset
        case 1:
            let key: String = try reader.string(limit: maxKey)
            let app: String = try reader.string(limit: 128)
            let package: String = try reader.string(limit: 128)
            let title: String = try reader.string(limit: 512)
            let text: String = try reader.string(limit: 4_096)
            let millis: Int64 = Int64(bitPattern: try reader.number(8))
            let flags: UInt64 = try reader.number(1)
            let code: String = try reader.string(limit: 16)
            guard !key.isEmpty else { throw invalid }
            let time = Date(timeIntervalSince1970: Double(millis) / 1000)
            let note = PhoneNotification(key: key, app: app, package: package, title: title, text: text, time: time,
                                         canReply: flags & 1 != 0, canDismiss: flags & 2 != 0, code: code)
            return .notification(note)
        case 2:
            let key: String = try reader.string(limit: maxKey)
            guard !key.isEmpty else { throw invalid }
            return .removed(key)
        case 3:
            let level: UInt64 = try reader.number(1)
            let charging: UInt64 = try reader.number(1)
            guard level <= 100, charging <= 1 else { throw invalid }
            return .battery(BatteryState(level: Int(level), charging: charging == 1))
        case 4:
            return .media(try media(&reader))
        default:
            return nil
        }
    }

    /// A MEDIA body: app, title, artist, duration and position (-1 when unknown), state and actions.
    static func media(_ reader: inout ByteReader) throws -> MediaState {
        let app: String = try reader.string(limit: 128)
        let title: String = try reader.string(limit: 512)
        let artist: String = try reader.string(limit: 512)
        let duration: Int64 = Int64(bitPattern: try reader.number(8))
        let position: Int64 = Int64(bitPattern: try reader.number(8))
        let state: UInt64 = try reader.number(1)
        let actions: UInt64 = try reader.number(1)
        guard duration >= -1, position >= -1, let playback = MediaState.Playback(rawValue: UInt8(truncatingIfNeeded: state)),
              state <= 2 else { throw invalid }
        return MediaState(app: app, title: title, artist: artist,
                          duration: duration < 0 ? nil : duration, position: position < 0 ? nil : position,
                          playback: playback, canPlayPause: actions & 1 != 0, canNext: actions & 2 != 0,
                          canPrevious: actions & 4 != 0, canSeek: actions & 8 != 0)
    }

    /// The MEDIA CONTROL request after the common header: the action byte, and for seek the position.
    public static func mediaRequest(_ command: MediaCommand) throws -> Data {
        guard case .seek(let millis) = command else { return Data([command.code]) }
        guard millis >= 0 else { throw BridgeError.message("Can't seek before the start.") }
        return Data([command.code]) + Wire.integer(millis)
    }

    /// A whole EVENTS reply after its status: cursor, count, then each kind, length and body.
    public static func batch(_ reply: Data) throws -> EventBatch {
        var reader = ByteReader(reply)
        let cursor: Int64 = Int64(bitPattern: try reader.number(8))
        let count: UInt64 = try reader.number(4)
        guard cursor >= 0, count <= UInt64(maxEvents) else { throw invalid }
        var events: [PhoneEvent] = []
        for _ in 0..<Int(count) {
            let kind: UInt8 = UInt8(try reader.number(1))
            let length: UInt64 = try reader.number(4)
            guard length <= UInt64(maxBody) else { throw invalid }
            let body: Data = try reader.take(Int(length))
            if let event = try event(kind: kind, body: body) { events.append(event) }
        }
        guard reader.atEnd else { throw invalid }
        return EventBatch(cursor: cursor, events: events)
    }

    /// The request after the common header: cursor, then the wait in milliseconds (clamped to 0...25,000).
    public static func eventsRequest(cursor: Int64, wait: Int32) -> Data {
        Wire.integer(max(cursor, 0)) + Wire.integer(min(max(wait, 0), maxWait))
    }

    /// The request after the common header for a notification action. The key goes in the document ID.
    public static func actionRequest(_ action: NotificationAction) throws -> Data {
        switch action {
        case .dismiss:
            return Data([1])
        case .reply(let text):
            let size: Int = text.utf8.count
            guard size >= 1, size <= maxReply else {
                throw BridgeError.message("A reply needs 1 to 4,096 bytes of text.")
            }
            return Data([2]) + Wire.string(text)
        }
    }

    /// A notification key as the phone sent it: 1 to 256 bytes.
    public static func validateKey(_ key: String) throws {
        guard !key.isEmpty, key.utf8.count <= maxKey else { throw BridgeError.message("That notification isn't valid.") }
    }

    /// Whether `text` is a link the phone opens: http or https, at most 2,048 bytes, with a host.
    public static func isWebLink(_ text: String) -> Bool {
        let trimmed: String = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= maxURL, !trimmed.contains(where: { $0.isWhitespace }),
              let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = url.host, !host.isEmpty else { return false }
        return true
    }

    /// The OPEN URL request: the link as a string, after checking it.
    public static func openURLRequest(_ link: String) throws -> Data {
        let trimmed: String = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isWebLink(trimmed) else {
            throw BridgeError.message("Only web links (http or https, at most 2,048 characters) open on the phone.")
        }
        return Wire.string(trimmed)
    }

    static var invalid: BridgeError { BridgeError.message("Invalid events reply.") }
}

/// Reads big-endian numbers and length-prefixed strings from bytes already received.
struct ByteReader {
    private let data: Data
    private var offset = 0

    init(_ data: Data) { self.data = Data(data) }

    var atEnd: Bool { offset == data.count }

    mutating func take(_ count: Int) throws -> Data {
        guard count >= 0, count <= data.count - offset else { throw EventCodec.invalid }
        let start: Int = data.startIndex + offset
        offset += count
        return data.subdata(in: start..<(start + count))
    }

    mutating func number(_ count: Int) throws -> UInt64 { Wire.number(try take(count)) }

    /// A string of at most `limit` UTF-8 bytes, strictly decoded.
    mutating func string(limit: Int) throws -> String {
        let size: UInt64 = try number(4)
        guard size <= UInt64(limit) else { throw EventCodec.invalid }
        guard let text = String(data: try take(Int(size)), encoding: .utf8) else { throw EventCodec.invalid }
        return text
    }
}
