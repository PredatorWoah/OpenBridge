import XCTest
@testable import MTPCore
import MTPTestSupport

final class SessionTests: XCTestCase {
    func testCodecRoundTrips() throws {
        let info = MTPObjectInfo(storageID: 0x10001, format: MTPCode.undefinedFormat, compressedSize: 42,
                                 parent: 7, filename: "résumé 📄.txt")
        XCTAssertEqual(try MTPObjectInfo(parsing: try info.encoded()), info)
        XCTAssertEqual(ContainerHeader.command(0x1002, transactionID: 0, parameters: [1]),
                       Data([16, 0, 0, 0, 1, 0, 2, 0x10, 0, 0, 0, 0, 1, 0, 0, 0]))
        var writer = MTPWriter()
        XCTAssertThrowsError(try writer.string(String(repeating: "a", count: 255)))
        var reader = MTPReader(Data([5, 0, 0]))
        XCTAssertThrowsError(try reader.string(), "truncated strings are rejected")
        XCTAssertThrowsError(try ContainerHeader(parsing: Data([4, 0, 0, 0, 1, 0, 1, 0x10, 0, 0, 0, 0])))
    }

    func testBrowseDevice() throws {
        let phone = FakePhone()
        let session = MTPSession(transport: phone, chunkSize: 4096)
        XCTAssertEqual(try session.deviceInfo().displayName, "Google Pixel 8")
        XCTAssertEqual(try session.deviceInfo().identity, MTPDeviceIdentity(manufacturer: "Google", model: "Pixel 8", serialNumber: "SERIAL"))
        try session.open()
        let storages = try session.storages()
        XCTAssertEqual(storages.map(\.name), ["Internal shared storage"])
        let top = try session.children(storage: storages[0].id, parent: MTPSession.root)
        XCTAssertEqual(top.map(\.info.filename), ["DCIM", "Download", "huge.mkv"])
        XCTAssertTrue(top[0].info.isFolder)
        XCTAssertEqual(try session.size(of: 4, info: top[2].info), 5 << 30, "64-bit size property for big files")
        XCTAssertEqual(try session.children(storage: storages[0].id, parent: 1).map(\.info.filename), ["photo.jpg"])
        XCTAssertEqual(phone.violations, [])
    }

    func testReopensStaleSession() throws {
        let phone = FakePhone()
        phone.sessionOpen = true
        try MTPSession(transport: phone).open()
        XCTAssertTrue(phone.sessionOpen)
        XCTAssertEqual(phone.violations, [])
    }

    func testDownloadsAcrossPacketBoundaries() throws {
        // Sizes around packet and chunk boundaries, including exact multiples that need a zero-length packet.
        for size in [0, 1, 500, 512 - 12, 512, 1024 - 12, 4096 - 12, 4096, 10_000, 3 * 4096 - 12] {
            let phone = FakePhone()
            let bytes = Data((0..<size).map { UInt8(($0 * 7) % 256) })
            phone.objects[50] = .init(storage: 0x10001, parent: MTPSession.root, name: "f", folder: false, data: bytes, bigSize: nil)
            let session = MTPSession(transport: phone, chunkSize: 4096)
            try session.open()
            let stream = try session.beginDownload(50)
            XCTAssertEqual(stream.length, UInt64(size))
            var received = Data()
            while let chunk = try stream.next() { received.append(chunk) }
            _ = try stream.finish()
            XCTAssertEqual(received, bytes, "size \(size)")
            // The session must still work afterwards (no stray packets left in the pipe).
            XCTAssertEqual(try session.storages().count, 1, "size \(size)")
            XCTAssertEqual(phone.violations, [], "size \(size)")
        }
    }

    func testUploadsAcrossPacketBoundaries() throws {
        for size in [0, 1, 500, 512 - 12, 512, 4096 - 12, 4096, 9000, 2 * 4096 - 12] {
            let phone = FakePhone()
            let session = MTPSession(transport: phone, chunkSize: 4096)
            try session.open()
            let bytes = Data((0..<size).map { UInt8(($0 * 13) % 256) })
            let (handle, stream) = try session.beginUpload(storage: 0x10001, parent: 3, name: "up.bin", size: UInt64(size))
            var offset = 0
            for piece in [700, 1, 4096, 3000] where offset < size {   // uneven writes must still be packet aligned
                let end = min(size, offset + piece)
                try stream.write(bytes.subdata(in: offset..<end))
                offset = end
            }
            if offset < size { try stream.write(bytes.subdata(in: offset..<size)) }
            _ = try stream.finish()
            XCTAssertEqual(phone.objects[handle]?.name, "up.bin")
            XCTAssertEqual(phone.objects[handle]?.parent, 3)
            XCTAssertEqual(phone.objects[handle]?.data, bytes, "size \(size)")
            XCTAssertEqual(try session.storages().count, 1, "size \(size)")
            XCTAssertEqual(phone.violations, [], "size \(size)")
        }
    }

    func testErrorsAndLimits() throws {
        let phone = FakePhone()
        let session = MTPSession(transport: phone, chunkSize: 4096)
        try session.open()
        XCTAssertThrowsError(try session.beginDownload(999)) { XCTAssertEqual($0 as? MTPError, .response(0x2009)) }
        XCTAssertEqual(try session.storages().count, 1, "a response error keeps the session usable")
        phone.failNextWith = 0x200C
        XCTAssertThrowsError(try session.storages()) { XCTAssertEqual($0 as? MTPError, .response(0x200C)) }
        let stream = try session.beginUpload(storage: 0x10001, parent: 3, name: "short", size: 10).stream
        XCTAssertThrowsError(try stream.finish(), "declared length must be sent")
        XCTAssertTrue(MTPError.response(0x200C).keepsSession)
        XCTAssertFalse(MTPError.protocolViolation("x").keepsSession)
    }

    /// Files of 4 GiB or more use an unknown size, ended by a short packet. Simulated with a lowered threshold.
    func testLargeUploadsUseUnknownLength() throws {
        for size in [1000, 4096 - 12, 4096, 4096 * 2, 9000] {
            let phone = FakePhone()
            let session = MTPSession(transport: phone, chunkSize: 4096)
            session.sizeFieldLimit = 1000
            try session.open()
            let bytes = Data((0..<size).map { UInt8(($0 * 31) % 256) })
            let (handle, stream) = try session.beginUpload(storage: 0x10001, parent: 3, name: "big.bin", size: UInt64(size))
            try stream.write(bytes)
            _ = try stream.finish()
            XCTAssertEqual(phone.lastDeclaredSize, 0xFFFF_FFFF, "size \(size)")
            XCTAssertEqual(phone.objects[handle]?.data, bytes, "size \(size)")
            XCTAssertEqual(try session.storages().count, 1, "size \(size)")
            XCTAssertEqual(phone.violations, [], "size \(size)")
        }
        let phone = FakePhone()
        let session = MTPSession(transport: phone, chunkSize: 4096)
        try session.open()
        _ = try session.beginUpload(storage: 0x10001, parent: 3, name: "small", size: 999).stream
        XCTAssertEqual(phone.lastDeclaredSize, 999, "normal files keep their real size")
    }
}
