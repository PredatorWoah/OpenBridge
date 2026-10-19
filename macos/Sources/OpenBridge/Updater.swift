import SwiftUI
import AppKit
import CryptoKit

/// Installs a newer release when the user asks: downloads the disk image and SHA256SUMS.txt from the GitHub release
/// over HTTPS, refuses anything whose SHA-256 doesn't match, copies OpenBridge.app over this copy and reopens it.
/// When this copy's folder isn't writable, it opens the disk image in Finder to drag over instead. Any failure opens
/// the release page.
@MainActor
final class Updater: ObservableObject {
    enum Phase: Equatable {
        case idle
        case downloading(Double)
        case verifying
        case installing
        /// The new version is open in Finder, to drag to Applications.
        case dragToApplications
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle

    var working: Bool {
        switch phase {
        case .downloading, .verifying, .installing: return true
        case .idle, .dragToApplications, .failed: return false
        }
    }

    private static let releases = "https://github.com/PredatorWoah/OpenBridge/releases/download/"

    func install(_ update: AvailableUpdate) {
        guard !working else { return }
        Task { await perform(update) }
    }

    private func perform(_ update: AvailableUpdate) async {
        let version = update.version
        let image = "OpenBridge-\(version)-macOS.dmg"
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("OpenBridge Update \(UUID().uuidString)", isDirectory: true)
        let mount = work.appendingPathComponent("mount", isDirectory: true)
        var mounted = false, keep = false
        defer {
            if mounted && !keep { Task.detached { _ = try? await Updater.tool("/usr/bin/hdiutil", ["detach", mount.path, "-force", "-quiet"]) } }
            if !keep { try? FileManager.default.removeItem(at: work) }
        }
        do {
            guard let current = Updater.runningApp() else { throw UpdateError("This copy of OpenBridge isn't an app, so it can't replace itself.") }
            guard let base = URL(string: Updater.releases + "v\(version)/"),
                  let sumsURL = URL(string: "SHA256SUMS.txt", relativeTo: base), let imageURL = URL(string: image, relativeTo: base) else {
                throw UpdateError("Unexpected version \(version).")
            }
            try FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
            Diagnostics.log("Update \(version): downloading")
            phase = .downloading(0)
            let sums = try await Updater.text(from: sumsURL.absoluteURL)
            guard let expected = Updater.checksum(for: image, in: sums) else { throw UpdateError("The release lists no checksum for the Mac app.") }
            let downloaded = work.appendingPathComponent(image)
            try await Download.file(from: imageURL.absoluteURL, to: downloaded) { fraction in
                Task { @MainActor [weak self] in
                    guard let self, case .downloading = self.phase else { return }
                    self.phase = .downloading(fraction)
                }
            }
            phase = .verifying
            guard try await Updater.sha256(of: downloaded) == expected else {
                throw UpdateError("The download didn't match its published checksum, so it wasn't installed.")
            }
            Diagnostics.log("Update \(version): checksum matches")
            phase = .installing
            try await Updater.tool("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path, downloaded.path])
            mounted = true
            let source = mount.appendingPathComponent("OpenBridge.app", isDirectory: true)
            let info = NSDictionary(contentsOf: source.appendingPathComponent("Contents/Info.plist"))
            guard info?["CFBundleIdentifier"] as? String == Bundle.main.bundleIdentifier,
                  info?["CFBundleShortVersionString"] as? String == version else {
                throw UpdateError("The disk image doesn't hold OpenBridge \(version).")
            }
            let folder = current.deletingLastPathComponent()
            guard FileManager.default.isWritableFile(atPath: folder.path) else {
                // Installed where this user can't write (or run from the disk image): let Finder ask for permission.
                Diagnostics.log("Update \(version): the app's folder isn't writable; opened the disk image")
                keep = true
                NSWorkspace.shared.open(mount)
                phase = .dragToApplications
                return
            }
            let staged = folder.appendingPathComponent(".OpenBridge-\(version)-\(UUID().uuidString).app", isDirectory: true)
            do {
                try await Updater.tool("/usr/bin/ditto", [source.path, staged.path])
                try Updater.replace(current, with: staged)
            } catch {
                try? FileManager.default.removeItem(at: staged)
                throw error
            }
            Diagnostics.log("Update \(version): installed, reopening")
            Updater.relaunch(current)
        } catch {
            Diagnostics.log("Update \(version) failed: \(error.localizedDescription)")
            phase = .failed(error.localizedDescription)
            NSWorkspace.shared.open(update.page)
        }
    }

    /// The running app bundle, if this is one.
    private static func runningApp() -> URL? {
        let url = Bundle.main.bundleURL
        return url.pathExtension == "app" ? url : nil
    }

    /// The SHA-256 listed for `file` in a SHA256SUMS.txt (lines of "<hex>  <name>").
    static func checksum(for file: String, in sums: String) -> String? {
        for line in sums.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: " ", omittingEmptySubsequences: true)
            guard parts.count == 2 else { continue }
            let name = parts[1].hasPrefix("*") ? String(parts[1].dropFirst()) : String(parts[1])
            let hash = parts[0].lowercased()
            if name == file, hash.count == 64, hash.allSatisfy(\.isHexDigit) { return hash }
        }
        return nil
    }

