import XCTest
import Security
@testable import BridgeCore

final class MirrorTests: XCTestCase {
    // MARK: ADB

    func testADBHeaderLayoutAndChecksum() throws {
        let message = ADBMessage(command: ADBMessage.connect, arg0: ADBMessage.version, arg1: ADBMessage.maxPayload, payload: Data("host::\0".utf8))
        let header = [UInt8](message.header)
        XCTAssertEqual(header.count, 24)
        XCTAssertEqual(Array(header[0..<4]), Array("CNXN".utf8))                    // little-endian command spells the name
        XCTAssertEqual(Array(header[12..<16]), [7, 0, 0, 0])                         // payload length
        let sum = Data("host::\0".utf8).reduce(UInt32(0)) { $0 + UInt32($1) }
        XCTAssertEqual(Array(header[16..<20]), withUnsafeBytes(of: sum.littleEndian) { Array($0) })   // byte sum, not CRC
        XCTAssertEqual(Array(header[20..<24]), withUnsafeBytes(of: (ADBMessage.connect ^ 0xFFFF_FFFF).littleEndian) { Array($0) })
        let (parsed, length) = try ADBMessage.parse(header: message.header)
        XCTAssertEqual(parsed.command, ADBMessage.connect)
        XCTAssertEqual(parsed.arg0, ADBMessage.version)
        XCTAssertEqual(length, 7)
        var damaged = message.header
        damaged[20] ^= 1
        XCTAssertThrowsError(try ADBMessage.parse(header: damaged))
    }

    func testMontgomeryRRMatchesPlainArithmetic() {
        XCTAssertEqual(ADBKey.montgomeryRR([7]), [2])                                   // 2^64 mod 7
        let n: UInt64 = 0x1_FFFF_FFFB
        var expected: UInt64 = 1
        for _ in 0..<128 { expected = (expected * 2) % n }                              // 2^128 mod n
        XCTAssertEqual(ADBKey.montgomeryRR([0xFFFF_FFFB, 1]), [UInt32(truncatingIfNeeded: expected), UInt32(expected >> 32)])
    }

