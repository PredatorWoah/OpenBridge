import Foundation
import CryptoKit
import Security

/// OBP3 pairing math (pairing by QR code, phone app 1.3.0), identical to the Android side: the QR code's text, and
/// both proofs of holding its key `K` (see docs/protocol.md, Pairing by QR code).
public enum QRPairing {
    /// How long a code is valid, on the phone and here.
    public static let lifetime: TimeInterval = 120
    public static let keySize = 16

    /// A fresh `K`: 16 random bytes from the system's secure generator.
    public static func newKey() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: keySize)
        let result: Int32 = SecRandomCopyBytes(kSecRandomDefault, keySize, &bytes)
        guard result == errSecSuccess else { throw BridgeError.message("Couldn't make a pairing code. Try again.") }
        return Data(bytes)
    }

    /// Unpadded base64url, as the QR code carries `K`.
    public static func base64url(_ data: Data) -> String {
        let plain: String = data.base64EncodedString()
        let swapped: String = plain.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        return swapped.replacingOccurrences(of: "=", with: "")
    }

    /// The name for the QR code: UTF-8 cut at a character boundary to at most 128 bytes, then percent-encoded
    /// (everything but letters, digits and `-._~`).
    public static func encodedName(_ name: String) -> String {
        var kept = ""
        var size = 0
        for character in name {
            let bytes: String.UTF8View = String(character).utf8
            if bytes.contains(0) || size + bytes.count > 128 { break }
            kept.append(character)
            size += bytes.count
        }
        var unreserved = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        unreserved.insert(charactersIn: "-._~")
        return kept.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }

    /// `openbridge://pair?k=<K>&n=<name>`, the text in the QR code.
    public static func link(key: Data, name: String) -> String {
        "openbridge://pair?k=" + base64url(key) + "&n=" + encodedName(name)
    }

    /// `Ps`, which the phone sends: HMAC-SHA256(K, "OpenBridge QR phone" ‖ F ‖ Nm ‖ Np).
    public static func phoneProof(key: Data, fingerprint: Data, macNonce: Data, phoneNonce: Data) -> Data {
        proof("OpenBridge QR phone", key: key, [fingerprint, macNonce, phoneNonce])
    }

    /// `Pc`, which this Mac sends: HMAC-SHA256(K, "OpenBridge QR computer" ‖ F ‖ Nm ‖ Np).
    public static func computerProof(key: Data, fingerprint: Data, macNonce: Data, phoneNonce: Data) -> Data {
        proof("OpenBridge QR computer", key: key, [fingerprint, macNonce, phoneNonce])
    }

    /// Compares two proofs without stopping at the first difference.
    static func same(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for (x, y) in zip(a, b) { difference |= x ^ y }
        return difference == 0
    }

    private static func proof(_ label: String, key: Data, _ parts: [Data]) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key))
        mac.update(data: Data(label.utf8))
        for part in parts { mac.update(data: part) }
        return Data(mac.finalize())
    }
}

/// Why pairing by QR code didn't work with one phone. Anything else (a network error) means nothing answered.
public enum QRPairingError: Error, Equatable {
    /// The phone holds no code from this Mac (it hasn't scanned one yet), with the phone's words.
    case notScanned(String)
    /// The phone's app is older than 1.3.0: pair with the 6-digit code instead.
    case tooOld
    /// The phone's proof didn't match: another computer's code, or something in between. Nothing was sent.
    case wrongPhone
}

extension BridgeClient {
    /// Pairs by QR code (OBP3) with a phone at a private IPv4 address that scanned the code holding `key`.
    public static func pairQR(host: String, port: UInt16, key: Data, timeout: TimeInterval = 4) async throws -> BridgeClient {
        guard Wire.privateIPv4(host), port > 0 else { throw BridgeError.message("Not a private Wi-Fi IPv4 address.") }
        return try await qrExchange(address: host, port: port, key: key, timeout: timeout)
    }

    /// Pairs by QR code with a phone reached by Fast USB, through an `ADBTunnel` on `port`.
    public static func pairQR(tunnel port: UInt16, key: Data, timeout: TimeInterval = 4) async throws -> BridgeClient {
        try await qrExchange(address: loopback, port: port, key: key, timeout: timeout)
    }

    private static func qrExchange(address: String, port: UInt16, key: Data, timeout: TimeInterval) async throws -> BridgeClient {
        guard key.count == QRPairing.keySize else { throw BridgeError.message("Invalid pairing code.") }
        // No pin yet: the phone's proof below ties the certificate it showed to the key in the QR code.
        let channel = TLSChannel(host: address, port: port, fingerprint: nil)
        defer { channel.close() }
        try await channel.connect(timeout: timeout)
        guard let fingerprint = channel.peerFingerprint else { throw BridgeError.message("The phone sent no certificate.") }
        var generator = SystemRandomNumberGenerator()
        let macNonce = Data((0..<32).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
        let hello: Data = Data("OBP3".utf8) + Pairing.nameBlock(computerName) + macNonce
        try await channel.send(hello)
        do {
            try await channel.status(timeout: 10)
        } catch BridgeError.forgotten {
            // A phone before 1.3.0 reads OBP3 as a wrong secret.
            throw QRPairingError.tooOld
        } catch BridgeError.message(let text) {
            throw QRPairingError.notScanned(text)
        }
        let phoneNonce: Data = try await channel.read(32)
        let shown: Data = try await channel.read(32)
        let expected: Data = QRPairing.phoneProof(key: key, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce)
        guard QRPairing.same(shown, expected) else { throw QRPairingError.wrongPhone }
        let answer: Data = QRPairing.computerProof(key: key, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce)
        try await channel.send(answer)
        do {
            try await channel.status(timeout: 15)
        } catch BridgeError.forgotten {
            throw BridgeError.message("The phone didn't accept the code. Refresh it and scan again.")
        }
        let secret: Data = try await channel.read(32)
        return BridgeClient(testHost: address, port: port, fingerprint: fingerprint, secret: secret)
    }
}