    private static func text(from url: URL) async throws -> String {
        let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30))
        guard (response as? HTTPURLResponse)?.statusCode == 200, response.url?.scheme == "https", data.count < 1 << 16 else {
            throw UpdateError("Couldn't get the release's checksums.")
        }
        return String(decoding: data, as: UTF8.self)
    }

    private static func sha256(of url: URL) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let handle = try FileHandle(forReadingFrom: url)
                    defer { try? handle.close() }
                    var hasher = SHA256()
                    while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
                    continuation.resume(returning: hasher.finalize().map { String(format: "%02x", $0) }.joined())
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    /// Runs a system tool and returns its output; a non-zero exit is an error.
    @discardableResult
    nonisolated static func tool(_ path: String, _ arguments: [String]) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: path)
                process.arguments = arguments
                let output = Pipe()
                process.standardOutput = output
                process.standardError = output
                do { try process.run() } catch { continuation.resume(throwing: error); return }
                let data = output.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                let text = String(decoding: data, as: UTF8.self)
                if process.terminationStatus == 0 { continuation.resume(returning: text) }
                else { continuation.resume(throwing: UpdateError("\(URL(fileURLWithPath: path).lastPathComponent) failed: \(text.prefix(300))")) }
            }
        }
    }

    /// Swaps the new copy in: two renames in the same folder, and the old copy goes (a running app can be removed).
    private static func replace(_ current: URL, with staged: URL) throws {
        let manager = FileManager.default
        let old = current.deletingLastPathComponent().appendingPathComponent(".OpenBridge-old-\(UUID().uuidString).app", isDirectory: true)
        try manager.moveItem(at: current, to: old)
        do { try manager.moveItem(at: staged, to: current) } catch {
            try? manager.moveItem(at: old, to: current)
            throw error
        }
        try? manager.removeItem(at: old)
    }

    /// Opens the new copy once this one has quit.
    private static func relaunch(_ app: URL) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        // $0 is the app, $1 this process: wait (at most ten seconds) for it to end, then open the new copy.
        process.arguments = ["-c", "n=0; while kill -0 \"$1\" 2>/dev/null && [ $n -lt 50 ]; do sleep 0.2; n=$((n+1)); done; sleep 1; /usr/bin/open \"$0\"",
                             app.path, String(ProcessInfo.processInfo.processIdentifier)]
        do { try process.run() } catch { return }
        NSApp.terminate(nil)
    }
}

struct UpdateError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// One HTTPS download to a file, with progress. Redirects are followed only to HTTPS (GitHub sends assets on to its
/// download host).
private final class Download: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destination: URL
    private let progress: (Double) -> Void
    private var continuation: CheckedContinuation<Void, Error>?
    private var failure: Error?

    private init(destination: URL, progress: @escaping (Double) -> Void) {
        self.destination = destination; self.progress = progress
    }

    static func file(from url: URL, to destination: URL, progress: @escaping (Double) -> Void) async throws {
        let download = Download(destination: destination, progress: progress)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 15 * 60
        let session = URLSession(configuration: configuration, delegate: download, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            download.continuation = continuation
            session.downloadTask(with: url).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        progress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file at `location` is gone once this returns: move it now.
        guard (downloadTask.response as? HTTPURLResponse)?.statusCode == 200 else {
            failure = UpdateError("The download failed (the release may not have a Mac app)."); return
        }
        do { try FileManager.default.moveItem(at: location, to: destination) } catch { failure = error }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url?.scheme == "https" ? request : nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let result = error ?? failure
        let continuation = self.continuation
        self.continuation = nil
        if let result { continuation?.resume(throwing: result) } else { continuation?.resume() }
    }
}

/// The amber pill at the top of the window when a newer version is out: Update, What's new, and progress.
struct UpdatePill: View {
    let update: AvailableUpdate
    @ObservedObject var updater: Updater
    /// A transfer is running: updating would cut it off.
    var busy: Bool

    var body: some View {
        HStack(spacing: 10) {
            switch updater.phase {
            case .idle, .failed:
                Button("Update to \(update.version)") { updater.install(update) }
                    .buttonStyle(.plain).disabled(busy)
                    .help(busy ? "Wait for the transfer to finish" : "Download, check and install it, then reopen OpenBridge")
                Link("What's new", destination: update.page)
            case .downloading(let fraction):
                ProgressView(value: fraction).frame(width: 60).tint(Palette.night)
                Text("Downloading \(Int(fraction * 100))%").monospacedDigit()
            case .verifying:
                Text("Checking the download…")
            case .installing:
                Text("Installing…")
            case .dragToApplications:
                Text("Drag OpenBridge to Applications")
            }
        }
        .font(.system(size: 12, weight: .bold)).foregroundStyle(Palette.night)
        .padding(.horizontal, 12).padding(.vertical, 5)
        .background(Palette.amber, in: Capsule())
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Update available: OpenBridge \(update.version)")
    }
}
