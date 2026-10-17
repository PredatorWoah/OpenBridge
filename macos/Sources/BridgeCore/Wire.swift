import Foundation

public enum BridgeError: LocalizedError {
    case message(String)
    public var errorDescription: String? {
        switch self { case .message(let message): return message }
    }
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
    public static func validateName(_ name: String) throws {
        guard !name.isEmpty, name != ".", name != "..", name.utf8.count <= 255,
              !name.contains("/"), !name.contains("\\"),
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw BridgeError.message("Unsupported filename.")
        }
    }
}
