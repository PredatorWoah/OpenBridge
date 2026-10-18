import Foundation

/// One USB bulk pipe pair. Each call is one USB transfer.
public protocol MTPTransport: AnyObject {
    /// Bulk endpoint packet size. Transfers that are not a multiple of it end a data phase.
    var maxPacketSize: Int { get }
    func write(_ data: Data) throws
    /// Returns when `maximum` bytes arrive or the device ends the transfer with a short or zero-length packet.
    func read(maximum: Int) throws -> Data
    /// Abandons a half-finished transfer so the device is ready for a new session.
    func abort()
    func close()
}

/// Synchronous MTP session over a transport. Not thread safe: use from one queue.
public final class MTPSession {
    /// Parent handle meaning "top level of a storage".
    public static let root: UInt32 = 0xFFFF_FFFF
    /// Files at least this large are sent with an unknown (0xFFFFFFFF) size and ended by a short packet,
    /// as Android expects for files of 4 GiB or more. Lowered only by tests.
    var sizeFieldLimit: UInt64 = 0xFFFF_FFFF

    let transport: MTPTransport
    let chunkSize: Int
    private var transactionID: UInt32 = 0

    public init(transport: MTPTransport, chunkSize: Int = 1 << 20) {
        self.transport = transport
        let packet = max(transport.maxPacketSize, 1)
        // Intermediate transfers must be whole packets, or the device sees the data phase end early.
        self.chunkSize = max(packet, chunkSize / packet * packet)
    }

    // MARK: Operations

    /// Works before a session is open, so it is used to recognise MTP devices.
    public func deviceInfo() throws -> MTPDeviceInfo {
        try MTPDeviceInfo(parsing: try dataIn(MTPCode.getDeviceInfo, transactionID: 0))
    }

    public func open() throws {
        do { _ = try simple(MTPCode.openSession, [1], transactionID: 0) }
        catch MTPError.response(MTPCode.sessionAlreadyOpen) {
            // A previous app instance left a session open; restart it.
            _ = try simple(MTPCode.closeSession, [])
            _ = try simple(MTPCode.openSession, [1], transactionID: 0)
        }
        transactionID = 0
    }

    public func close() {
        _ = try? simple(MTPCode.closeSession, [])
        transport.close()
    }

    /// Drops the session after a failed or cancelled transfer, without talking MTP over a confused pipe.
    public func abandon() {
        transport.abort()
        transport.close()
    }

    public func delete(_ handle: UInt32) throws {
        _ = try simple(MTPCode.deleteObject, [handle, 0])
    }

    /// Renames an object in place (its file name property). The caller checks the name is free first.
    public func rename(_ handle: UInt32, to name: String) throws {
        var writer = MTPWriter()
        try writer.string(name)
        _ = try dataOut(MTPCode.setObjectPropValue, [handle, UInt32(MTPCode.objectFileNameProperty)], payload: writer.data)
    }

    public func storages() throws -> [MTPStorage] {
        var reader = MTPReader(try dataIn(MTPCode.getStorageIDs))
        return try reader.u32Array().map { id in
            try MTPStorage(id: id, parsing: try dataIn(MTPCode.getStorageInfo, [id]))
        }
    }

    public func children(storage: UInt32, parent: UInt32) throws -> [(handle: UInt32, info: MTPObjectInfo)] {
        var reader = MTPReader(try dataIn(MTPCode.getObjectHandles, [storage, 0, parent]))
        return try reader.u32Array().map { handle in
            (handle, try MTPObjectInfo(parsing: try dataIn(MTPCode.getObjectInfo, [handle])))
        }
    }

    /// One item in a folder, as needed for sizes and search.
    public struct Child: Equatable {
        public let handle: UInt32
        public let folder: Bool
        public let size: UInt64
        public let name: String
    }

    /// Everything directly inside `parent`. Uses Android's GetObjectPropList, which answers for a whole folder in one
    /// request per property; phones without it (and storage tops, which it can't tell apart) are asked per object.
    public func contents(storage: UInt32, parent: UInt32, names: Bool) throws -> [Child] {
        if parent != MTPSession.root {
            do {
                let formats = try propertyList(parent, MTPCode.objectFormatProperty)
                let sizes = try propertyList(parent, MTPCode.objectSizeProperty)
                let named = names ? try propertyList(parent, MTPCode.objectFileNameProperty) : [:]
                // Android lists the folder itself along with what's in it: leave it out, or it would contain itself.
                return formats.keys.filter { $0 != parent }.sorted().map { handle in
                    let size: UInt64
                    if case .number(let value)? = sizes[handle] { size = value } else { size = 0 }
                    var name = ""
                    if case .text(let value)? = named[handle] { name = value }
                    var folder = false
                    if case .number(let value)? = formats[handle] { folder = value == UInt64(MTPCode.associationFormat) }
                    return Child(handle: handle, folder: folder, size: size, name: name)
                }
            } catch MTPError.response {
                // Not supported here: ask object by object below.
            }
        }
        return try children(storage: storage, parent: parent).map { item in
            Child(handle: item.handle, folder: item.info.isFolder,
                  size: item.info.isFolder ? 0 : try size(of: item.handle, info: item.info), name: item.info.filename)
        }
    }

