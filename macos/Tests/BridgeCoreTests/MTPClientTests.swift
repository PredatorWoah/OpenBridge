import XCTest
@testable import BridgeCore
@testable import MTPCore
import MTPTestSupport

final class MTPClientTests: XCTestCase {
    private func client(_ phone: FakePhone) -> MTPClient {
        MTPClient(name: "Test phone", session: nil, reconnect: {
            phone.closed = false
            let session = MTPSession(transport: phone, chunkSize: 4096)
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
}
