import Foundation

/// MTP (PTP) failures. Response errors leave the session usable; the others mean it must be reopened.
public enum MTPError: LocalizedError, Equatable {
    case response(UInt16)
    case protocolViolation(String)
    case transport(String)

    public var errorDescription: String? {
        switch self {
        case .response(let code): return MTPError.describe(code)
        case .protocolViolation(let detail): return "The phone sent an unexpected reply (\(detail)). Unplug it and try again."
        case .transport(let detail): return detail
        }
    }

    static func describe(_ code: UInt16) -> String {
        switch code {
        case 0x2003: return "The phone closed the USB session. Unlock it and choose File transfer again."
        case 0x2008, 0x2009: return "That file or folder no longer exists on the phone. Refresh and try again."
        case 0x200C: return "The phone's storage is full."
        case 0x200D, 0x200E: return "The phone does not allow writing there."
        case 0x2019: return "The phone is busy. Try again in a moment."
        default: return "The phone refused the request (MTP code 0x\(String(code, radix: 16)))."
        }
    }

    /// Whether the session is still in a consistent state after this error.
    public var keepsSession: Bool { if case .response = self { return true } else { return false } }
}

enum MTPCode {
    static let getDeviceInfo: UInt16 = 0x1001
    static let openSession: UInt16 = 0x1002
    static let closeSession: UInt16 = 0x1003
    static let getStorageIDs: UInt16 = 0x1004
    static let getStorageInfo: UInt16 = 0x1005
    static let getObjectHandles: UInt16 = 0x1007
    static let getObjectInfo: UInt16 = 0x1008
    static let getObject: UInt16 = 0x1009
    static let deleteObject: UInt16 = 0x100B
    static let sendObjectInfo: UInt16 = 0x100C
    static let sendObject: UInt16 = 0x100D
    static let getObjectPropValue: UInt16 = 0x9803

    static let ok: UInt16 = 0x2001
    static let sessionAlreadyOpen: UInt16 = 0x201E

    static let undefinedFormat: UInt16 = 0x3000
    static let associationFormat: UInt16 = 0x3001
    static let genericFolder: UInt16 = 0x0001
    static let objectSizeProperty: UInt16 = 0xDC04
}

enum ContainerType: UInt16 {
    case command = 1, data = 2, response = 3, event = 4
}

/// Every MTP container starts with this 12-byte little-endian header.
struct ContainerHeader: Equatable {
    static let size = 12
    /// Length value used when a data phase is larger than a 32-bit length can describe.
    static let unknownLength: UInt32 = 0xFFFF_FFFF
    let length: UInt32
    let type: UInt16
    let code: UInt16
    let transactionID: UInt32

    init(length: UInt32, type: ContainerType, code: UInt16, transactionID: UInt32) {
        self.length = length; self.type = type.rawValue; self.code = code; self.transactionID = transactionID
    }

    init(parsing bytes: Data) throws {
        guard bytes.count >= ContainerHeader.size else { throw MTPError.protocolViolation("short container") }
        var reader = MTPReader(bytes.prefix(ContainerHeader.size))
        length = try reader.u32(); type = try reader.u16(); code = try reader.u16(); transactionID = try reader.u32()
        guard length == ContainerHeader.unknownLength || length >= ContainerHeader.size else {
            throw MTPError.protocolViolation("invalid container length")
        }
    }

    var encoded: Data {
        var writer = MTPWriter()
        writer.u32(length); writer.u16(type); writer.u16(code); writer.u32(transactionID)
        return writer.data
    }

    static func command(_ code: UInt16, transactionID: UInt32, parameters: [UInt32]) -> Data {
        var data = ContainerHeader(length: UInt32(size + 4 * parameters.count), type: .command,
                                   code: code, transactionID: transactionID).encoded
        var writer = MTPWriter()
        parameters.forEach { writer.u32($0) }
        data.append(writer.data)
        return data
    }
}

/// Little-endian encoder for MTP datasets.
struct MTPWriter {
    private(set) var data = Data()
    mutating func u8(_ value: UInt8) { data.append(value) }
    mutating func u16(_ value: UInt16) { append(value) }
    mutating func u32(_ value: UInt32) { append(value) }
    mutating func u64(_ value: UInt64) { append(value) }
    private mutating func append<T: FixedWidthInteger>(_ value: T) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
    /// PTP string: UTF-16 code unit count including the terminator (one byte), then UTF-16LE units.
    mutating func string(_ value: String) throws {
        if value.isEmpty { u8(0); return }
        let units = Array(value.utf16)
        guard units.count < 255 else { throw MTPError.protocolViolation("name too long") }
        u8(UInt8(units.count + 1))
        units.forEach { u16($0) }
        u16(0)
    }
}

/// Bounds-checked little-endian decoder for MTP datasets.
struct MTPReader {
    private let bytes: [UInt8]
    private(set) var offset = 0
    init(_ data: Data) { bytes = Array(data) }
    var remaining: Int { bytes.count - offset }

