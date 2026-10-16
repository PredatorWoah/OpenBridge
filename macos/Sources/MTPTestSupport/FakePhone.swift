import Foundation
@testable import MTPCore

/// In-memory MTP responder that enforces USB bulk rules: a transfer that is not a whole number of
/// packets ends a data phase, and a data phase that is a whole number of packets ends with a
/// zero-length packet. Host mistakes are recorded in `violations`.
public final class FakePhone: MTPTransport {
    public struct Object {
        public var storage: UInt32, parent: UInt32, name: String, folder: Bool, data: Data, bigSize: UInt64?
        public init(storage: UInt32, parent: UInt32, name: String, folder: Bool, data: Data, bigSize: UInt64?) {
            self.storage = storage; self.parent = parent; self.name = name; self.folder = folder; self.data = data; self.bigSize = bigSize
        }
    }

    public let maxPacketSize: Int
    public var objects: [UInt32: Object] = [:]
    public var violations: [String] = []
    public var sessionOpen = false
    public var closed = false
    public var failNextWith: UInt16?
    public var freeSpace: UInt64 = 64 << 30
    /// Reported in device info; a different value simulates a different phone.
    public var serialNumber = "SERIAL"
    /// Simulates a USB error when the host sends this command.
    public var failWritingCommand: UInt16?
    /// Simulates a USB error after this many more successful writes.
    public var breakAfterWrites: Int?
    /// Size field of the last object info received, to check how large uploads are declared.
    public private(set) var lastDeclaredSize: UInt32?
    private var unknownLengthContainer: (header: ContainerHeader, body: Data)?
    /// Simulates a USB error (cable glitch) after this many more successful reads.
    public var breakAfterReads: Int?
    public private(set) var reads = 0
    private var nextHandle: UInt32 = 100
    private var outbound: [Data] = []           // queued device transfers (empty = zero-length packet)
    private var incoming = Data()              // host bulk-out bytes of the current container
    private var awaitingZLP = false
    private var pendingSend: (handle: UInt32, transaction: UInt32)?

    public init(maxPacketSize: Int = 512) {
        self.maxPacketSize = maxPacketSize
        objects[1] = Object(storage: 0x10001, parent: MTPSession.root, name: "DCIM", folder: true, data: Data(), bigSize: nil)
        objects[2] = Object(storage: 0x10001, parent: 1, name: "photo.jpg", folder: false, data: Data((0..<3000).map { UInt8($0 % 251) }), bigSize: nil)
        objects[3] = Object(storage: 0x10001, parent: MTPSession.root, name: "Download", folder: true, data: Data(), bigSize: nil)
        objects[4] = Object(storage: 0x10001, parent: MTPSession.root, name: "huge.mkv", folder: false, data: Data(), bigSize: 5 << 30)
    }

    public var aborted = false
    /// Runs when the host resets the phone, to simulate changes before the host reconnects.
    public var onAbort: (() -> Void)?
    public func abort() { onAbort?(); aborted = true; outbound.removeAll(); incoming = Data(); awaitingZLP = false; sessionOpen = false }
    public func close() { closed = true }

    // MARK: Host to device

    public func write(_ data: Data) throws {
        if let left = breakAfterWrites {
            if left == 0 { breakAfterWrites = nil; throw MTPError.transport("simulated USB failure") }
            breakAfterWrites = left - 1
        }
        if let code = failWritingCommand, incoming.isEmpty, unknownLengthContainer == nil, !awaitingZLP, data.count >= ContainerHeader.size,
           let header = try? ContainerHeader(parsing: data), header.type == ContainerType.command.rawValue, header.code == code {
            failWritingCommand = nil
            throw MTPError.transport("simulated USB failure")
        }
        if awaitingZLP {
            if !data.isEmpty { violations.append("missing zero-length packet") }
            awaitingZLP = false
            if data.isEmpty { return }
        }
        if var open = unknownLengthContainer {
            // An unknown-length data phase ends with the first short or zero-length transfer.
            open.body.append(data)
            if data.isEmpty || data.count % maxPacketSize != 0 { unknownLengthContainer = nil; try handle(open.header, open.body) }
            else { unknownLengthContainer = open }
            return
        }
        incoming.append(data)
        while incoming.count >= ContainerHeader.size {
            let header = try ContainerHeader(parsing: incoming)
            if header.length == ContainerHeader.unknownLength {
                guard header.type == ContainerType.data.rawValue else { violations.append("unknown length on a non-data container"); return }
                let body = Data(incoming.dropFirst(ContainerHeader.size))
                incoming = Data()
                if data.count % maxPacketSize != 0 { try handle(header, body) } else { unknownLengthContainer = (header, body) }
                return
            }
            let length = Int(header.length)
            if incoming.count < length {
                if data.count % maxPacketSize != 0 { violations.append("short packet inside a data phase") }
                return
            }
            let container = incoming.prefix(length)
            incoming = Data(incoming.dropFirst(length))
            if header.type == ContainerType.data.rawValue && length % maxPacketSize == 0 { awaitingZLP = true }
            try handle(header, Data(container.dropFirst(ContainerHeader.size)))
        }
    }

    // MARK: Device to host

    public func read(maximum: Int) throws -> Data {
        if let left = breakAfterReads {
            if left == 0 { breakAfterReads = nil; throw MTPError.transport("simulated USB failure") }
            breakAfterReads = left - 1
        }
        reads += 1
        guard !outbound.isEmpty else { throw MTPError.transport("read with nothing queued") }
        if maximum % maxPacketSize != 0 { violations.append("read size is not a whole number of packets") }
        let transfer = outbound.removeFirst()
        if transfer.count > maximum {
            outbound.insert(Data(transfer.dropFirst(maximum)), at: 0)
            return Data(transfer.prefix(maximum))
        }
        return transfer
    }

