import XCTest
@testable import BridgeCore

final class WireTests: XCTestCase {
    func testIntegerEncodingAndUnicode() throws {
        XCTAssertEqual(Wire.integer(UInt32(0x12345678)), Data([0x12, 0x34, 0x56, 0x78]))
        XCTAssertEqual(Wire.integer(Int64(65537)), Data([0, 0, 0, 0, 0, 1, 0, 1]))
        XCTAssertEqual(Wire.number(Data([0, 0, 1, 0])), 256)
        XCTAssertEqual(Wire.string("é"), Data([0, 0, 0, 2, 0xc3, 0xa9]))
        XCTAssertEqual(try Wire.hex(String(repeating: "aF", count: 32)), Data(repeating: 0xaf, count: 32))
        for text in ["", "abc", String(repeating: "gg", count: 32), String(repeating: "a", count: 65)] {
            XCTAssertThrowsError(try Wire.hex(text))
        }
    }
    func testPrivateAddressesOnly() {
        for host in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2"] {
            XCTAssertTrue(Wire.privateIPv4(host), host)
        }
        for host in ["127.0.0.1", "0.0.0.0", "8.8.8.8", "172.32.0.1", "100.64.0.1", "::1", "example.com", "10.00.0.1", "10.0.0", "10.0.0.256"] {
            XCTAssertFalse(Wire.privateIPv4(host), host)
        }
        XCTAssertThrowsError(try BridgeClient(host: "8.8.8.8", port: "443", fingerprint: String(repeating: "a", count: 64), secret: String(repeating: "b", count: 64)))
    }
    func testNamesAndCollisions() throws {
        try Wire.validateName("résumé.txt")
        for name in ["", ".", "..", "../secret", "/etc/passwd", "a/b", "a\\b", "a\0b", "a\nb", String(repeating: "é", count: 128)] {
            XCTAssertThrowsError(try Wire.validateName(name), name)
        }
    }

    func testLongNamesFitTheMac() {
        let long = String(repeating: "长", count: 250) + ".txt"
        let fitted = Wire.fitted(long)
        XCTAssertLessThanOrEqual(fitted.utf8.count, 255)
        XCTAssertTrue(fitted.hasSuffix("长.txt"))
        XCTAssertEqual(Wire.fitted("photo.jpg"), "photo.jpg")
        XCTAssertEqual(Wire.fitted("ééééé", limit: 5), "éé")
        let partial = PartialFile.location(for: URL(fileURLWithPath: "/tmp/" + long), size: 123_456)
        XCTAssertLessThanOrEqual(partial.lastPathComponent.utf8.count, 255)
        XCTAssertTrue(partial.lastPathComponent.hasSuffix(".txt.123456.openbridge-part"))
    }

    func testOldPhonesAreRecognized() {
        XCTAssertTrue(BridgeError.isUnsupported(BridgeError.message("Unsupported operation; update OpenBridge on the phone")))
        XCTAssertFalse(BridgeError.isUnsupported(BridgeError.message("Sending from the phone is not available")))
        XCTAssertFalse(BridgeError.isUnsupported(CancellationError()))
    }

    func testPairingMatchesTheAndroidImplementation() {
        // Same reference vector as ProtocolTest.java and scripts/test-tls.py.
        let fingerprint = Data(repeating: 1, count: 32), macNonce = Data(repeating: 2, count: 32), phoneNonce = Data(repeating: 3, count: 32)
        XCTAssertEqual(Pairing.code(fingerprint: fingerprint, macNonce: macNonce, phoneNonce: phoneNonce), "520380")
        XCTAssertEqual(Pairing.commitment(fingerprint: fingerprint, phoneNonce: phoneNonce).map { String(format: "%02x", $0) }.joined(),
                       "8ef8a25a4293066adbb289a0cff4a91b7e65d0db9811cd15d30f14d2c0a9b356")
        var other = fingerprint; other[0] ^= 1
        XCTAssertNotEqual(Pairing.code(fingerprint: other, macNonce: macNonce, phoneNonce: phoneNonce), "520380")
    }
}