    public enum PropertyValue: Equatable { case number(UInt64), text(String) }

    /// One property of every object directly inside `parent` (GetObjectPropList, depth 1).
    func propertyList(_ parent: UInt32, _ property: UInt16) throws -> [UInt32: PropertyValue] {
        var reader = MTPReader(try dataIn(MTPCode.getObjectPropList, [parent, 0, UInt32(property), 0, 1]))
        let count = try reader.u32()
        guard count <= 1_000_000 else { throw MTPError.protocolViolation("property list too long") }
        var values: [UInt32: PropertyValue] = [:]
        for _ in 0..<count {
            let handle = try reader.u32()
            _ = try reader.u16()          // property code
            let type = try reader.u16()
            switch type {
            case 0x0001, 0x0002: values[handle] = .number(UInt64(try reader.u8()))
            case 0x0003, 0x0004: values[handle] = .number(UInt64(try reader.u16()))
            case 0x0005, 0x0006: values[handle] = .number(UInt64(try reader.u32()))
            case 0x0007, 0x0008: values[handle] = .number(try reader.u64())
            case 0x0009, 0x000A: _ = try reader.u64(); values[handle] = .number(try reader.u64())   // 128-bit: low half
            case 0xFFFF: values[handle] = .text(try reader.string())
            default: throw MTPError.protocolViolation("property type \(type)")
            }
        }
        return values
    }

    public func info(_ handle: UInt32) throws -> MTPObjectInfo {
        try MTPObjectInfo(parsing: try dataIn(MTPCode.getObjectInfo, [handle]))
    }

    /// File size, using the 64-bit size property for files of 4 GiB or more.
    public func size(of handle: UInt32, info: MTPObjectInfo) throws -> UInt64 {
        guard info.compressedSize == 0xFFFF_FFFF else { return UInt64(info.compressedSize) }
        var reader = MTPReader(try dataIn(MTPCode.getObjectPropValue, [handle, UInt32(MTPCode.objectSizeProperty)]))
        return try reader.u64()
    }

    public func beginDownload(_ handle: UInt32) throws -> MTPInbound {
        try MTPInbound(session: self, code: MTPCode.getObject, transactionID: try command(MTPCode.getObject, [handle]))
    }

    /// Starts a download of `count` bytes from `offset` (Android's GetPartialObject64), to resume a download.
    public func beginPartialDownload(_ handle: UInt32, offset: UInt64, count: UInt32) throws -> MTPInbound {
        let parameters = [handle, UInt32(truncatingIfNeeded: offset), UInt32(truncatingIfNeeded: offset >> 32), count]
        return try MTPInbound(session: self, code: MTPCode.getPartialObject64,
                              transactionID: try command(MTPCode.getPartialObject64, parameters))
    }

    /// The phone's own JPEG preview of a photo or video.
    public func thumbnail(_ handle: UInt32) throws -> Data {
        try dataIn(MTPCode.getThumb, [handle])
    }

    /// Creates an empty folder and returns its handle. Folders need no data phase.
    public func createFolder(storage: UInt32, parent: UInt32, name: String) throws -> UInt32 {
        let info = MTPObjectInfo(storageID: storage, format: MTPCode.associationFormat, compressedSize: 0, parent: parent,
                                 associationType: MTPCode.genericFolder, filename: name)
        let reply = try dataOut(MTPCode.sendObjectInfo, [storage, parent], payload: try info.encoded())
        guard reply.count >= 3 else { throw MTPError.protocolViolation("missing folder handle") }
        return reply[2]
    }

    /// Creates the (still empty) object on the phone. From here on, a failed upload must be cleaned up
    /// with `deleteIfUnchanged`.
    public func createObject(storage: UInt32, parent: UInt32, name: String, size: UInt64) throws -> MTPCreatedObject {
        let large = size >= sizeFieldLimit
        let info = MTPObjectInfo(storageID: storage, format: MTPCode.undefinedFormat,
                                 compressedSize: large ? 0xFFFF_FFFF : UInt32(size), parent: parent, filename: name)
        let reply = try dataOut(MTPCode.sendObjectInfo, [storage, parent], payload: try info.encoded())
        guard reply.count >= 3 else { throw MTPError.protocolViolation("missing object handle") }
        return MTPCreatedObject(handle: reply[2], storage: reply[0], parent: reply[1], name: name, size: size, unknownLength: large)
    }

