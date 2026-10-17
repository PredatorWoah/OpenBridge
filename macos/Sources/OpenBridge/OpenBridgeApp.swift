import SwiftUI
import AppKit
import BridgeCore

@MainActor
final class BrowserModel: ObservableObject {
    @Published var host = ""
    @Published var port = ""
    /// The 6-digit code shown while the phone asks its user to confirm the pairing.
    @Published var pairingCode: String?
    @Published var entries: [Entry] = []
    @Published var path: [(id: String, name: String)] = [("", "Phone")]
    @Published var connected = false
    @Published var busy = false
    @Published var status = "Start sharing on Android, then enter the IP and port shown on the phone."
    @Published var error: String?
    @Published var fraction: Double = 0
    @Published var connectionLabel = ""
    private var client: (any FileSource)?
    private var task: Task<Void, Never>?

    /// Whether the current (or attempted) connection is USB File transfer, which has no Wi-Fi pairing.
    private var usb = false

    private func run(_ work: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil; fraction = 0
        task = Task {
            defer { busy = false; task = nil; pairingCode = nil }
            do { try await work() }
            catch is CancellationError { status = "Operation cancelled. Refresh to check the phone's folder." }
            catch {
                self.error = error.localizedDescription
                status = usb
                    ? "Couldn't reach the phone over USB. Keep it unlocked, plugged in, and in File transfer mode."
                    : "Operation failed. Check Wi-Fi and that sharing is still on, or pair again."
            }
        }
    }

    func connect() {
        usb = false
        run {
            self.status = "Connecting to the phone…"
            let paired = try await BridgeClient.pair(host: self.host.trimmingCharacters(in: .whitespacesAndNewlines), port: self.port) { code in
                self.pairingCode = code
                self.status = "Check that your phone shows the same code, then tap Pair on the phone."
            }
            self.pairingCode = nil
            let entries = try await paired.list()
            self.client = paired; self.connected = true
            self.path = [("", "Phone")]; self.entries = entries
            self.connectionLabel = "\(self.host):\(self.port) on the local network"
            self.status = "Paired. Both screens showed the same code."
        }
    }

    /// USB cable in File transfer (MTP) mode: no app or pairing needed on the phone.
    func connectUSB() {
        usb = true
        run {
            self.status = "Looking for a phone on USB…"
            let usb = try await MTPClient.connectUSB()
            let storages = try await usb.list(id: "")
            self.client = usb; self.connected = true
            self.path = [("", usb.name)]; self.entries = storages
            self.connectionLabel = "\(usb.name) over USB"
            self.status = "Connected over USB. Open a storage to browse."
        }
    }

    func disconnect() {
        guard !busy else { return }
        if let old = client { Task { await old.disconnect() } }
        client = nil; connected = false; entries = []; path = [("", "Phone")]; connectionLabel = ""
        error = nil
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
                Text("Wi-Fi (with the OpenBridge Android app)").font(.headline)
                Text("Put both devices on the same network (or plug in the phone with USB tethering on), start sharing in the Android app, and enter the IP and port it shows.")
                HStack {
                    TextField("Phone IPv4 address", text: $model.host)
                    TextField("Port", text: $model.port).frame(width: 90)
                }
                Button("Pair", action: model.connect).disabled(model.busy || model.host.isEmpty || model.port.isEmpty)
                if let code = model.pairingCode {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Pairing code").font(.caption).foregroundStyle(.secondary)
                        Text("\(code.prefix(3)) \(code.suffix(3))")
                            .font(.system(size: 44, weight: .semibold, design: .monospaced))
                            .textSelection(.enabled)
                        Text("Tap Pair on the phone only if it shows the same code.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Divider().padding(.vertical, 6)
                Text("USB cable (beta)").font(.headline)
                Text("Plug in your phone, unlock it, and choose File transfer in its USB notification. No pairing and nothing to install on the phone.")
                Button("Connect over USB", action: model.connectUSB).disabled(model.busy)
            } else {
                HStack {
                    Text(model.connectionLabel)
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
            Text(model.connectionLabel.hasSuffix("over USB") ? "Keep the phone unlocked and plugged in during transfers." : "Sharing stops when you leave the Android app. Restarting it requires new pairing credentials.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(minWidth: 700, minHeight: 540)
    }
}

/// Keeps OpenBridge a normal foreground app: it activates on launch, and clicking its Dock icon
/// brings the window back even when it was hidden, minimized or behind other apps.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        NSApp.unhide(nil)
        for window in sender.windows where window.canBecomeMain {
            if window.isMiniaturized { window.deminiaturize(nil) }
            window.makeKeyAndOrderFront(nil)
        }
        NSApp.activate(ignoringOtherApps: true)
        return true   // with no window left, SwiftUI opens a new one
    }
}

@main
struct OpenBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup { BrowserView() }
    }
}
