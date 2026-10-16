import SwiftUI
import AppKit
import BridgeCore

@MainActor
final class BrowserModel: ObservableObject {
    @Published var host = ""
    @Published var port = ""
    @Published var fingerprint = ""
    @Published var secret = ""
    @Published var entries: [Entry] = []
    @Published var path: [(id: String, name: String)] = [("", "Shared folder")]
    @Published var connected = false
    @Published var busy = false
    @Published var status = "Start sharing on Android, then enter the credentials shown on the phone."
    @Published var error: String?
    @Published var fraction: Double = 0
    private var client: BridgeClient?
    private var task: Task<Void, Never>?

    private func run(_ work: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil; fraction = 0
        task = Task {
            defer { busy = false; task = nil }
            do { try await work() }
            catch is CancellationError { status = "Operation cancelled. Refresh to check the phone's folder." }
            catch {
                self.error = error.localizedDescription
                status = "Operation failed. Check Wi-Fi, sharing status, and the fingerprint on the phone."
            }
        }
    }

    func connect() {
        run {
            let candidate = try BridgeClient(host: self.host.trimmingCharacters(in: .whitespacesAndNewlines),
                port: self.port, fingerprint: self.fingerprint, secret: self.secret)
            let entries = try await candidate.list()
            self.client = candidate; self.connected = true
            self.path = [("", "Shared folder")]; self.entries = entries
            self.status = "Paired. Certificate fingerprint matched the value entered from the phone."
        }
    }

    func disconnect() {
        guard !busy else { return }
        client = nil; connected = false; entries = []; path = [("", "Shared folder")]
        secret = ""; fingerprint = ""; error = nil
        status = "Disconnected. Credentials are kept only in memory."
    }

    func refresh() {
        guard let client else { return }
        run { self.entries = try await client.list(id: self.path.last!.id); self.status = "Folder refreshed." }
    }

    func enter(_ entry: Entry) {
        guard entry.directory, let client else { return }
        run {
            let entries = try await client.list(id: entry.id)
            self.path.append((entry.id, entry.name)); self.entries = entries
            self.status = "Folder opened."
        }
    }

    func up() {
        guard path.count > 1, let client else { return }
        run {
            let parent = self.path[self.path.count - 2]
            let entries = try await client.list(id: parent.id)
            self.path.removeLast(); self.entries = entries; self.status = "Folder opened."
        }
    }

    func download(_ entry: Entry) {
        guard let client, !entry.directory, !busy else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = entry.name
        panel.message = "Choose a new filename. OpenBridge refuses to replace existing files."
        guard panel.runModal() == .OK, let target = panel.url else { return }
        run {
            self.status = "Downloading \(entry.name)…"
            try await client.download(entry, to: target) { done, total in self.update(done, total) }
            self.fraction = 1; self.status = "Downloaded \(entry.name)."
        }
    }

    func upload() {
        guard let client, !busy else { return }
        let panel = NSOpenPanel()
        panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let source = panel.url else { return }
        run {
            self.status = "Uploading \(source.lastPathComponent)…"
            try await client.upload(source, directoryID: self.path.last!.id) { done, total in self.update(done, total) }
            self.fraction = 1
            // Publication succeeded even if a subsequent folder refresh fails.
            self.status = "Uploaded \(source.lastPathComponent)."
            do { self.entries = try await client.list(id: self.path.last!.id) }
            catch { self.error = "Upload completed, but refresh failed: \(error.localizedDescription)" }
        }
    }

    private func update(_ done: Int64, _ total: Int64) {
        fraction = total == 0 ? 0 : Double(done) / Double(total)
    }
    func cancel() { task?.cancel() }
}

struct BrowserView: View {
    @StateObject private var model = BrowserModel()
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("OpenBridge").font(.largeTitle)
            if !model.connected {
                Text("Connect both devices to the same trusted local network. Copy the fingerprint directly from your phone before pairing.")
                HStack {
                    TextField("Phone IPv4 address", text: $model.host)
                    TextField("Port", text: $model.port).frame(width: 90)
                }
                TextField("Certificate SHA-256 (64 hex characters)", text: $model.fingerprint)
                SecureField("Pairing secret (64 hex characters)", text: $model.secret)
                Button("Pair and browse", action: model.connect).disabled(model.busy)
            } else {
                HStack {
                    Text("\(model.host):\(model.port)")
                    Spacer()
                    Button("Disconnect", action: model.disconnect).disabled(model.busy)
                }
                HStack {
                    Button("Up", action: model.up).disabled(model.path.count < 2 || model.busy)
                    Button("Refresh", action: model.refresh).disabled(model.busy)
                    Button("Upload file…", action: model.upload).disabled(model.busy)
                    Spacer()
                }
                Text(model.path.map(\.name).joined(separator: " / ")).font(.headline)
                List(model.entries) { entry in
                    HStack {
                        Image(systemName: entry.directory ? "folder" : "doc")
                        Text(entry.name).lineLimit(1)
                        Spacer()
                        if !entry.directory {
                            Text(ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                                .foregroundStyle(.secondary)
                        }
                        Button(entry.directory ? "Open" : "Download…") {
                            if entry.directory { model.enter(entry) } else { model.download(entry) }
                        }.disabled(model.busy)
                    }.padding(.vertical, 3)
                }
            }
            if model.busy {
                ProgressView(value: model.fraction)
                Button("Cancel", action: model.cancel)
            }
            Text(model.status).textSelection(.enabled)
            if let error = model.error { Text(error).foregroundStyle(.red).textSelection(.enabled) }
            Text("Sharing stops when you leave the Android app. Restarting it requires new pairing credentials.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(minWidth: 700, minHeight: 540)
    }
}

@main
struct OpenBridgeApp: App {
    var body: some Scene {
        WindowGroup { BrowserView() }
    }
}