    mutating func u8() throws -> UInt8 { UInt8(try integer(1)) }
    mutating func u16() throws -> UInt16 { UInt16(try integer(2)) }
    mutating func u32() throws -> UInt32 { UInt32(try integer(4)) }
    mutating func u64() throws -> UInt64 { try integer(8) }

    private mutating func integer(_ size: Int) throws -> UInt64 {
        guard remaining >= size else { throw MTPError.protocolViolation("truncated dataset") }
        var value: UInt64 = 0
        for index in 0..<size { value |= UInt64(bytes[offset + index]) << (8 * index) }
        offset += size
        return value
    }

    mutating func string() throws -> String {
        let count = Int(try u8())
        if count == 0 { return "" }
        var units: [UInt16] = []
        for _ in 0..<count { units.append(try u16()) }
        if units.last == 0 { units.removeLast() }
        return String(decoding: units, as: UTF16.self)
    }

    mutating func u16Array() throws -> [UInt16] {
        let count = Int(try u32())
        guard count <= remaining / 2 else { throw MTPError.protocolViolation("array too long") }
        return try (0..<count).map { _ in try u16() }
    }

    mutating func u32Array() throws -> [UInt32] {
        let count = Int(try u32())
        guard count <= remaining / 4 else { throw MTPError.protocolViolation("array too long") }
        return try (0..<count).map { _ in try u32() }
    }
}

/// Who a phone is, so a reconnect never silently continues on a different device.
public struct MTPDeviceIdentity: Equatable {
    public let manufacturer: String
    public let model: String
    public let serialNumber: String
    public init(manufacturer: String, model: String, serialNumber: String) {
        self.manufacturer = manufacturer; self.model = model; self.serialNumber = serialNumber
    }
}

public struct MTPDeviceInfo: Equatable {
    public let manufacturer: String
    public let model: String
    public let serialNumber: String
    public let vendorExtension: String

    public var identity: MTPDeviceIdentity {
        MTPDeviceIdentity(manufacturer: manufacturer, model: model, serialNumber: serialNumber)
    }

    init(parsing data: Data) throws {
        var reader = MTPReader(data)
        _ = try reader.u16()              // standard version
        _ = try reader.u32()              // vendor extension ID
        _ = try reader.u16()              // vendor extension version
        vendorExtension = try reader.string()
        _ = try reader.u16()              // functional mode
        for _ in 0..<5 { _ = try reader.u16Array() } // operations, events, properties, capture and playback formats
        manufacturer = try reader.string()
        model = try reader.string()
        _ = try reader.string()           // device version
        serialNumber = try reader.string()
    }

    /// A short human-readable name, avoiding a repeated manufacturer ("Google Pixel 8").
    public var displayName: String {
        let name = model.lowercased().hasPrefix(manufacturer.lowercased()) || manufacturer.isEmpty
            ? model : "\(manufacturer) \(model)"
        return name.trimmingCharacters(in: .whitespaces).isEmpty ? "Android phone" : name
    }
}

public struct MTPStorage: Equatable {
    public let id: UInt32
    public let name: String
    public let freeSpace: UInt64

    init(id: UInt32, parsing data: Data) throws {
        var reader = MTPReader(data)
        _ = try reader.u16()              // storage type
        _ = try reader.u16()              // filesystem type
        _ = try reader.u16()              // access capability
        _ = try reader.u64()              // max capacity
        freeSpace = try reader.u64()
        _ = try reader.u32()              // free space in objects
        let description = try reader.string()
        let label = try reader.string()
        self.id = id
        name = description.isEmpty ? (label.isEmpty ? "Storage" : label) : description
    }
}

public struct MTPObjectInfo: Equatable {
    public var storageID: UInt32
    public var format: UInt16
    public var compressedSize: UInt32
    public var parent: UInt32
    public var associationType: UInt16
    public var filename: String

    public var isFolder: Bool { format == MTPCode.associationFormat }

    init(storageID: UInt32, format: UInt16, compressedSize: UInt32, parent: UInt32, associationType: UInt16 = 0, filename: String) {
        self.storageID = storageID; self.format = format; self.compressedSize = compressedSize
        self.parent = parent; self.associationType = associationType; self.filename = filename
    }

    init(parsing data: Data) throws {
        var reader = MTPReader(data)
        storageID = try reader.u32()
        format = try reader.u16()
        _ = try reader.u16()              // protection status
        compressedSize = try reader.u32()
        _ = try reader.u16()              // thumb format
        for _ in 0..<6 { _ = try reader.u32() } // thumb size/width/height, image width/height/depth
        parent = try reader.u32()
        associationType = try reader.u16()
        _ = try reader.u32()              // association description
        _ = try reader.u32()              // sequence number
        filename = try reader.string()
    }

    func encoded() throws -> Data {
        var writer = MTPWriter()
        writer.u32(storageID); writer.u16(format); writer.u16(0); writer.u32(compressedSize)
        writer.u16(0); for _ in 0..<6 { writer.u32(0) }
        writer.u32(parent); writer.u16(associationType); writer.u32(0); writer.u32(0)
        try writer.string(filename)
        try writer.string("")             // date created
        try writer.string("")             // date modified
        try writer.string("")             // keywords
        return writer.data
    }
}
