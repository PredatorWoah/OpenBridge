import Foundation
import CryptoKit

/// OBP1 pairing math, identical to the Android side (`org.openbridge.protocol.Pairing`).
enum Pairing {
    static func commitment(fingerprint: Data, phoneNonce: Data) -> Data {
        hash("OpenBridge pairing commit", fingerprint, phoneNonce)
    }

    /// Six decimal digits from the certificate fingerprint and both nonces.
    static func code(fingerprint: Data, macNonce: Data, phoneNonce: Data) -> String {
        let digest = hash("OpenBridge pairing code", fingerprint, macNonce, phoneNonce)
        let value = digest.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06u", value % 1_000_000)
    }

    /// OBP2 sends the computer's name in exactly 128 bytes: UTF-8 cut at a character boundary, padded with zeros.
    static func nameBlock(_ name: String) -> Data {
        var block = Data()
        for character in name {
            let bytes = Data(String(character).utf8)
            if bytes.contains(0) || block.count + bytes.count > 128 { break }
            block.append(bytes)
        }
        return block + Data(count: 128 - block.count)
    }

    private static func hash(_ label: String, _ parts: Data...) -> Data {
        var hasher = SHA256()
        hasher.update(data: Data(label.utf8))
        parts.forEach { hasher.update(data: $0) }
        return Data(hasher.finalize())
    }
}

extension BridgeClient {
    /// Pairs with the phone at a private IPv4 address. `show` receives the 6-digit code to display while the
    /// phone asks its user to confirm that both screens match.
    public static func pair(host: String, port: String, show: @MainActor (String) -> Void) async throws -> BridgeClient {
        guard Wire.privateIPv4(host), let number = UInt16(port), number > 0 else {
            throw BridgeError.message("Enter the phone's private Wi-Fi IPv4 address and port (1–65535).")
        }
        return try await pair(address: host, port: number, show: show)
    }

    /// This Mac's name, shown on the phone when pairing and in its list of trusted computers.
    public static var computerName: String { Host.current().localizedName ?? "Mac" }

    /// Pairs, sending this Mac's name (OBP2). A phone older than 0.11.0 answers that with "Pairing rejected" at once;
    /// then pairing starts over without the name (OBP1).
    static func pair(address: String, port: UInt16, named: Bool = true, show: @MainActor (String) -> Void) async throws -> BridgeClient {
        guard named else { return try await exchange(address: address, port: port, name: nil, show: show) }
        do { return try await exchange(address: address, port: port, name: computerName, show: show) }
        catch BridgeError.forgotten { return try await exchange(address: address, port: port, name: nil, show: show) }
    }

    private static func exchange(address: String, port: UInt16, name: String?, show: @MainActor (String) -> Void) async throws -> BridgeClient {
        // No pin yet: the handshake records the certificate, and the commitment below ties it to the phone.
        let channel = TLSChannel(host: address, port: port, fingerprint: nil)
        defer { channel.close() }
        try await channel.connect()
        guard let fingerprint = channel.peerFingerprint else { throw BridgeError.message("The phone sent no certificate.") }
        try await channel.send(name.map { Data("OBP2".utf8) + Pairing.nameBlock($0) } ?? Data("OBP1".utf8))
        try await channel.status()
        let commitment = try await channel.read(32)
        var generator = SystemRandomNumberGenerator()
        let macNonce = Data((0..<32).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
        try await channel.send(macNonce)
        let phoneNonce = try await channel.read(32)
        guard Pairing.commitment(fingerprint: fingerprint, phoneNonce: phoneNonce) == commitment else {
            throw BridgeError.message("Pairing failed: the connection was altered on the way. Use a trusted network and try again.")
        }
        await show(Pairing.code(fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce))
        // The phone waits up to a minute for its user to tap Pair or Cancel.
        try await channel.status(timeout: 75)
        let secret = try await channel.read(32)
        return BridgeClient(testHost: address, port: port, fingerprint: fingerprint, secret: secret)
    }
}
