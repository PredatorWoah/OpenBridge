import Foundation

/// The scrcpy 5.0.1 server protocol (Apache-2.0, https://github.com/Genymobile/scrcpy): the helper OpenBridge
/// pushes to the phone for screen mirroring. All integers are big endian. The version must match the bundled
/// server exactly.
public enum Scrcpy {
    public static let version = "5.0.1"
    public static let serverPath = "/data/local/tmp/scrcpy-server.jar"

    /// The shell command that starts the server. With `tunnel_forward` the phone listens on
    /// `localabstract:scrcpy_<scid>` and the Mac connects: video first, then control.
    public static func command(scid: UInt32, maxSize: Int) -> String {
        "CLASSPATH=\(serverPath) app_process / com.genymobile.scrcpy.Server \(version) scid=\(socketID(scid)) "
            + "log_level=info audio=false tunnel_forward=true max_size=\(maxSize) video_bit_rate=8000000 clipboard_autosync=true"
    }

    public static func socketID(_ scid: UInt32) -> String { String(format: "%08x", scid & 0x7FFF_FFFF) }
    public static func socket(_ scid: UInt32) -> String { "localabstract:scrcpy_\(socketID(scid))" }

    public static let h264: UInt32 = 0x6832_3634

    // MARK: Video stream

    /// What a 12-byte video header announces.
    public enum Header: Equatable {
        /// The encoder (re)started with this frame size, for example after rotation.
        case session(width: Int, height: Int)
        /// A packet of `size` bytes follows. A config packet carries SPS and PPS.
        case media(size: Int, config: Bool, keyFrame: Bool)
    }

    public static func parseHeader(_ bytes: Data) throws -> Header {
        let b = [UInt8](bytes.prefix(12))
        guard b.count == 12 else { throw BridgeError.message("Short video header.") }
        func u32(_ i: Int) -> UInt32 { b[i...i + 3].reduce(0) { $0 << 8 | UInt32($1) } }
        if b[0] & 0x80 != 0 {   // session packet: bit 31 of the first word
            return .session(width: Int(u32(4)), height: Int(u32(8)))
        }
        let flags = u32(0)
        let size = Int(u32(8))
        guard size > 0, size <= 64 << 20 else { throw BridgeError.message("Invalid video packet.") }
        return .media(size: size, config: flags & 0x4000_0000 != 0, keyFrame: flags & 0x2000_0000 != 0)
    }

    // MARK: Control messages (Mac to phone)

    public enum Action: UInt8 { case down = 0, up = 1, move = 2 }
    /// The mouse pointer; the phone treats it like a finger for taps and drags.
    public static let mousePointer: UInt64 = .max
    public static let primaryButton: Int32 = 1

    public static func touch(_ action: Action, x: Int, y: Int, width: Int, height: Int, pressure: Double,
                             pointer: UInt64 = mousePointer, actionButton: Int32, buttons: Int32) -> Data {
        var w = Writer(2)
        w.u8(action.rawValue); w.u64(pointer)
        w.position(x: x, y: y, width: width, height: height)
        w.u16(pressure >= 1 ? 0xFFFF : UInt16(max(0, pressure) * 65_536))
        w.i32(actionButton); w.i32(buttons)
        return w.data
    }

    /// Scroll by `dx`, `dy` notches; the phone scales them (16 notches per unit).
    public static func scroll(x: Int, y: Int, width: Int, height: Int, dx: Double, dy: Double) -> Data {
        func fixed(_ value: Double) -> Int16 {
            let unit = min(max(value / 16, -1), 1)
            return unit >= 1 ? Int16.max : Int16(unit * 32_768)
        }
        var w = Writer(3)
        w.position(x: x, y: y, width: width, height: height)
        w.i16(fixed(dx)); w.i16(fixed(dy)); w.i32(0)
        return w.data
    }

    public static func key(_ action: Action, keycode: Int32, repeat count: Int32 = 0, meta: Int32 = 0) -> Data {
        var w = Writer(0)
        w.u8(action.rawValue); w.i32(keycode); w.i32(count); w.i32(meta)
        return w.data
    }

    /// Types text; the server accepts at most 300 bytes per message, so longer text is split on characters.
    public static func text(_ text: String) -> [Data] {
        var messages: [Data] = [], piece = ""
        func flush() { if !piece.isEmpty { var w = Writer(1); w.string(piece); messages.append(w.data); piece = "" } }
        for character in text {
            if piece.utf8.count + String(character).utf8.count > 300 { flush() }
            piece.append(character)
        }
        flush()
        return messages
    }

