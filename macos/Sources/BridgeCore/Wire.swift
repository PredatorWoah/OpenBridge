import Foundation

public enum BridgeError: LocalizedError {
    case message(String)
    /// The phone no longer trusts this Mac (forgotten on the phone, or its app reinstalled): pair again.
    case forgotten
    public var errorDescription: String? {
        switch self {
        case .message(let message): return message
        case .forgotten: return "This phone no longer trusts this Mac (it was forgotten on the phone, or OpenBridge was reinstalled there). Pair again."
        }
    }
}

extension BridgeError {
    /// The phone app is too old for this operation ("Unsupported operation; update OpenBridge on the phone").
    public static func isUnsupported(_ error: Error) -> Bool {
        if case BridgeError.message(let text)? = error as? BridgeError { return text.contains("Unsupported operation") }
        return false
    }
}

/// Something the phone is sending: a file for Downloads/OpenBridge, or a text for the clipboard (its `size` in bytes).
public struct OutboxItem: Equatable {
    public let entry: Entry
    public let text: Bool
    public init(entry: Entry, text: Bool) { self.entry = entry; self.text = text }
}

/// What INFO says about the phone: its OpenBridge version, the highest operation it answers, and its name.
public struct PhoneInfo: Equatable {
    public let version: String
    public let revision: Int
    public let name: String
    public init(version: String, revision: Int, name: String) { self.version = version; self.revision = revision; self.name = name }
}

/// A search hit, and the folder it's in by name, relative to where the search started ("DCIM/Camera").
public struct Found: Identifiable, Equatable {
    public let entry: Entry
    public let location: String
    public var id: String { entry.id }
}

/// What a search found; `complete` is false when it stopped early (time or result limit).
public struct SearchResult: Equatable {
    public let found: [Found]
    public let complete: Bool
}

/// What MEASURE found in a folder. `complete` is false when the phone stopped counting early.
public struct FolderSize: Equatable {
    public let bytes: Int64, files: Int64, complete: Bool
}

public struct Entry: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let directory: Bool
    public let size: Int64
}

public enum Wire {
    public static let maxFile: Int64 = 64 * 1024 * 1024 * 1024
    public static let maxClipboard = 256 * 1024
    public static func integer<T: FixedWidthInteger>(_ value: T) -> Data {
        var big = value.bigEndian
        return withUnsafeBytes(of: &big) { Data($0) }
    }
    public static func number(_ bytes: Data) -> UInt64 {
        bytes.reduce(0) { ($0 << 8) | UInt64($1) }
    }
    public static func string(_ value: String) -> Data {
        let bytes = Data(value.utf8)
        return integer(UInt32(bytes.count)) + bytes
    }
    public static func hex(_ text: String) throws -> Data {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard clean.utf8.count == 64, clean.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw BridgeError.message("Fingerprint and pairing secret must each contain 64 hexadecimal characters.")
        }
        let chars = Array(clean)
        return Data(try stride(from: 0, to: chars.count, by: 2).map {
            guard let byte = UInt8(String(chars[$0...$0 + 1]), radix: 16) else {
                throw BridgeError.message("Invalid hexadecimal credentials.")
            }
            return byte
        })
    }
    public static func privateIPv4(_ host: String) -> Bool {
        let pieces = host.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count == 4 else { return false }
        var bytes: [UInt8] = []
        for piece in pieces {
            guard piece.allSatisfy({ $0.isASCII && $0.isNumber }), let byte = UInt8(piece),
                  String(byte) == piece else { return false }
            bytes.append(byte)
        }
        return bytes[0] == 10 || (bytes[0] == 172 && (16...31).contains(bytes[1])) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }
    /// `name` shortened to at most `limit` UTF-8 bytes (Mac file names hold 255), cut at a character and keeping the
    /// extension: "很长的名字….txt". Phones allow longer names than the Mac over USB.
    public static func fitted(_ name: String, limit: Int = 255) -> String {
        guard name.utf8.count > limit else { return name }
        let ext = (name as NSString).pathExtension
        let suffix = ext.isEmpty || ext.utf8.count > limit / 4 ? "" : "." + ext
        var base = ""
        for character in suffix.isEmpty ? name : (name as NSString).deletingPathExtension {
            if base.utf8.count + String(character).utf8.count + suffix.utf8.count > limit { break }
            base.append(character)
        }
        return base + suffix
    }

    public static func validateName(_ name: String) throws {
        guard !name.isEmpty, name != ".", name != "..", name.utf8.count <= 255,
              !name.contains("/"), !name.contains("\\"),
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw BridgeError.message("Unsupported filename.")
        }
    }
}