    /// Starts the data phase for an object just created with `createObject`.
    public func beginSend(_ object: MTPCreatedObject) throws -> MTPOutbound {
        let id = try command(MTPCode.sendObject, [])
        return MTPOutbound(session: self, code: MTPCode.sendObject, transactionID: id, length: object.size, unknownLength: object.unknownLength)
    }

    /// Test convenience: create and start sending in one step.
    func beginUpload(storage: UInt32, parent: UInt32, name: String, size: UInt64) throws -> (handle: UInt32, stream: MTPOutbound) {
        let object = try createObject(storage: storage, parent: parent, name: name, size: size)
        return (object.handle, try beginSend(object))
    }

    /// Deletes a failed upload only if the handle still names that same object; handles are just numbers
    /// and must never be trusted on their own. Returns whether it deleted anything.
    public func deleteIfUnchanged(_ object: MTPCreatedObject) throws -> Bool {
        guard let current = try? info(object.handle) else { return false }
        // Phones may report top-level objects with parent 0 instead of the root marker.
        let sameParent = current.parent == object.parent
            || (object.parent == MTPSession.root && current.parent == 0)
            || (object.parent == 0 && current.parent == MTPSession.root)
        guard current.filename == object.name, current.storageID == object.storage, sameParent, !current.isFolder else { return false }
        try delete(object.handle)
        return true
    }

    // MARK: Transactions

    private func nextTransactionID() -> UInt32 {
        transactionID &+= 1
        if transactionID == 0 || transactionID == 0xFFFF_FFFF { transactionID = 1 }
        return transactionID
    }

    func command(_ code: UInt16, _ parameters: [UInt32], transactionID explicit: UInt32? = nil) throws -> UInt32 {
        let id = explicit ?? nextTransactionID()
        try transport.write(ContainerHeader.command(code, transactionID: id, parameters: parameters))
        return id
    }

    func response(transactionID id: UInt32) throws -> [UInt32] {
        var bytes = Data()
        // A zero-length packet that ended the previous data phase may still be queued.
        for _ in 0..<3 where bytes.isEmpty { bytes = try transport.read(maximum: max(transport.maxPacketSize, 512)) }
        let header = try ContainerHeader(parsing: bytes)
        guard header.type == ContainerType.response.rawValue else { throw MTPError.protocolViolation("expected a response") }
        return try finishResponse(header, bytes, transactionID: id)
    }

    func finishResponse(_ header: ContainerHeader, _ bytes: Data, transactionID id: UInt32) throws -> [UInt32] {
        guard header.transactionID == id else { throw MTPError.protocolViolation("transaction mismatch") }
        guard header.code == MTPCode.ok else { throw MTPError.response(header.code) }
        var reader = MTPReader(bytes.dropFirst(ContainerHeader.size).prefix(Int(header.length) - ContainerHeader.size))
        var parameters: [UInt32] = []
        while reader.remaining >= 4 { parameters.append(try reader.u32()) }
        return parameters
    }

    func simple(_ code: UInt16, _ parameters: [UInt32], transactionID explicit: UInt32? = nil) throws -> [UInt32] {
        try response(transactionID: try command(code, parameters, transactionID: explicit))
    }

    func dataIn(_ code: UInt16, _ parameters: [UInt32] = [], transactionID explicit: UInt32? = nil) throws -> Data {
        let stream = try MTPInbound(session: self, code: code, transactionID: try command(code, parameters, transactionID: explicit))
        var result = Data()
        while let chunk = try stream.next() {
            result.append(chunk)
            guard result.count <= 16 << 20 else { throw MTPError.protocolViolation("dataset too large") }
        }
        _ = try stream.finish()
        return result
    }

    func dataOut(_ code: UInt16, _ parameters: [UInt32], payload: Data) throws -> [UInt32] {
        let stream = MTPOutbound(session: self, code: code, transactionID: try command(code, parameters), length: UInt64(payload.count))
        try stream.write(payload)
        return try stream.finish()
    }
}

/// An object created on the phone by `createObject`, before or during its upload.
public struct MTPCreatedObject: Equatable {
    public let handle: UInt32
    public let storage: UInt32
    public let parent: UInt32
    public let name: String
    public let size: UInt64
    let unknownLength: Bool
}

/// Device-to-host data phase, read in chunks, then its response.
public final class MTPInbound {
    private let session: MTPSession
    private let transactionID: UInt32
    private var pending: Data?
    /// Bytes still expected, or nil when the device sent an unknown (64-bit) length.
    private var remaining: UInt64?
    private var ended = false
    private var earlyResponse: [UInt32]?
    public let length: UInt64?