    func testAndroidPublicKeyFormatAndSignature() throws {
        var error: Unmanaged<CFError>?
        let secKey = try XCTUnwrap(SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048] as CFDictionary, &error))
        let key = try ADBKey(secKey)
        let encoded = try key.androidPublicKey(name: "OpenBridge@Test")
        XCTAssertEqual(encoded.last, 0, "NUL-terminated")
        let text = String(decoding: encoded.dropLast(), as: UTF8.self)
        XCTAssertTrue(text.hasSuffix(" OpenBridge@Test"))
        let blob = try XCTUnwrap(Data(base64Encoded: String(text.split(separator: " ")[0])))
        XCTAssertEqual(blob.count, 524)
        func word(_ i: Int) -> UInt32 { blob.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: i * 4, as: UInt32.self)) } }
        XCTAssertEqual(word(0), 64)
        XCTAssertEqual(word(1) &* word(2), 0xFFFF_FFFF, "n0inv · n[0] ≡ −1 mod 2^32")
        XCTAssertEqual(word(130), 65_537, "exponent")
        // The modulus in the struct is the key's modulus, little-endian.
        let der = try XCTUnwrap(SecKeyCopyExternalRepresentation(SecKeyCopyPublicKey(secKey)!, &error) as Data?)
        let (modulus, _) = try ADBKey.parseRSAPublicKey(der)
        XCTAssertEqual(Array(blob[8..<264]), Array(modulus.reversed()))
        // adbd verifies RSA_sign(NID_sha1) over the raw token: PKCS#1 v1.5 with the SHA-1 DigestInfo, no further hashing.
        let token = Data((0..<20).map { UInt8($0 * 7) })
        let signature = try key.sign(token)
        XCTAssertEqual(signature.count, 256)
        XCTAssertTrue(SecKeyVerifySignature(SecKeyCopyPublicKey(secKey)!, .rsaSignatureDigestPKCS1v15SHA1, token as CFData, signature as CFData, &error))
    }

    // MARK: scrcpy

    func testServerCommandAndSocket() {
        XCTAssertEqual(Scrcpy.socket(0x1a2b3c4d), "localabstract:scrcpy_1a2b3c4d")
        XCTAssertEqual(Scrcpy.socketID(0xFFFF_FFFF), "7fffffff", "31-bit")
        let command = Scrcpy.command(scid: 0x1a2b3c4d, maxSize: 1920)
        XCTAssertTrue(command.hasPrefix("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0.1 "))
        for option in ["scid=1a2b3c4d", "audio=false", "tunnel_forward=true", "max_size=1920"] { XCTAssertTrue(command.contains(option), option) }
    }

    func testControlMessageLayouts() throws {
        let touch = [UInt8](Scrcpy.touch(.down, x: 0x102, y: 0x304, width: 1080, height: 2400, pressure: 1,
                                         actionButton: Scrcpy.primaryButton, buttons: Scrcpy.primaryButton))
        XCTAssertEqual(touch.count, 32)
        XCTAssertEqual(touch[0], 2); XCTAssertEqual(touch[1], 0)
        XCTAssertEqual(Array(touch[2..<10]), [UInt8](repeating: 0xFF, count: 8), "mouse pointer id −1")
        XCTAssertEqual(Array(touch[10..<22]), [0, 0, 1, 2, 0, 0, 3, 4, 0x04, 0x38, 0x09, 0x60])
        XCTAssertEqual(Array(touch[22..<24]), [0xFF, 0xFF], "pressure 1.0")
        XCTAssertEqual(Array(touch[24..<32]), [0, 0, 0, 1, 0, 0, 0, 1])
        XCTAssertEqual(Array(Scrcpy.touch(.up, x: 0, y: 0, width: 1, height: 1, pressure: 0, actionButton: 1, buttons: 0)[22..<24]), [0, 0])

        let scroll = [UInt8](Scrcpy.scroll(x: 1, y: 2, width: 3, height: 4, dx: -16, dy: 32))
        XCTAssertEqual(scroll.count, 21)
        XCTAssertEqual(scroll[0], 3)
        XCTAssertEqual(Array(scroll[13..<15]), [0x80, 0x00], "−1.0")
        XCTAssertEqual(Array(scroll[15..<17]), [0x7F, 0xFF], "clamped to 1.0")

        XCTAssertEqual([UInt8](Scrcpy.key(.up, keycode: Scrcpy.Key.home)), [0, 1, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0])
        XCTAssertEqual([UInt8](Scrcpy.backOrScreenOn(.down)), [4, 0])

        let pieces = Scrcpy.text(String(repeating: "é", count: 400))   // 800 bytes
        XCTAssertEqual(pieces.count, 3)
        XCTAssertTrue(pieces.allSatisfy { $0.count <= 5 + 300 && $0[0] == 1 })

        let clipboard = [UInt8](try XCTUnwrap(Scrcpy.setClipboard("hi", paste: true)))
        XCTAssertEqual(clipboard, [9, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0x68, 0x69])
        XCTAssertNil(Scrcpy.setClipboard(String(repeating: "x", count: 262_131)))
    }

    func testVideoHeadersAndDeviceMessages() throws {
        XCTAssertEqual(try Scrcpy.parseHeader(Data([0x80, 0, 0, 0, 0, 0, 0x04, 0x38, 0, 0, 0x09, 0x60])), .session(width: 1080, height: 2400))
        XCTAssertEqual(try Scrcpy.parseHeader(Data([0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30])), .media(size: 30, config: true, keyFrame: false))
        XCTAssertEqual(try Scrcpy.parseHeader(Data([0x20, 0, 0, 0, 0, 1, 0x86, 0xA0, 0, 0, 0x10, 0])), .media(size: 4096, config: false, keyFrame: true))
        XCTAssertThrowsError(try Scrcpy.parseHeader(Data([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])), "empty packet")

        var bytes = Data([0]) + Data([0, 0, 0, 5]) + Data("hello".utf8) + Data([1]) + Data(repeating: 0, count: 8)
        func read(_ n: Int) throws -> Data { defer { bytes.removeFirst(n) }; return Data(bytes.prefix(n)) }
        XCTAssertEqual(try Scrcpy.readDeviceMessage(read), "hello")
        XCTAssertNil(try Scrcpy.readDeviceMessage(read))
    }

    func testAnnexBToLengthPrefixed() {
        let stream = Data([0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6])
        let units = H264.units(stream)
        XCTAssertEqual(units.map(H264.type), [7, 8, 5])
        XCTAssertEqual(units[0], Data([0x67, 1, 2]))
        XCTAssertEqual(H264.lengthPrefixed([Data([0x65, 4])]), Data([0, 0, 0, 2, 0x65, 4]))
    }
}