    public static func backOrScreenOn(_ action: Action) -> Data {
        var w = Writer(4); w.u8(action.rawValue); return w.data
    }

    /// Puts text on the phone's clipboard; with `paste`, also pastes it where the cursor is.
    public static func setClipboard(_ text: String, paste: Bool = false) -> Data? {
        guard text.utf8.count <= 262_130 else { return nil }
        var w = Writer(9); w.u64(0); w.u8(paste ? 1 : 0); w.string(text)   // sequence 0: no acknowledgement wanted
        return w.data
    }

    public static func rotate() -> Data { Writer(11).data }

    // Android key codes used by the mirror window.
    public enum Key {
        public static let home: Int32 = 3, back: Int32 = 4, enter: Int32 = 66, delete: Int32 = 67, tab: Int32 = 61
        public static let up: Int32 = 19, down: Int32 = 20, left: Int32 = 21, right: Int32 = 22
        public static let volumeUp: Int32 = 24, volumeDown: Int32 = 25, power: Int32 = 26, recents: Int32 = 187, forwardDelete: Int32 = 112
    }

    // MARK: Device messages (phone to Mac)

    /// Reads one device message from the control stream; returns clipboard text, or nil for other messages.
    public static func readDeviceMessage(_ read: (Int) throws -> Data) throws -> String? {
        let type = try read(1)[0]
        func u32() throws -> Int { try read(4).reduce(0) { $0 << 8 | Int($1) } }
        switch type {
        case 0:   // CLIPBOARD
            let length = try u32()
            guard length <= 262_139 else { throw BridgeError.message("Clipboard text too large.") }
            return String(decoding: try read(length), as: UTF8.self)
        case 1:   // ACK_CLIPBOARD
            _ = try read(8); return nil
        case 2:   // UHID_OUTPUT
            _ = try read(2)
            let size = try read(2).reduce(0) { $0 << 8 | Int($1) }
            _ = try read(size); return nil
        default:
            throw BridgeError.message("Unknown message from the phone.")
        }
    }

    struct Writer {
        var data = Data()
        init(_ type: UInt8) { data.append(type) }
        mutating func u8(_ v: UInt8) { data.append(v) }
        mutating func u16(_ v: UInt16) { append(v.bigEndian) }
        mutating func i16(_ v: Int16) { append(v.bigEndian) }
        mutating func i32(_ v: Int32) { append(v.bigEndian) }
        mutating func u64(_ v: UInt64) { append(v.bigEndian) }
        mutating func position(x: Int, y: Int, width: Int, height: Int) {
            i32(Int32(clamping: x)); i32(Int32(clamping: y)); u16(UInt16(clamping: width)); u16(UInt16(clamping: height))
        }
        mutating func string(_ s: String) { let bytes = Data(s.utf8); append(UInt32(bytes.count).bigEndian); data.append(bytes) }
        private mutating func append<T>(_ value: T) { withUnsafeBytes(of: value) { data.append(contentsOf: $0) } }
    }
}

/// H.264 from Android's encoder comes as Annex B (start codes); Apple's decoder wants lengths instead.
public enum H264 {
    /// NAL units without their start codes.
    public static func units(_ annexB: Data) -> [Data] {
        let b = [UInt8](annexB)
        var starts: [(at: Int, length: Int)] = []
        var i = 0
        while i + 3 <= b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 { starts.append((i, 3)); i += 3 }
            else if i + 4 <= b.count, b[i] == 0, b[i + 1] == 0, b[i + 2] == 0, b[i + 3] == 1 { starts.append((i, 4)); i += 4 }
            else { i += 1 }
        }
        return starts.enumerated().compactMap { index, start in
            let from = start.at + start.length
            let to = index + 1 < starts.count ? starts[index + 1].at : b.count
            return to > from ? Data(b[from..<to]) : nil
        }
    }

    /// Each unit prefixed with its 4-byte big-endian length (AVCC).
    public static func lengthPrefixed(_ units: [Data]) -> Data {
        var out = Data()
        for unit in units {
            withUnsafeBytes(of: UInt32(unit.count).bigEndian) { out.append(contentsOf: $0) }
            out.append(unit)
        }
        return out
    }

    public static func type(_ unit: Data) -> UInt8 { unit.first.map { $0 & 0x1F } ?? 0 }
}
