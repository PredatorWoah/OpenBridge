import Foundation
import Security
import MTPCore

public enum ADBError: LocalizedError {
    /// USB debugging is on, but the phone hasn't allowed this Mac yet (and wasn't asked to).
    case notAllowed
    public var errorDescription: String? {
        switch self {
        case .notAllowed: return "The phone hasn't allowed USB debugging from this Mac yet."
        }
    }
}

/// One ADB packet: a 24-byte little-endian header and an optional payload.
struct ADBMessage: Equatable {
    static let connect: UInt32 = 0x4e58_4e43    // CNXN
    static let auth: UInt32 = 0x4854_5541       // AUTH
    static let open: UInt32 = 0x4e45_504f       // OPEN
    static let okay: UInt32 = 0x5941_4b4f       // OKAY
    static let close: UInt32 = 0x4553_4c43      // CLSE
    static let write: UInt32 = 0x4554_5257      // WRTE
    static let version: UInt32 = 0x0100_0001
    static let maxPayload: UInt32 = 1 << 20

    var command: UInt32
    var arg0: UInt32
    var arg1: UInt32
    var payload = Data()

    /// The header's data check is the plain sum of the payload bytes (not a CRC).
    var header: Data {
        var data = Data()
        for value in [command, arg0, arg1, UInt32(payload.count), payload.reduce(UInt32(0)) { $0 &+ UInt32($1) }, command ^ 0xFFFF_FFFF] {
            withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
        }
        return data
    }

    /// Parses a header; the payload follows separately.
    static func parse(header: Data) throws -> (ADBMessage, length: Int) {
        guard header.count >= 24 else { throw BridgeError.message("Short ADB header.") }
        let bytes = [UInt8](header.prefix(24))
        func word(_ index: Int) -> UInt32 {
            (0..<4).reduce(UInt32(0)) { $0 | UInt32(bytes[index * 4 + $1]) << (8 * UInt32($1)) }
        }
        let command = word(0)
        guard word(5) == command ^ 0xFFFF_FFFF else { throw BridgeError.message("The phone sent a damaged USB debugging packet. Unplug it and try again.") }
        guard word(3) <= maxPayload else { throw BridgeError.message("The phone sent an oversized USB debugging packet.") }
        return (ADBMessage(command: command, arg0: word(1), arg1: word(2)), Int(word(3)))
    }
}

/// This Mac's USB debugging key: a 2048-bit RSA key stored in Application Support. The phone remembers it
/// after the user taps Allow once.
public final class ADBKey {
    private let privateKey: SecKey
    private let publicKey: SecKey

