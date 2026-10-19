import XCTest
@testable import MTPCore
import MTPTestSupport

/// What real Android phones send over MTP that a tidy implementation wouldn't (see `FakePhone.quirky`).
final class QuirksTests: XCTestCase {
    private func open(_ phone: FakePhone) throws -> MTPSession {
        let session = MTPSession(transport: phone, chunkSize: 4096)
        try session.open()
        return session
    }

    func testDatesNamesAndFoldersAsPhonesSendThem() throws {
        let phone = FakePhone.quirky()
        let session = try open(phone)
        let top = try session.children(storage: 0x10001, parent: MTPSession.root)
        let chat = try XCTUnwrap(top.first { $0.info.filename == "微信" })
        XCTAssertTrue(chat.info.isFolder, "a folder by format alone, without an association type")
        XCTAssertEqual(chat.info.associationType, 0)
        let inside = try session.children(storage: 0x10001, parent: chat.handle)
        XCTAssertEqual(inside.map(\.handle), [11, 12, 13, 14], "an object listed twice comes back once")
        XCTAssertEqual(inside[0].info.filename, "旅行照片 🏖️.jpg")
        XCTAssertEqual(inside[0].info.modified, "20231201T080000.0+0800")
        XCTAssertEqual(inside[1].info.modified, "", "no date at all")
        XCTAssertEqual(inside[1].info.compressedSize, 0)
        XCTAssertEqual(inside[2].info.filename.utf16.count, 254)
        XCTAssertEqual(inside[2].info.filename, String(repeating: "长", count: 250) + ".txt")
        let photos = try session.children(storage: 0x10001, parent: 1)
        XCTAssertEqual(photos.first?.info.modified, "20240315T101500")
        XCTAssertEqual(phone.violations, [])
    }

    func testObjectInfoWithoutDatesOrCutShort() throws {
        let info = MTPObjectInfo(storageID: 1, format: MTPCode.undefinedFormat, compressedSize: 3, parent: 0, filename: "a.txt")
        let encoded = try info.encoded()
        XCTAssertEqual(try MTPObjectInfo(parsing: encoded), info)
        // Some phones end the dataset right after the name.
        let short = encoded.prefix(encoded.count - 3)
        XCTAssertEqual(try MTPObjectInfo(parsing: short).filename, "a.txt")
        XCTAssertEqual(try MTPObjectInfo(parsing: short).modified, "")
    }

    func testContentsAsksForNamesThePropertyListLeftOut() throws {
        for lists in [true, false] {
            let phone = FakePhone.quirky()
            phone.supportsPropertyLists = lists
            let session = try open(phone)
            let children = try session.contents(storage: 0x10001, parent: 10, names: true)
            XCTAssertEqual(children.map(\.handle), [11, 12, 13, 14], "lists: \(lists)")
            XCTAssertEqual(children.first { $0.handle == 14 }?.name, "录音", "lists: \(lists)")
            XCTAssertEqual(children.first { $0.handle == 14 }?.folder, true)
            XCTAssertEqual(children.first { $0.handle == 12 }?.size, 0)
            XCTAssertEqual(children.first { $0.handle == 11 }?.size, 700)
            XCTAssertEqual(try session.contents(storage: 0x10001, parent: 10, names: false).count, 4)
            XCTAssertEqual(phone.violations, [], "lists: \(lists)")
        }
    }

    func testEmptyFilesMoveBothWays() throws {
        let phone = FakePhone.quirky()
        let session = try open(phone)
        let download = try session.beginDownload(12)
        XCTAssertEqual(download.length, 0)
        XCTAssertNil(try download.next())
        _ = try download.finish()
        let (handle, upload) = try session.beginUpload(storage: 0x10001, parent: 10, name: "空.txt", size: 0)
        _ = try upload.finish()
        XCTAssertEqual(phone.objects[handle]?.name, "空.txt")
        XCTAssertEqual(phone.objects[handle]?.data, Data())
        XCTAssertEqual(try session.storages().count, 2, "the session still works")
        XCTAssertEqual(phone.violations, [])
    }

    func testStoragesWithImpossibleSpace() throws {
        let phone = FakePhone.quirky()
        phone.capacity = 0
        phone.freeSpace = 0
        let storages = try open(phone).storages()
        XCTAssertEqual(storages.map(\.name), ["Internal shared storage", "SD card"])
        XCTAssertEqual(storages[0].capacity, 0)
        XCTAssertGreaterThan(storages[1].freeSpace, storages[1].capacity)
    }
}
