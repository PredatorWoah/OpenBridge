import XCTest
@testable import BridgeCore

final class InteropTests: XCTestCase {
    @MainActor
    func testRealTLSJavaServerAndSwiftClient() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let classpath = env["FIXTURE_CLASSPATH"], let keystore = env["FIXTURE_KEYSTORE"] else {
            throw XCTSkip("Set FIXTURE_CLASSPATH and FIXTURE_KEYSTORE to run the Java/Swift TLS integration test; see CI.")
        }
        let process = Process()
        let output = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["java", "-cp", classpath, "org.openbridge.protocol.TLSFixture", keystore]
        process.standardOutput = output
        try process.run()
        defer { process.terminate(); process.waitUntilExit() }
        var line = Data()
        while let byte = try output.fileHandleForReading.read(upToCount: 1), !byte.isEmpty {
            if byte == Data([10]) { break }
            line.append(byte)
        }
        let pieces = String(decoding: line, as: UTF8.self).split(separator: " ")
        guard pieces.count == 2, let port = UInt16(pieces[0]) else { return XCTFail("TLS fixture failed to start") }
        let fingerprint = try Wire.hex(String(pieces[1]))
        let secret = Data(repeating: 0x5a, count: 32)
        let client = BridgeClient(testHost: "127.0.0.1", port: port, fingerprint: fingerprint, secret: secret)
        let root = try await client.list()
        XCTAssertEqual(root.map(\.name), ["Nested", "hello.txt"])
        let nested = try await client.list(id: "folder-id")
        XCTAssertTrue(nested.isEmpty)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("résumé.bin")
        let bytes = Data((0..<200_003).map { UInt8($0 % 251) })
        try bytes.write(to: source)
        var uploadProgress: Int64 = 0
        try await client.upload(source, directoryID: "") { done, _ in uploadProgress = done }
        XCTAssertEqual(uploadProgress, Int64(bytes.count))
        let afterUpload = try await client.list()
        let uploaded = try XCTUnwrap(afterUpload.first { $0.name == "résumé.bin" })
        let destination = directory.appendingPathComponent("download.bin")
        var downloadProgress: Int64 = 0
        try await client.download(uploaded, to: destination) { done, _ in downloadProgress = done }
        XCTAssertEqual(try Data(contentsOf: destination), bytes)
        XCTAssertEqual(downloadProgress, Int64(bytes.count))
        do {
            try await client.upload(source, directoryID: "") { _, _ in }
            XCTFail("Collision must fail")
        } catch { XCTAssertTrue(error.localizedDescription.contains("exists")) }
        do {
            try await client.download(uploaded, to: destination) { _, _ in }
            XCTFail("Existing Mac destination must not be overwritten")
        } catch { XCTAssertEqual(try Data(contentsOf: destination), bytes) }
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: directory.path).contains { $0.hasSuffix(".part") })
        for badPin in [false, true] {
            let rejected = BridgeClient(testHost: "127.0.0.1", port: port,
                fingerprint: badPin ? Data(repeating: 0, count: 32) : fingerprint,
                secret: badPin ? secret : Data(repeating: 0, count: 32))
            do { _ = try await rejected.list(); XCTFail("Bad credentials must fail") }
            catch { /* Pin rejection or authenticated protocol rejection is expected. */ }
        }
        do { _ = try await client.list(id: "../../etc"); XCTFail("Traversal ID must fail") }
        catch { }
    }
}
