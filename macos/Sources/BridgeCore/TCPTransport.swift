import Foundation
import Darwin
import MTPCore

/// ADB over Wi‑Fi (the phone's port 5555): a plain TCP stream behind the same interface as the USB pipes, so the
/// ADB client and mirroring run unchanged. Private IPv4 addresses only.
public final class TCPTransport: MTPTransport {
    /// TCP has no packets: a size nothing reaches, so ADB never sends a zero-length packet.
    public let maxPacketSize = Int(Int32.max)
    private let lock = NSLock()
    private var socket: Int32

    public init(host: String, port: UInt16, timeout: TimeInterval = 4) throws {
        guard Wire.privateIPv4(host) else { throw BridgeError.message("The phone's Wi‑Fi address isn't a local one.") }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &address.sin_addr) == 1 else { throw BridgeError.message("Invalid phone address.") }
        socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw BridgeError.message("Couldn't open a network connection.") }
        // Connect without blocking, so an unreachable phone fails after `timeout` instead of a minute.
        let flags = fcntl(socket, F_GETFL)
        _ = fcntl(socket, F_SETFL, flags | O_NONBLOCK)
        let started = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        var problem: Int32 = started == 0 ? 0 : errno
        if problem == EINPROGRESS {
            var descriptor = pollfd(fd: socket, events: Int16(POLLOUT), revents: 0)
            if poll(&descriptor, 1, Int32(timeout * 1000)) == 1 {
                var length = socklen_t(MemoryLayout<Int32>.size)
                getsockopt(socket, SOL_SOCKET, SO_ERROR, &problem, &length)
            } else { problem = ETIMEDOUT }
        }
        guard problem == 0 else {
            Darwin.close(socket); socket = -1
            throw BridgeError.message("Couldn't reach the phone over Wi‑Fi. Check that both are on the same network.")
        }
        _ = fcntl(socket, F_SETFL, flags)
        var on: Int32 = 1
        setsockopt(socket, IPPROTO_TCP, TCP_NODELAY, &on, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
    }

    deinit { close() }

    public func write(_ data: Data) throws {
        let descriptor = current()
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var offset = 0
            while offset < raw.count {
                let sent = Darwin.send(descriptor, raw.baseAddress! + offset, raw.count - offset, 0)
                if sent <= 0 { throw MTPError.transport("The Wi‑Fi connection to the phone dropped.") }
                offset += sent
            }
        }
    }

    public func read(maximum: Int) throws -> Data {
        var buffer = Data(count: maximum)
        let descriptor = current()
        let count = buffer.withUnsafeMutableBytes { Darwin.recv(descriptor, $0.baseAddress, maximum, 0) }
        guard count > 0 else { throw MTPError.transport("The Wi‑Fi connection to the phone dropped.") }
        return buffer.prefix(count)
    }

    public func abort() {}

    /// Wakes a read blocked on another thread, then closes.
    public func close() {
        lock.lock(); defer { lock.unlock() }
        guard socket >= 0 else { return }
        shutdown(socket, SHUT_RDWR)
        Darwin.close(socket)
        socket = -1
    }

    private func current() -> Int32 { lock.lock(); defer { lock.unlock() }; return socket }
}

/// Switching a cable-connected phone to wireless debugging, and back.
public enum ADBWireless {
    public static let port: UInt16 = 5555

    /// The phone's Wi‑Fi address, asked over the cable.
    public static func wifiAddress(_ adb: ADBConnection) throws -> String {
        let shell = try adb.open("shell:ip -o -f inet addr show")
        var output = ""
        while let chunk = shell.readAvailable() { output += String(decoding: chunk, as: UTF8.self) }
        shell.close()
        // Lines like "30: wlan0    inet 192.168.1.23/24 brd 192.168.1.255 scope global wlan0".
        for line in output.split(separator: "\n") where line.contains("wlan") {
            if let range = line.range(of: #"inet (\d{1,3}\.){3}\d{1,3}"#, options: .regularExpression) {
                let address = String(line[range].dropFirst(5))
                if Wire.privateIPv4(address) { return address }
            }
        }
        throw BridgeError.message("Connect the phone to Wi‑Fi first, on the same network as this Mac.")
    }

    /// Restarts the phone's debugging on port 5555. The cable connection ends; reconnect over TCP.
    public static func listen(_ adb: ADBConnection) {
        if let reply = try? adb.open("tcpip:\(port)", timeout: 5) { _ = reply.readAvailable(); reply.close() }
        adb.close()
    }

    /// Back to cable-only debugging, so the phone doesn't stay open on the network.
    public static func stopListening(_ adb: ADBConnection) {
        if let reply = try? adb.open("usb:", timeout: 3) { _ = reply.readAvailable(); reply.close() }
        adb.close()
    }
}