    private func send(_ type: ContainerType, _ code: UInt16, _ transaction: UInt32, _ payload: Data) {
        var data = ContainerHeader(length: UInt32(ContainerHeader.size + payload.count), type: type, code: code, transactionID: transaction).encoded
        data.append(payload)
        outbound.append(data)
        if type == .data && data.count % maxPacketSize == 0 { outbound.append(Data()) }
    }

    private func respond(_ code: UInt16, _ transaction: UInt32, _ parameters: [UInt32] = []) {
        var writer = MTPWriter(); parameters.forEach { writer.u32($0) }
        send(.response, code, transaction, writer.data)
    }

    private func dataThenOK(_ code: UInt16, _ transaction: UInt32, _ payload: Data) {
        send(.data, code, transaction, payload)
        respond(MTPCode.ok, transaction)
    }

    private var pendingInfo: (storage: UInt32, parent: UInt32, transaction: UInt32)?

    private func handle(_ header: ContainerHeader, _ body: Data) throws {
        var reader = MTPReader(body)
        let t = header.transactionID
        if header.type == ContainerType.data.rawValue {
            if header.code == MTPCode.sendObjectInfo, let info = pendingInfo {
                let parsed = try MTPObjectInfo(parsing: body)
                lastDeclaredSize = parsed.compressedSize
                let handle = nextHandle; nextHandle += 1
                objects[handle] = Object(storage: info.storage, parent: info.parent, name: parsed.filename, folder: false, data: Data(), bigSize: nil)
                pendingSend = (handle, 0); pendingInfo = nil
                respond(MTPCode.ok, info.transaction, [info.storage, info.parent == MTPSession.root ? 0 : info.parent, handle])
            } else if header.code == MTPCode.sendObject, let target = pendingSend {
                objects[target.handle]?.data = body
                pendingSend = nil
                respond(MTPCode.ok, t)
            } else { violations.append("unexpected data container") }
            return
        }
        if let failure = failNextWith { failNextWith = nil; respond(failure, t); return }
        switch header.code {
        case MTPCode.getDeviceInfo:
            var w = MTPWriter()
            w.u16(100); w.u32(6); w.u16(100); try w.string("microsoft.com: 1.0; android.com: 1.0;"); w.u16(0)
            for _ in 0..<5 { w.u32(0) }
            try w.string("Google"); try w.string("Pixel 8"); try w.string("1.0"); try w.string(serialNumber)
            dataThenOK(header.code, t, w.data)
        case MTPCode.openSession:
            if sessionOpen { respond(MTPCode.sessionAlreadyOpen, t) } else { sessionOpen = true; respond(MTPCode.ok, t) }
        case MTPCode.closeSession:
            sessionOpen = false; respond(MTPCode.ok, t)
        case MTPCode.getStorageIDs:
            var w = MTPWriter(); w.u32(1); w.u32(0x10001)
            dataThenOK(header.code, t, w.data)
        case MTPCode.getStorageInfo:
            var w = MTPWriter()
            w.u16(3); w.u16(2); w.u16(0); w.u64(128 << 30); w.u64(freeSpace); w.u32(0xFFFF_FFFF)
            try w.string("Internal shared storage"); try w.string("")
            dataThenOK(header.code, t, w.data)
        case MTPCode.getObjectHandles:
            let storage = try reader.u32(); _ = try reader.u32(); let parent = try reader.u32()
            let handles = objects.filter { $0.value.storage == storage && $0.value.parent == parent }.keys.sorted()
            var w = MTPWriter(); w.u32(UInt32(handles.count)); handles.forEach { w.u32($0) }
            dataThenOK(header.code, t, w.data)
        case MTPCode.getObjectInfo:
            let handle = try reader.u32()
            guard let object = objects[handle] else { respond(0x2009, t); return }
            let size: UInt32 = object.bigSize != nil ? 0xFFFF_FFFF : UInt32(object.data.count)
            let info = MTPObjectInfo(storageID: object.storage, format: object.folder ? MTPCode.associationFormat : MTPCode.undefinedFormat,
                                     compressedSize: size, parent: object.parent, associationType: object.folder ? MTPCode.genericFolder : 0, filename: object.name)
            dataThenOK(header.code, t, try info.encoded())
        case MTPCode.getObjectPropValue:
            let handle = try reader.u32()
            var w = MTPWriter(); w.u64(objects[handle]?.bigSize ?? UInt64(objects[handle]?.data.count ?? 0))
            dataThenOK(header.code, t, w.data)
        case MTPCode.getObject:
            let handle = try reader.u32()
            guard let object = objects[handle], !object.folder else { respond(0x2009, t); return }
            dataThenOK(header.code, t, object.data)
        case MTPCode.sendObjectInfo:
            pendingInfo = (try reader.u32(), try reader.u32(), t)
        case MTPCode.deleteObject:
            let handle = try reader.u32()
            if objects.removeValue(forKey: handle) == nil { respond(0x2009, t) } else { respond(MTPCode.ok, t) }
        case MTPCode.sendObject:
            if pendingSend == nil { respond(0x2014, t) }   // no valid object info
        default:
            respond(0x2005, t)                             // operation not supported
        }
    }
}