    init(session: MTPSession, code: UInt16, transactionID: UInt32) throws {
        self.session = session; self.transactionID = transactionID
        let first = try session.transport.read(maximum: session.chunkSize)
        let header = try ContainerHeader(parsing: first)
        if header.type == ContainerType.response.rawValue {
            // The device skipped the data phase, usually to report an error.
            earlyResponse = try session.finishResponse(header, first, transactionID: transactionID)
            length = 0; remaining = 0; ended = true; pending = nil
            return
        }
        guard header.type == ContainerType.data.rawValue, header.code == code, header.transactionID == transactionID else {
            throw MTPError.protocolViolation("expected data")
        }
        let body = first.dropFirst(ContainerHeader.size)
        if header.length == ContainerHeader.unknownLength {
            length = nil; remaining = nil
            ended = first.count < session.chunkSize
        } else {
            let total = UInt64(header.length) - UInt64(ContainerHeader.size)
            guard UInt64(body.count) <= total else { throw MTPError.protocolViolation("data longer than declared") }
            length = total; remaining = total - UInt64(body.count)
            ended = remaining == 0
            if !ended && first.count < session.chunkSize { throw MTPError.protocolViolation("data ended early") }
        }
        pending = Data(body)
    }

    /// Next chunk of the payload, or nil when the data phase is complete.
    public func next() throws -> Data? {
        if let chunk = pending {
            pending = nil
            if !chunk.isEmpty { return chunk }
        }
        guard !ended else { return nil }
        let packet = session.transport.maxPacketSize
        var request = session.chunkSize
        if let left = remaining {
            // Ask only for what is left (rounded to whole packets) so the response is never read as data.
            let rounded = (left + UInt64(packet) - 1) / UInt64(packet) * UInt64(packet)
            request = Int(min(UInt64(request), rounded))
        }
        let chunk = try session.transport.read(maximum: request)
        if let left = remaining {
            guard UInt64(chunk.count) <= left else { throw MTPError.protocolViolation("data longer than declared") }
            remaining = left - UInt64(chunk.count)
            if remaining == 0 { ended = true }
            else if chunk.count < request { throw MTPError.protocolViolation("data ended early") }
        } else if chunk.count < request {
            ended = true
        }
        return chunk.isEmpty ? (ended ? nil : try next()) : chunk
    }

    /// Reads the response once the payload has been consumed.
    public func finish() throws -> [UInt32] {
        if let response = earlyResponse { return response }
        guard ended, pending == nil || pending?.isEmpty == true else { throw MTPError.protocolViolation("unread data") }
        return try session.response(transactionID: transactionID)
    }
}

/// Host-to-device data phase. Buffers so every transfer except the last is a whole number of packets.
public final class MTPOutbound {
    private let session: MTPSession
    private let transactionID: UInt32
    private let length: UInt64
    private var buffer: Data
    private var sent: UInt64 = 0
    private var total: UInt64 = 0

    init(session: MTPSession, code: UInt16, transactionID: UInt32, length: UInt64, unknownLength: Bool = false) {
        self.session = session; self.transactionID = transactionID; self.length = length
        let containerLength = length + UInt64(ContainerHeader.size)
        // With an unknown length the device reads until a short or zero-length packet, which finish() guarantees.
        let declared = unknownLength || containerLength >= UInt64(ContainerHeader.unknownLength)
            ? ContainerHeader.unknownLength : UInt32(containerLength)
        buffer = ContainerHeader(length: declared, type: .data, code: code, transactionID: transactionID).encoded
    }

    public func write(_ chunk: Data) throws {
        guard sent + UInt64(chunk.count) <= length else { throw MTPError.protocolViolation("more data than declared") }
        sent += UInt64(chunk.count)
        buffer.append(chunk)
        let whole = buffer.count / session.chunkSize * session.chunkSize
        if whole > 0 {
            try flush(buffer.prefix(whole))
            buffer = Data(buffer.dropFirst(whole))
        }
    }

    /// Sends the final short transfer (or a zero-length packet) and returns the response parameters.
    public func finish() throws -> [UInt32] {
        guard sent == length else { throw MTPError.protocolViolation("less data than declared") }
        if !buffer.isEmpty { try flush(buffer); buffer = Data() }
        if total % UInt64(session.transport.maxPacketSize) == 0 { try session.transport.write(Data()) }
        return try session.response(transactionID: transactionID)
    }

    private func flush(_ data: Data) throws {
        try session.transport.write(Data(data))
        total += UInt64(data.count)
    }
}