    public static func loadOrCreate() throws -> ADBKey {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenBridge", isDirectory: true)
        let file = folder.appendingPathComponent("adbkey.der")
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPrivate]
        if let data = try? Data(contentsOf: file), let key = SecKeyCreateWithData(data as CFData, attributes as CFDictionary, nil) {
            return try ADBKey(key)
        }
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048] as CFDictionary, &error),
              let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw BridgeError.message("Couldn't create a USB debugging key on this Mac.")
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: file.path, contents: data, attributes: [.posixPermissions: 0o600])
        return try ADBKey(key)
    }

    init(_ key: SecKey) throws {
        guard let publicKey = SecKeyCopyPublicKey(key) else { throw BridgeError.message("Invalid USB debugging key.") }
        privateKey = key; self.publicKey = publicKey
    }

    /// RSA PKCS#1 v1.5 with the SHA-1 DigestInfo applied to the raw 20-byte token, as adbd expects.
    func sign(_ token: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(privateKey, .rsaSignatureDigestPKCS1v15SHA1, token as CFData, &error) else {
            throw BridgeError.message("Couldn't sign the phone's USB debugging request.")
        }
        return signature as Data
    }

    /// Android's public key format: base64 of its RSA struct, then " name", then a NUL.
    func androidPublicKey(name: String) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let der = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw BridgeError.message("Couldn't read the USB debugging key.")
        }
        let (modulus, exponent) = try ADBKey.parseRSAPublicKey(der)
        return Data((try ADBKey.androidStruct(modulus: modulus, exponent: exponent).base64EncodedString() + " " + name).utf8) + Data([0])
    }

    /// PKCS#1 RSAPublicKey: SEQUENCE { INTEGER n, INTEGER e }.
    static func parseRSAPublicKey(_ der: Data) throws -> (modulus: [UInt8], exponent: UInt32) {
        let bytes = [UInt8](der)
        var index = 0
        func length() throws -> Int {
            guard index < bytes.count else { throw BridgeError.message("Invalid key.") }
            let first = Int(bytes[index]); index += 1
            if first < 0x80 { return first }
            var value = 0
            for _ in 0..<(first & 0x7F) { guard index < bytes.count else { throw BridgeError.message("Invalid key.") }; value = value << 8 | Int(bytes[index]); index += 1 }
            return value
        }
        func integer() throws -> [UInt8] {
            guard index < bytes.count, bytes[index] == 0x02 else { throw BridgeError.message("Invalid key.") }
            index += 1
            let count = try length()
            guard index + count <= bytes.count else { throw BridgeError.message("Invalid key.") }
            var value = Array(bytes[index..<index + count]); index += count
            while value.count > 1 && value[0] == 0 { value.removeFirst() }
            return value
        }
        guard bytes.first == 0x30 else { throw BridgeError.message("Invalid key.") }
        index = 1; _ = try length()
        let modulus = try integer(), exponent = try integer()
        guard exponent.count <= 4 else { throw BridgeError.message("Invalid key.") }
        return (modulus, exponent.reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
    }

    /// The 524-byte little-endian struct: word count, n0inv = -1/n mod 2^32, n, rr = 2^4096 mod n, e.
    static func androidStruct(modulus: [UInt8], exponent: UInt32) throws -> Data {
        guard modulus.count == 256 else { throw BridgeError.message("USB debugging keys must be 2048-bit.") }
        // Little-endian 32-bit words.
        var n = [UInt32](repeating: 0, count: 64)
        for (i, byte) in modulus.reversed().enumerated() { n[i / 4] |= UInt32(byte) << (8 * UInt32(i % 4)) }
        var inverse = n[0]
        for _ in 0..<5 { inverse = inverse &* (2 &- n[0] &* inverse) }   // Newton: n[0] * inverse ≡ 1 mod 2^32
        let rr = montgomeryRR(n)
        var data = Data()
        func put(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        put(64); put(0 &- inverse)
        n.forEach(put); rr.forEach(put)
        put(exponent)
        return data
    }

    /// 2^(32·2·words) mod n, by doubling: never needs more than one subtraction per step.
    static func montgomeryRR(_ n: [UInt32]) -> [UInt32] {
        let words = n.count
        var r = [UInt32](repeating: 0, count: words + 1)
        r[0] = 1
        let modulus = n + [0]
        for _ in 0..<(64 * words) {
            var carry: UInt32 = 0
            for i in 0...words { let next = r[i] >> 31; r[i] = r[i] << 1 | carry; carry = next }
            if !lessThan(r, modulus) {
                var borrow: UInt64 = 0
                for i in 0...words {
                    let difference = UInt64(r[i]) &- UInt64(modulus[i]) &- borrow
                    r[i] = UInt32(truncatingIfNeeded: difference)
                    borrow = (difference >> 63) & 1
                }
            }
        }
        return Array(r.prefix(words))
    }

    private static func lessThan(_ a: [UInt32], _ b: [UInt32]) -> Bool {
        for i in stride(from: a.count - 1, through: 0, by: -1) where a[i] != b[i] { return a[i] < b[i] }
        return false
    }
}

/// A USB debugging connection to the phone, with numbered streams over one USB interface.
/// Blocking API, like MTPSession: call it from a background thread.
public final class ADBConnection: @unchecked Sendable {
    private let transport: MTPTransport
    private var buffer = Data()
    private let writeLock = NSLock()
    private let state = NSCondition()
    private var streams: [UInt32: ADBStream] = [:]
    private var nextLocal: UInt32 = 1
    private var closed = false
    private(set) var maxPayload = Int(ADBMessage.maxPayload)
    /// The phone's description from its CNXN, like "device::ro.product.model=V2514;…".
    public private(set) var banner = ""

    init(transport: MTPTransport) { self.transport = transport }

    /// Connects and authenticates. `allowPrompt` runs when the phone shows "Allow USB debugging?". With `offerKey` false,
    /// a phone that doesn't know this Mac yet fails with `ADBError.notAllowed` instead of asking its user. `ready` runs
    /// after authentication, before anything else is read (to lift a handshake timeout, for example).
    public static func connect(transport: MTPTransport, key: ADBKey, name: String, offerKey: Bool = true,
                               ready: (() -> Void)? = nil, allowPrompt: @escaping () -> Void) throws -> ADBConnection {
        let connection = ADBConnection(transport: transport)
        try connection.handshake(key: key, name: name, offerKey: offerKey, allowPrompt: allowPrompt)
        ready?()
        Thread { connection.readLoop() }.start()
        return connection
    }

    /// Whether the connection has ended (unplugged, or the phone restarted USB debugging).
    public var isClosed: Bool { state.lock(); defer { state.unlock() }; return closed }

    /// The phone's model from its banner ("ro.product.model=V2514;"), like what `adb devices -l` shows.
    public var model: String? {
        guard let range = banner.range(of: "ro.product.model=") else { return nil }
        let value = banner[range.upperBound...].prefix { $0 != ";" }
        return value.isEmpty ? nil : String(value)
    }

    private func handshake(key: ADBKey, name: String, offerKey: Bool, allowPrompt: () -> Void) throws {
        try send(ADBMessage(command: ADBMessage.connect, arg0: ADBMessage.version, arg1: ADBMessage.maxPayload, payload: Data("host::\0".utf8)))
        var signed = false, offered = false
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            let message = try receive()
            switch message.command {
            case ADBMessage.connect:
                maxPayload = Int(min(message.arg1, ADBMessage.maxPayload))
                banner = String(decoding: message.payload, as: UTF8.self)
                return
            case ADBMessage.auth where message.arg0 == 1:   // TOKEN
                if !signed {
                    signed = true
                    try send(ADBMessage(command: ADBMessage.auth, arg0: 2, arg1: 0, payload: try key.sign(message.payload)))
                } else if !offered {
                    // The phone doesn't know this Mac yet: offer the key, and the phone asks its user.
                    guard offerKey else { throw ADBError.notAllowed }
                    offered = true
                    try send(ADBMessage(command: ADBMessage.auth, arg0: 3, arg1: 0, payload: try key.androidPublicKey(name: name)))
                    allowPrompt()
                }
            default:
                continue
            }
        }
        throw BridgeError.message("The phone didn't allow USB debugging. Unlock it, tap Allow, and try again.")
    }

    /// Opens a service such as "shell:…", "sync:" or "localabstract:…". Fails if the phone refuses it.
    public func open(_ service: String, timeout: TimeInterval = 10) throws -> ADBStream {
        state.lock()
        let local = nextLocal; nextLocal += 1
        let stream = ADBStream(connection: self, local: local)
        streams[local] = stream
        state.unlock()
        try send(ADBMessage(command: ADBMessage.open, arg0: local, arg1: 0, payload: Data((service + "\0").utf8)))
        try stream.waitOpen(timeout: timeout)
        return stream
    }

    public func close() {
        state.lock()
        closed = true
        let all = Array(streams.values)
        streams.removeAll()
        state.unlock()
        all.forEach { $0.peerClosed() }
        transport.close()
    }

    func send(_ message: ADBMessage) throws {
        writeLock.lock(); defer { writeLock.unlock() }
        try transport.write(message.header)
        if !message.payload.isEmpty {
            try transport.write(message.payload)
            // A payload that is a whole number of USB packets needs a zero-length packet to end the transfer.
            if message.payload.count % transport.maxPacketSize == 0 { try transport.write(Data()) }
        }
    }

    private func fill(_ count: Int) throws {
        while buffer.count < count {
            let chunk = try transport.read(maximum: 64 * 1024)
            buffer.append(chunk)
        }
    }

    private func receive() throws -> ADBMessage {
        try fill(24)
        var (message, length) = try ADBMessage.parse(header: Data(buffer.prefix(24)))
        buffer.removeFirst(24)
        try fill(length)
        message.payload = Data(buffer.prefix(length))
        buffer.removeFirst(length)
        return message
    }

    private func readLoop() {
        while true {
            let message: ADBMessage
            do { message = try receive() } catch { break }
            state.lock()
            let stream = streams[message.arg1]
            state.unlock()
            switch message.command {
            case ADBMessage.okay: stream?.okay(remote: message.arg0)
            case ADBMessage.write:
                // The phone sends a stream's next data only after this OKAY; a stream with a backlog sends it later.
                if stream?.received(message.payload) ?? true {
                    try? send(ADBMessage(command: ADBMessage.okay, arg0: message.arg1, arg1: message.arg0))
                }
            case ADBMessage.close:
                if let stream { state.lock(); streams[stream.local] = nil; state.unlock(); stream.peerClosed() }
            default: break
            }
        }
        close()
    }

    func forget(_ stream: ADBStream) {
        state.lock(); streams[stream.local] = nil; state.unlock()
    }
}

