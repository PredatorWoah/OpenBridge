import Foundation
import Darwin

/// Fast USB: a listener on this Mac's loopback address whose connections are carried to a port on the phone through
/// USB debugging, as `adb forward` does. Each connection opens its own ADB `tcp:` stream and bytes flow both ways until
/// either side closes, so OB01 runs unchanged on top: TLS end to end, the pin checked before the secret is sent.
/// Only processes on this Mac can reach the listener.
public final class ADBTunnel: @unchecked Sendable {
    public let localPort: UInt16
    public let remotePort: UInt16
    private let connect: (String) throws -> ADBStream
    private let listener: Int32
    private let lock = NSLock()
    private var stopped = false
    private var links: [ObjectIdentifier: Link] = [:]

    /// `connect` opens a service on the phone ("tcp:41987"); it runs on a background thread for each connection.
    public init(remotePort: UInt16, connect: @escaping (String) throws -> ADBStream) throws {
        self.remotePort = remotePort
        self.connect = connect
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw BridgeError.message("Couldn't open a local connection for Fast USB.") }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0                                          // any free port
        address.sin_addr = in_addr(s_addr: UInt32(0x7F00_0001).bigEndian)   // 127.0.0.1 only
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        var actual = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &actual) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socket, $0, &length) }
        }
        guard bound == 0, listen(socket, 16) == 0, named == 0 else {
            close(socket)
            throw BridgeError.message("Couldn't open a local connection for Fast USB.")
        }
        listener = socket
        localPort = UInt16(bigEndian: actual.sin_port)
        Thread { [self] in acceptLoop() }.start()
    }

    /// Closes the listener and every connection through it. Until then the listener keeps running.
    public func stop() {
        lock.lock()
        stopped = true
        let all = Array(links.values)
        links.removeAll()
        lock.unlock()
        all.forEach { $0.cut() }
    }

    private var isStopped: Bool { lock.lock(); defer { lock.unlock() }; return stopped }

    private func acceptLoop() {
        // Polls, because closing a socket doesn't reliably wake a thread blocked in accept().
        while !isStopped {
            var descriptor = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
            guard poll(&descriptor, 1, 400) > 0, !isStopped else { continue }
            let client = accept(listener, nil, nil)
            guard client >= 0 else { continue }
            var on: Int32 = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
            setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &on, socklen_t(MemoryLayout<Int32>.size))
            Thread { [self] in carry(client) }.start()
        }
        close(listener)
    }

    /// One connection: phone to Mac on a second thread, Mac to phone on this one.
    private func carry(_ socket: Int32) {
        let stream: ADBStream
        do { stream = try connect("tcp:\(remotePort)") } catch { close(socket); return }
        let link = Link(socket: socket, stream: stream)
        lock.lock()
        let accepted = !stopped
        if accepted { links[ObjectIdentifier(link)] = link }
        lock.unlock()
        guard accepted else { stream.close(); close(socket); return }

        Thread {
            while let chunk = stream.readAvailable(), ADBTunnel.sendAll(socket, chunk) {}
            shutdown(socket, SHUT_RDWR)   // wakes the other direction
            link.release()
        }.start()
        var buffer = [UInt8](repeating: 0, count: 256 << 10)
        while true {
            let count = buffer.withUnsafeMutableBytes { recv(socket, $0.baseAddress, $0.count, 0) }
            if count < 0 && errno == EINTR { continue }
            guard count > 0 else { break }
            do { try stream.write(Data(buffer[0..<count])) } catch { break }
        }
        stream.close()                    // wakes the other direction
        lock.lock(); links[ObjectIdentifier(link)] = nil; lock.unlock()
        link.release()
    }

    private static func sendAll(_ socket: Int32, _ data: Data) -> Bool {
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
            guard let base = raw.baseAddress else { return true }
            var offset = 0
            while offset < raw.count {
                let sent = send(socket, base + offset, raw.count - offset, 0)
                if sent < 0 && errno == EINTR { continue }
                guard sent > 0 else { return false }
                offset += sent
            }
            return true
        }
    }

    /// A socket and its stream. The socket is closed once both directions are done with it, never earlier, so its
    /// number can't be reused by another connection while one direction still writes to it.
    private final class Link: @unchecked Sendable {
        let socket: Int32
        let stream: ADBStream
        private let lock = NSLock()
        private var users = 2
        init(socket: Int32, stream: ADBStream) { self.socket = socket; self.stream = stream }

        func release() {
            lock.lock(); users -= 1; let last = users == 0; lock.unlock()
            if last { close(socket) }
        }

        /// Ends both directions; they release the socket as they finish.
        func cut() {
            shutdown(socket, SHUT_RDWR)
            stream.close()
        }
    }
}
