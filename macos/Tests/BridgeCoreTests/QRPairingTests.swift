import XCTest
@testable import BridgeCore

/// Pairing by QR code (OBP3), byte for byte as docs/protocol.md describes it. The vectors are shared with the phone
/// app's tests: K is the bytes 00 to 0f; F, Nm and Np are filled with 01, 02 and 03.
final class QRPairingTests: XCTestCase {
    private let key = Data(0..<16)
    private let fingerprint = Data(repeating: 1, count: 32)
    private let macNonce = Data(repeating: 2, count: 32)
    private let phoneNonce = Data(repeating: 3, count: 32)

    private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    func testProofVectors() {
        let phone: Data = QRPairing.phoneProof(key: key, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce)
        let computer: Data = QRPairing.computerProof(key: key, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce)
        XCTAssertEqual(hex(phone), "3a0bac9b999778f24e7aee72417ec7de8b211b4a76de2af63c3d4570a9f5585d")
        XCTAssertEqual(hex(computer), "3d8f513260fbb3245d86903c5e06f23003c55c21f2afbc3988490133bc86819d")
        XCTAssertNotEqual(phone, computer)
    }

    func testProofsDependOnEveryInput() {
        let base: Data = QRPairing.phoneProof(key: key, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce)
        var otherKey = key
        otherKey[0] ^= 1
        XCTAssertNotEqual(base, QRPairing.phoneProof(key: otherKey, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce))
        XCTAssertNotEqual(base, QRPairing.phoneProof(key: key, fingerprint: macNonce, macNonce: macNonce, phoneNonce: phoneNonce))
        XCTAssertNotEqual(base, QRPairing.phoneProof(key: key, fingerprint: fingerprint, macNonce: phoneNonce, phoneNonce: phoneNonce))
        XCTAssertNotEqual(base, QRPairing.phoneProof(key: key, fingerprint: fingerprint, macNonce: macNonce, phoneNonce: macNonce))
    }

    func testLink() {
        XCTAssertEqual(QRPairing.base64url(key), "AAECAwQFBgcICQoLDA0ODw")
        XCTAssertEqual(QRPairing.base64url(Data([0xfb, 0xff])), "-_8")
        XCTAssertEqual(QRPairing.link(key: key, name: "Aditya's MacBook Air"),
                       "openbridge://pair?k=AAECAwQFBgcICQoLDA0ODw&n=Aditya%27s%20MacBook%20Air")
        XCTAssertEqual(QRPairing.encodedName("Café & co/1"), "Caf%C3%A9%20%26%20co%2F1")
        // At most 128 bytes of UTF-8, cut at a character boundary.
        let long: String = QRPairing.encodedName(String(repeating: "é", count: 70))
        XCTAssertEqual(long, String(repeating: "%C3%A9", count: 64))
        XCTAssertEqual(QRPairing.encodedName(String(repeating: "a", count: 200)).count, 128)
    }

    func testFreshKeys() throws {
        let first: Data = try QRPairing.newKey()
        let second: Data = try QRPairing.newKey()
        XCTAssertEqual(first.count, 16)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(QRPairing.base64url(first).count, 22)
    }

    func testConstantTimeComparison() {
        XCTAssertTrue(QRPairing.same(Data([1, 2, 3]), Data([1, 2, 3])))
        XCTAssertFalse(QRPairing.same(Data([1, 2, 3]), Data([1, 2, 4])))
        XCTAssertFalse(QRPairing.same(Data([1, 2, 3]), Data([1, 2])))
    }
}