/// One ADB stream: a byte pipe to a service on the phone.
public final class ADBStream: @unchecked Sendable {
    let local: UInt32
    private unowned let connection: ADBConnection
    private let condition = NSCondition()
    private var remote: UInt32 = 0
    private var opened = false
    private var acknowledged = true
    private var finished = false
    private var incoming = Data()

    init(connection: ADBConnection, local: UInt32) { self.connection = connection; self.local = local }

    func waitOpen(timeout: TimeInterval) throws {
        condition.lock(); defer { condition.unlock() }
        let deadline = Date().addingTimeInterval(timeout)
        while !opened && !finished { if !condition.wait(until: deadline) { break } }
        guard opened else { throw BridgeError.message("The phone refused the connection.") }
    }

    func okay(remote: UInt32) {
        condition.lock()
        if !opened { opened = true; self.remote = remote } else { acknowledged = true }
        condition.broadcast(); condition.unlock()
    }

    /// Unread data beyond which the phone's next write waits (its OKAY is held back) until the reader catches up, so a
    /// slow reader of a fast stream (a Fast USB download) never makes the Mac buffer the whole file.
    static let backlog = 4 << 20
    private var owesOkay = false

    /// Takes data from the phone; returns whether to acknowledge it now.
    func received(_ data: Data) -> Bool {
        condition.lock(); defer { condition.unlock() }
        incoming.append(data); condition.broadcast()
        guard incoming.count > ADBStream.backlog else { return true }
        owesOkay = true
        return false
    }

