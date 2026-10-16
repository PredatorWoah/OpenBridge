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
}
