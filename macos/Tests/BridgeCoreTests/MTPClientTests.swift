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
        let content = Data((0..<50_000).map { UInt8($0 % 241) })
        phone.objects[60] = .init(storage: 0x10001, parent: MTPSession.root, name: "big.bin", folder: false,
                                  data: content, bigSize: nil)
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
        // The interrupted download is kept, so the next attempt continues instead of starting over.
        let kept = directory.appendingPathComponent(".a.bin.50000.openbridge-part")
        let keptSize = try Data(contentsOf: kept).count
        XCTAssertGreaterThan(keptSize, 0)
        XCTAssertLessThan(keptSize, 50_000)
        var resumedFrom: Int64 = -1
        try await client.download(file, to: directory.appendingPathComponent("a.bin")) { done, _ in if resumedFrom < 0 { resumedFrom = done } }
        XCTAssertEqual(resumedFrom, Int64(keptSize), "continued from the kept bytes")
        XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("a.bin")), content)
        XCTAssertFalse(FileManager.default.fileExists(atPath: kept.path), "partial file published")
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testResumeStartsOverWhenThePhoneCannot() async throws {
        let phone = FakePhone()
        phone.supportsPartialObject = false
        let client = client(phone)
        _ = try await client.list(id: "")
        let content = Data((0..<20_000).map { UInt8($0 % 239) })
        phone.objects[61] = .init(storage: 0x10001, parent: MTPSession.root, name: "clip.bin", folder: false, data: content, bigSize: nil)
        let directory = try temporaryDirectory()
        let target = directory.appendingPathComponent("clip.bin")
        try Data(content.prefix(5000)).write(to: directory.appendingPathComponent(".clip.bin.20000.openbridge-part"))
        try await client.download(Entry(id: "o:65537:61", name: "clip.bin", directory: false, size: 20_000), to: target) { _, _ in }
        XCTAssertEqual(try Data(contentsOf: target), content)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["clip.bin"])

        // A partial copy of a different size is never continued.
        try Data(repeating: 1, count: 100).write(to: directory.appendingPathComponent(".other.bin.999.openbridge-part"))
        phone.supportsPartialObject = true
        let second = directory.appendingPathComponent("again.bin")
        try Data(repeating: 9, count: 5000).write(to: directory.appendingPathComponent(".again.bin.20000.openbridge-part"))
        do { try await client.download(Entry(id: "o:65537:61", name: "clip.bin", directory: false, size: 20_001), to: second) { _, _ in }; XCTFail("size changed") }
        catch { XCTAssertTrue(error.localizedDescription.contains("changed")) }
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testFoldersAndThumbnails() async throws {
        let phone = FakePhone()
        let client = client(phone)
        let top = try await client.list(id: "s:65537")
        let made = try await client.makeFolder(named: "Trips", in: "s:65537")
        XCTAssertTrue(made.directory)
        let afterCreate = try await client.list(id: "s:65537")
        XCTAssertEqual(afterCreate.map(\.name), ["DCIM", "Download", "Trips", "huge.mkv"])
        let inside = try await client.list(id: made.id)
        XCTAssertEqual(inside, [])
        do { _ = try await client.makeFolder(named: "trips", in: "s:65537"); XCTFail("name clash") }
        catch { XCTAssertTrue(error.localizedDescription.contains("already exists")) }
        let photos = try await client.list(id: top[0].id)
        let preview = try await client.thumbnail(photos[0], size: 256)
        XCTAssertEqual(preview.first, 0xFF)
        do { _ = try await client.thumbnail(top[2], size: 256); XCTFail("no preview for a video without one") } catch {}
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testRecoversFromAUSBHiccupByItself() async throws {
        let phone = FakePhone()
        let client = client(phone)
        let top = try await client.list(id: "s:65537")
        // The cable fails once: the listing reconnects and succeeds without anyone pressing Refresh.
        phone.breakAfterReads = 0
        let again = try await client.list(id: "s:65537")
        XCTAssertEqual(again.map(\.name), top.map(\.name))
        XCTAssertNil(phone.breakAfterReads, "the failure was triggered")
        XCTAssertTrue(phone.aborted)

        // Previews that break the connection twice stay off, so they can't keep breaking browsing.
        let photos = try await client.list(id: top[0].id)
        for _ in 0..<2 {
            _ = try await client.list(id: top[0].id)   // a working session, so the failure hits the preview itself
            phone.breakAfterReads = 0
            do { _ = try await client.thumbnail(photos[0], size: 256); XCTFail("expected a USB failure") } catch {}
        }
        do { _ = try await client.thumbnail(photos[0], size: 256); XCTFail("previews are off") }
        catch { XCTAssertTrue(error.localizedDescription.contains("No preview")) }
        let listed = try await client.list(id: top[0].id)
        XCTAssertEqual(listed.map(\.name), ["photo.jpg"])
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testRenameAndDeleteOverUSB() async throws {
        let phone = FakePhone()
        let client = client(phone)
        let top = try await client.list(id: "s:65537")
        let photos = try await client.list(id: top[0].id)
        let renamed = try await client.rename(photos[0], to: "holiday.jpg")
        XCTAssertEqual(renamed.name, "holiday.jpg")
        let inside = try await client.list(id: top[0].id)
        XCTAssertEqual(inside.map(\.name), ["holiday.jpg"])
        do { _ = try await client.rename(top[1], to: "dcim"); XCTFail("name clash") }
        catch { XCTAssertTrue(error.localizedDescription.contains("already exists")) }
        try await client.delete(top[0])   // DCIM, with the photo inside
        let left = try await client.list(id: "s:65537")
        XCTAssertEqual(left.map(\.name), ["Download", "huge.mkv"])
        XCTAssertFalse(phone.objects.values.contains { $0.name == "holiday.jpg" })
        let empty = try await client.measure(top[1])
        XCTAssertEqual(empty, FolderSize(bytes: 0, files: 0, complete: true))
        XCTAssertEqual(phone.violations, [])
    }

    @MainActor
    func testSizesAndSearchOverUSB() async throws {
        // Phones that answer for a whole folder at once, and phones asked object by object, give the same answers.
        for lists in [true, false] {
            let phone = FakePhone()
            phone.supportsPropertyLists = lists
            let client = client(phone)
            let top = try await client.list(id: "s:65537")
            let dcim = try XCTUnwrap(top.first { $0.name == "DCIM" })
            let size = try await client.measure(dcim)
            XCTAssertEqual(size, FolderSize(bytes: 3000, files: 1, complete: true))
            let hits = try await client.search(in: "s:65537", for: "PHOTO")
            XCTAssertEqual(hits.found.map(\.entry.name), ["photo.jpg"])
            XCTAssertEqual(hits.found.first?.location, "DCIM")
            XCTAssertTrue(hits.complete)
            let everywhere = try await client.search(in: "", for: ".mkv")
            XCTAssertEqual(everywhere.found.map(\.entry.name), ["huge.mkv"])
            XCTAssertEqual(everywhere.found.first?.entry.size, 5 << 30)
            XCTAssertEqual(everywhere.found.first?.location, "Internal shared storage")
            XCTAssertEqual(phone.violations, [])
        }
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