    /// After a read (lock held): whether a held-back OKAY can go now. The caller sends it once unlocked.
    private func caughtUp() -> UInt32? {
        guard owesOkay, incoming.count <= ADBStream.backlog, !finished else { return nil }
        owesOkay = false
        return remote
    }

    private func acknowledge(_ remote: UInt32?) {
        if let remote { try? connection.send(ADBMessage(command: ADBMessage.okay, arg0: local, arg1: remote)) }
    }

    func peerClosed() {
        condition.lock(); finished = true; condition.broadcast(); condition.unlock()
    }

    /// Writes all of `data`, one payload at a time; each waits for the phone's acknowledgement.
    public func write(_ data: Data) throws {
        var offset = 0
        while offset < data.count {
            let piece = data[data.startIndex + offset..<data.startIndex + min(data.count, offset + connection.maxPayload)]
            condition.lock()
            while !acknowledged && !finished { condition.wait() }
            if finished { condition.unlock(); throw BridgeError.message("The phone closed the connection.") }
            acknowledged = false
            let remote = self.remote
            condition.unlock()
            try connection.send(ADBMessage(command: ADBMessage.write, arg0: local, arg1: remote, payload: Data(piece)))
            offset += piece.count
        }
    }

    /// Reads exactly `count` bytes, waiting for them.
    public func read(_ count: Int) throws -> Data {
        condition.lock()
        while incoming.count < count && !finished { condition.wait() }
        guard incoming.count >= count else { condition.unlock(); throw BridgeError.message("The phone closed the connection.") }
        let result = Data(incoming.prefix(count))
        incoming.removeFirst(count)
        let okay = caughtUp()
        condition.unlock()
        acknowledge(okay)
        return result
    }

    /// Reads whatever has arrived (at least one byte), or nil once the stream has ended.
    public func readAvailable() -> Data? {
        condition.lock()
        while incoming.isEmpty && !finished { condition.wait() }
        guard !incoming.isEmpty else { condition.unlock(); return nil }
        let result = incoming
        incoming = Data()
        let okay = caughtUp()
        condition.unlock()
        acknowledge(okay)
        return result
    }

    public func close() {
        condition.lock()
        let wasOpen = opened && !finished
        finished = true
        let remote = self.remote
        condition.broadcast(); condition.unlock()
        connection.forget(self)
        if wasOpen { try? connection.send(ADBMessage(command: ADBMessage.close, arg0: local, arg1: remote)) }
    }

    /// Pushes a file with the sync protocol: SEND "path,mode", DATA chunks, DONE mtime, then OKAY or FAIL.
    static func push(_ data: Data, to path: String, mode: Int, over connection: ADBConnection) throws {
        let stream = try connection.open("sync:")
        defer { stream.close() }
        func request(_ id: String, _ value: UInt32) -> Data {
            var bytes = Data(id.utf8)
            withUnsafeBytes(of: value.littleEndian) { bytes.append(contentsOf: $0) }
            return bytes
        }
        let target = Data("\(path),\(mode)".utf8)
        try stream.write(request("SEND", UInt32(target.count)) + target)
        var offset = 0
        while offset < data.count {
            let chunk = data[offset..<min(data.count, offset + 64 * 1024)]
            try stream.write(request("DATA", UInt32(chunk.count)) + chunk)
            offset += chunk.count
        }
        try stream.write(request("DONE", UInt32(Date().timeIntervalSince1970)))
        let reply = try stream.read(8)
        let id = String(decoding: reply.prefix(4), as: UTF8.self)
        let length = reply.suffix(4).withUnsafeBytes { Int(UInt32(littleEndian: $0.loadUnaligned(as: UInt32.self))) }
        if id == "FAIL" {
            let message = String(decoding: try stream.read(min(length, 4096)), as: UTF8.self)
            throw BridgeError.message("The phone refused the mirroring helper: \(message)")
        }
        guard id == "OKAY" else { throw BridgeError.message("Unexpected reply while sending the mirroring helper.") }
        try? stream.write(request("QUIT", 0))
    }
}

extension ADBConnection {
    public func push(_ data: Data, to path: String, mode: Int = 0o644) throws {
        try ADBStream.push(data, to: path, mode: 0o100000 | mode, over: self)
    }
}
