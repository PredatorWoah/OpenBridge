import XCTest
@testable import BridgeCore
@testable import MTPCore
import MTPTestSupport

final class MTPClientTests: XCTestCase {
    private let pixel = MTPDeviceIdentity(manufacturer: "Google", model: "Pixel 8", serialNumber: "SERIAL")

    /// A client opened on the "SERIAL" phone. `connected` decides which phone answers a reconnect.
    private func client(_ phone: FakePhone, connected: (() -> FakePhone)? = nil) -> MTPClient {
        MTPClient(name: "Test phone", identity: pixel, session: nil, reconnect: {
            let target = connected?() ?? phone
            target.closed = false
            let session = MTPSession(transport: target, chunkSize: 4096)
            try session.open()
            return session
        })
    }

    private func temporaryDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }

    @MainActor
    func testBrowseDownloadAndUpload() async throws {
        let phone = FakePhone()
        let client = client(phone)
        let storages = try await client.list(id: "")
        XCTAssertEqual(storages.map(\.name), ["Internal shared storage"])
        XCTAssertTrue(storages[0].directory)
        let top = try await client.list(id: storages[0].id)
        XCTAssertEqual(top.map(\.name), ["DCIM", "Download", "huge.mkv"])
        XCTAssertEqual(top[2].size, 5 << 30)
        let photos = try await client.list(id: top[0].id)
        XCTAssertEqual(photos.map(\.name), ["photo.jpg"])

        let directory = try temporaryDirectory()
        let target = directory.appendingPathComponent("photo.jpg")
        var progress: Int64 = 0
        try await client.download(photos[0], to: target) { done, _ in progress = done }
        XCTAssertEqual(try Data(contentsOf: target), phone.objects[2]?.data)
        XCTAssertEqual(progress, 3000)
        do { try await client.download(photos[0], to: target) { _, _ in }; XCTFail("existing file must not be replaced") }
        catch { XCTAssertTrue(error.localizedDescription.contains("already exists")) }

        let source = directory.appendingPathComponent("notes.txt")
        let bytes = Data((0..<9000).map { UInt8($0 % 200) })
        try bytes.write(to: source)
        try await client.upload(source, directoryID: top[1].id) { done, _ in progress = done }
        XCTAssertEqual(progress, 9000)
        let downloads = try await client.list(id: top[1].id)
        XCTAssertEqual(downloads.map(\.name), ["notes.txt"])
        XCTAssertEqual(phone.objects.values.first { $0.name == "notes.txt" }?.data, bytes)

        // Collisions ignore case, like the phone's storage does.
        let shouting = directory.appendingPathComponent("NOTES.TXT")
        try bytes.write(to: shouting)
        do { try await client.upload(shouting, directoryID: top[1].id) { _, _ in }; XCTFail("case-insensitive collision") }
        catch { XCTAssertTrue(error.localizedDescription.contains("already exists")) }
        phone.freeSpace = 100
        let roomy = directory.appendingPathComponent("roomy.bin")
        try bytes.write(to: roomy)
        do { try await client.upload(roomy, directoryID: top[1].id) { _, _ in }; XCTFail("not enough space") }
        catch { XCTAssertTrue(error.localizedDescription.contains("Not enough space")) }
        XCTAssertFalse(phone.objects.values.contains { $0.name == "roomy.bin" }, "nothing created on the phone")
        phone.freeSpace = 64 << 30
        do { try await client.upload(source, directoryID: "") { _, _ in }; XCTFail("device root is not a folder") }
        catch { XCTAssertTrue(error.localizedDescription.contains("storage")) }
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testRecoversFromErrors() async throws {
        let phone = FakePhone()
        let client = client(phone)
        _ = try await client.list(id: "")
        phone.failNextWith = 0x2019   // a response error keeps the session
        do { _ = try await client.list(id: "s:65537"); XCTFail("expected busy") } catch {}
        XCTAssertFalse(phone.aborted)
        let recovered = try await client.list(id: "s:65537")
        XCTAssertEqual(recovered.count, 3)
        do { _ = try await client.list(id: "bogus"); XCTFail("bad id") } catch {}

        // A USB failure mid-download resets the phone; the next action reconnects with a new session.
        phone.objects[60] = .init(storage: 0x10001, parent: MTPSession.root, name: "big.bin", folder: false,
                                  data: Data(repeating: 7, count: 50_000), bigSize: nil)
        let directory = try temporaryDirectory()
        let file = Entry(id: "o:65537:60", name: "big.bin", directory: false, size: 50_000)
        // Object info (data + response), then the first data chunk succeed; the failure hits the next chunk.
        phone.breakAfterReads = 3
        var partial: Int64 = 0
        do { try await client.download(file, to: directory.appendingPathComponent("a.bin")) { done, _ in partial = done }; XCTFail("expected failure") }
        catch { XCTAssertTrue(phone.aborted) }
        XCTAssertNil(phone.breakAfterReads, "the failure was triggered")
        XCTAssertEqual(partial, 0, "failed inside the data phase, before the end")
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("a.bin").path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), [], "temporary file removed")
        try await client.download(file, to: directory.appendingPathComponent("b.bin")) { _, _ in }
        XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("b.bin")).count, 50_000)
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testRefusesToContinueOnADifferentPhone() async throws {
        let original = FakePhone(), other = FakePhone()
        other.serialNumber = "SOMEONE-ELSE"
        var current = original
        let client = client(original, connected: { current })
        let top = try await client.list(id: "s:65537")
        // The cable is swapped and the session drops; the next action must not run against the new phone.
        current = other
        original.breakAfterReads = 0
        do { _ = try await client.list(id: top[0].id); XCTFail("expected the first failure") } catch {}
        do { _ = try await client.list(id: top[0].id); XCTFail("must refuse a different phone") }
        catch { XCTAssertTrue(error.localizedDescription.contains("different phone"), error.localizedDescription) }
        let directory = try temporaryDirectory()
        do { try await client.download(Entry(id: "o:65537:2", name: "photo.jpg", directory: false, size: 3000),
                                       to: directory.appendingPathComponent("p.jpg")) { _, _ in }; XCTFail("must refuse") }
        catch { XCTAssertTrue(error.localizedDescription.contains("different phone")) }
        XCTAssertFalse(other.sessionOpen, "the other phone's session is closed again")
        // Back to the right phone: everything works again.
        current = original
        let again = try await client.list(id: top[0].id)
        XCTAssertEqual(again.map(\.name), ["photo.jpg"])
    }

    @MainActor
    func testFailedUploadsAreCleanedUpAtEveryStage() async throws {
        let directory = try temporaryDirectory()
        let source = directory.appendingPathComponent("movie.bin")
        try Data((0..<50_000).map { UInt8($0 % 251) }).write(to: source)
        // Fail right after the object is created (starting the data phase), then partway through the data.
        for failure in ["start", "middle"] {
            let phone = FakePhone()
            let client = client(phone)
            _ = try await client.list(id: "s:65537")
            if failure == "start" { phone.failWritingCommand = MTPCode.sendObject } else { phone.breakAfterWrites = 6 }
            do { try await client.upload(source, directoryID: "o:65537:3") { _, _ in }; XCTFail("expected failure (\(failure))") }
            catch { XCTAssertFalse(error.localizedDescription.contains("may remain"), "cleaned up (\(failure))") }
            XCTAssertTrue(phone.aborted, failure)
            XCTAssertFalse(phone.objects.values.contains { $0.name == "movie.bin" }, "no partial file left (\(failure))")
            XCTAssertEqual(phone.violations, [], failure)
        }
    }

    @MainActor
    func testCleanupNeverDeletesOnAnotherPhone() async throws {
        let original = FakePhone(), other = FakePhone()
        other.serialNumber = "SOMEONE-ELSE"
        // The other phone has an unrelated file under the handle the upload will get (handles start at 100).
        other.objects[100] = .init(storage: 0x10001, parent: 3, name: "precious.jpg", folder: false, data: Data([1, 2, 3]), bigSize: nil)
        var current = original
        let client = client(original, connected: { current })
        _ = try await client.list(id: "s:65537")
        let directory = try temporaryDirectory()
        let source = directory.appendingPathComponent("movie.bin")
        try Data(repeating: 9, count: 20_000).write(to: source)
        original.failWritingCommand = MTPCode.sendObject
        current = other   // the reconnect for cleanup lands on a different phone
        do { try await client.upload(source, directoryID: "o:65537:3") { _, _ in }; XCTFail("expected failure") }
        catch { XCTAssertTrue(error.localizedDescription.contains("may remain"), error.localizedDescription) }
        XCTAssertEqual(other.objects[100]?.name, "precious.jpg", "the other phone's file is untouched")
    }

    func testCleanupChecksTheObjectIsStillTheSame() throws {
        let phone = FakePhone()
        let session = MTPSession(transport: phone, chunkSize: 4096)
        try session.open()
        let created = try session.createObject(storage: 0x10001, parent: MTPSession.root, name: "upload.bin", size: 10)
        // The handle now names a different file (the phone reused it): never delete that.
        phone.objects[created.handle]?.name = "someone-else.txt"
        XCTAssertFalse(try session.deleteIfUnchanged(created))
        XCTAssertNotNil(phone.objects[created.handle])
        phone.objects[created.handle]?.name = "upload.bin"
        XCTAssertTrue(try session.deleteIfUnchanged(created), "top-level objects match even though the phone reports parent 0")
        XCTAssertNil(phone.objects[created.handle])
    }

    @MainActor
    func testCleanupSkipsAHandleThatNowNamesAnotherFile() async throws {
        let phone = FakePhone()
        let client = client(phone)
        _ = try await client.list(id: "s:65537")
        let directory = try temporaryDirectory()
        let source = directory.appendingPathComponent("movie.bin")
        try Data(repeating: 4, count: 20_000).write(to: source)
        phone.failWritingCommand = MTPCode.sendObject
        // Same phone, but by the time cleanup runs the handle names someone else's file.
        phone.onAbort = { [unowned phone] in
            if let handle = phone.objects.first(where: { $0.value.name == "movie.bin" })?.key { phone.objects[handle]?.name = "someone-else.txt" }
        }
        do { try await client.upload(source, directoryID: "o:65537:3") { _, _ in }; XCTFail("expected failure") }
        catch { XCTAssertTrue(error.localizedDescription.contains("may remain"), error.localizedDescription) }
        XCTAssertTrue(phone.objects.values.contains { $0.name == "someone-else.txt" }, "unrelated file is untouched")
    }
}
