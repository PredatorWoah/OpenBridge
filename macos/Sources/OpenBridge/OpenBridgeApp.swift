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
    @Published var status = ""
    @Published var error: String?
    @Published var deviceName = "Phone"
    @Published var overUSB = false
    /// Whether the file browser is showing (instead of the connect screen).
    @Published var showBrowser = false
    @Published var selection: Entry.ID?
    @Published var transfer: Transfer?
    private var client: (any FileSource)?
    private var task: Task<Void, Never>?
    private var watcher: Task<Void, Never>?
    /// A plugged-in phone that is not opened automatically: it failed to connect, or the user disconnected it.
    /// Cleared when that phone is unplugged.
    private var skipped: String?

    struct Transfer {
        var name: String
        var upload: Bool
        var fraction = 0.0
        var done = false
        var saved: URL?
    }

    private func run(_ work: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        task = Task {
            defer { busy = false; task = nil; pairingCode = nil }
            do { try await work() }
            catch is CancellationError { status = "Cancelled."; transfer = nil }
            catch {
                self.error = error.localizedDescription
                status = overUSB
                    ? "Couldn't reach the phone over USB. Keep it unlocked, plugged in and in File transfer mode."
                    : "Couldn't reach the phone. Check Wi‑Fi and that sharing is still on, or pair again."
                if transfer?.done == false { transfer = nil }
            }
        }
    }

    // MARK: Connecting

    /// Watches for a phone being plugged in or unplugged, so USB needs no button.
    func startWatching() {
        guard watcher == nil else { return }
        // The model lives as long as the window, so the loop holds it strongly.
        watcher = Task {
            while !Task.isCancelled {
                poll()
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        }
    }

    private func poll() {
        guard !busy else { return }
        let attached = MTPClient.attachedPhone()
        if attached == nil { skipped = nil }
        if connected {
            if overUSB && attached == nil { drop("The phone was unplugged.") }
            return
        }
        if let attached, attached != skipped { connectUSB() }
    }

    /// USB cable in File transfer (MTP) mode: no app or pairing needed on the phone.
    func connectUSB() {
        overUSB = true
        let attached = MTPClient.attachedPhone()
        run {
            self.status = "Opening File transfer…"
            do {
                let usb = try await MTPClient.connectUSB()
                let storages = try await usb.list(id: "")
                self.client = usb; self.deviceName = usb.name
                self.path = [("", usb.name)]; self.entries = storages
                self.connected = true; self.status = ""
            } catch {
                self.skipped = attached   // don't retry in a loop; replugging or Try again does
                throw error
            }
        }
    }

    func retryUSB() { skipped = nil; error = nil; connectUSB() }

    func pair() {
        overUSB = false
        run {
            self.status = "Connecting to the phone…"
            let paired = try await BridgeClient.pair(host: self.host.trimmingCharacters(in: .whitespacesAndNewlines), port: self.port) { code in
                self.pairingCode = code
                self.status = "Check that your phone shows the same code, then tap Pair on the phone."
            }
            self.pairingCode = nil
            let entries = try await paired.list()
            self.client = paired; self.deviceName = "Phone at \(self.host)"
            self.path = [("", "Phone")]; self.entries = entries
            self.connected = true; self.status = ""
        }
    }

    func disconnect() {
        guard !busy else { return }
        if overUSB { skipped = MTPClient.attachedPhone() }
        drop("")
    }

    private func drop(_ reason: String) {
        if let old = client { Task { await old.disconnect() } }
        client = nil; connected = false; showBrowser = false; entries = []; path = [("", "Phone")]
        selection = nil; transfer = nil; error = nil; status = reason
    }

    func cancel() { task?.cancel() }

    // MARK: Browsing

    func refresh() {
        guard let client else { return }
        run { self.entries = try await client.list(id: self.path.last!.id) }
    }

    func open(_ entry: Entry) {
        if entry.directory { enter(entry) } else { download(entry) }
    }

    func enter(_ entry: Entry) {
        guard entry.directory, let client else { return }
        run {
            let entries = try await client.list(id: entry.id)
            self.path.append((entry.id, entry.name)); self.entries = entries; self.selection = nil
        }
    }

    func up() {
        guard path.count > 1, let client else { return }
        run {
            let parent = self.path[self.path.count - 2]
            let entries = try await client.list(id: parent.id)
            self.path.removeLast(); self.entries = entries; self.selection = nil
        }
    }

    /// Opens a well-known folder such as DCIM/Camera. Tries each spelling in turn; phones differ.
    func jump(_ label: String, to candidates: [[String]]) {
        guard let client else { return }
        run {
            for names in candidates {
                if let (trail, entries) = try await self.resolve(names, client) {
                    self.path = trail; self.entries = entries; self.selection = nil; self.showBrowser = true
                    return
                }
            }
            self.status = "This phone has no \(label) folder."
        }
    }

    private func resolve(_ names: [String], _ client: any FileSource) async throws -> ([(id: String, name: String)], [Entry])? {
        func find(_ name: String, in entries: [Entry]) -> Entry? {
            entries.first { $0.directory && $0.name.caseInsensitiveCompare(name) == .orderedSame }
        }
        var trail = [path[0]]
        var entries = try await client.list(id: "")
        // USB and multi-storage phones list storage areas first: look inside the first one.
        if let first = names.first, find(first, in: entries) == nil, let storage = entries.first(where: \.directory) {
            entries = try await client.list(id: storage.id); trail.append((storage.id, storage.name))
        }
        for name in names {
            guard let next = find(name, in: entries) else { return nil }
            entries = try await client.list(id: next.id); trail.append((next.id, next.name))
        }
        return (trail, entries)
    }

    // MARK: Transfers

    var selectedFile: Entry? { entries.first { $0.id == selection && !$0.directory } }

    func download(_ entry: Entry) {
        guard let client, !entry.directory, !busy else { return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = entry.name
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        panel.message = "Choose where to save. OpenBridge never replaces existing files."
        guard panel.runModal() == .OK, let target = panel.url else { return }
        transfer = Transfer(name: entry.name, upload: false)
        run {
            try await client.download(entry, to: target) { done, total in self.update(done, total) }
            self.transfer?.fraction = 1; self.transfer?.done = true; self.transfer?.saved = target
        }
    }

    func upload() {
        guard let client, !busy else { return }
        let panel = NSOpenPanel()
        panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let source = panel.url else { return }
        transfer = Transfer(name: source.lastPathComponent, upload: true)
        run {
            try await client.upload(source, directoryID: self.path.last!.id) { done, total in self.update(done, total) }
            self.transfer?.fraction = 1; self.transfer?.done = true
            // Publication succeeded even if a subsequent folder refresh fails.
            do { self.entries = try await client.list(id: self.path.last!.id) }
            catch { self.error = "Uploaded, but the folder didn't refresh: \(error.localizedDescription)" }
        }
    }

    private func update(_ done: Int64, _ total: Int64) {
        transfer?.fraction = total == 0 ? 0 : Double(done) / Double(total)
    }
}

// MARK: - Styles

struct Glass: ViewModifier {
    var radius: CGFloat = 18
    var border: Color = .white.opacity(0.14)
    func body(content: Content) -> some View {
        content
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(border))
    }
}

extension View {
    func glass(_ radius: CGFloat = 18, border: Color = .white.opacity(0.14)) -> some View { modifier(Glass(radius: radius, border: border)) }
}

struct SunsetButton: ButtonStyle {
    var wide = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(.white)
            .frame(maxWidth: wide ? .infinity : nil)
            .padding(.horizontal, 18).padding(.vertical, wide ? 13 : 8)
            .background(Palette.sunset, in: Capsule())
            .overlay(Capsule().strokeBorder(LinearGradient(colors: [.white.opacity(0.45), .clear], startPoint: .top, endPoint: .bottom)))
            .shadow(color: Palette.pink.opacity(0.35), radius: 10, y: 5)
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

struct GhostButton: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .background(Color.white.opacity(configuration.isPressed ? 0.16 : 0.08), in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.14)))
            .opacity(enabled ? 1 : 0.4)
    }
}

/// A slow glow swell every 3.2 s, in step with the sun's breathing.
struct Breathing: ViewModifier {
    @State private var on = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .shadow(color: Palette.amber.opacity(on ? 0.7 : 0.25), radius: on ? 22 : 10)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true)) { on = true }
            }
    }
}

// MARK: - Screens

struct RootView: View {
    @StateObject private var model = BrowserModel()
    @State private var wifi = false
    @AppStorage("openAutomatically") private var openAutomatically = false

    private var browsing: Bool { model.showBrowser && model.connected }

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            if browsing {
                BrowserScreen(model: model)
                    .transition(.asymmetric(insertion: .scale(scale: 1.04).combined(with: .opacity), removal: .opacity))
            } else {
                ConnectScreen(model: model, wifi: $wifi, openAutomatically: $openAutomatically)
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.55, dampingFraction: 0.82), value: browsing)
        .frame(minWidth: 780, minHeight: 580)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $wifi) { WiFiSheet(model: model, isPresented: $wifi) }
        .onAppear { model.startWatching() }
        .onChange(of: model.connected) { connected in
            guard connected else { return }
            wifi = false
            if openAutomatically {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.1) { if model.connected { model.showBrowser = true } }
            }
        }
    }
}

struct ConnectScreen: View {
    @ObservedObject var model: BrowserModel
    @Binding var wifi: Bool
    @Binding var openAutomatically: Bool

    private var title: String {
        if model.connected { return model.overUSB ? "Connected over USB" : "Connected over Wi‑Fi" }
        if model.busy { return model.overUSB ? "Phone found" : "Connecting…" }
        return "Waiting for your phone"
    }
    private var subtitle: String {
        if model.connected { return "Your phone is ready." }
        if !model.status.isEmpty { return model.status }
        return "Plug it in, unlock it and choose File transfer."
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                SunArch(active: true, connected: model.connected, centerY: 0.27, radius: 62, startScanning: true)
                    .allowsHitTesting(false)
                    .ignoresSafeArea()
                VStack(spacing: 6) {
                    Spacer().frame(height: geometry.size.height * 0.27 + 104)
                    Text(title).font(.system(size: 24, weight: .bold, design: .rounded))
                    Text(subtitle).foregroundStyle(Palette.muted).multilineTextAlignment(.center).textSelection(.enabled)
                    if model.connected {
                        DeviceCard(model: model, openAutomatically: $openAutomatically)
                            .padding(.top, 16)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else if let error = model.error {
                        ErrorCard(message: error, retry: { if model.overUSB { model.retryUSB() } else { wifi = true } })
                            .padding(.top, 16)
                            .transition(.opacity)
                    }
                    Spacer()
                    HStack(spacing: 6) {
                        Text("No cable?").foregroundStyle(Palette.muted)
                        Button("Pair over Wi‑Fi") { wifi = true }.buttonStyle(.link).disabled(model.busy || model.connected)
                    }
                    .font(.system(size: 13))
                    .padding(.bottom, 22)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
                .animation(.spring(response: 0.6, dampingFraction: 0.68), value: model.connected)
            }
        }
    }
}

struct DeviceCard: View {
    @ObservedObject var model: BrowserModel
    @Binding var openAutomatically: Bool
    private struct Shortcut { let label: String; let candidates: [[String]] }
    private let shortcuts = [
        Shortcut(label: "Camera", candidates: [["DCIM", "Camera"]]),
        Shortcut(label: "Download", candidates: [["Download"], ["Downloads"]]),
        Shortcut(label: "Screenshots", candidates: [["Pictures", "Screenshots"], ["DCIM", "Screenshots"]]),
        Shortcut(label: "WhatsApp", candidates: [["Android", "media", "com.whatsapp", "WhatsApp", "Media"], ["WhatsApp", "Media"]]),
        Shortcut(label: "Documents", candidates: [["Documents"]])
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Image(systemName: "iphone")
                    .font(.system(size: 24, weight: .medium))
                    .frame(width: 52, height: 52)
                    .background(LinearGradient(colors: [Palette.violet, Palette.pink], startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.deviceName).font(.system(size: 18, weight: .bold, design: .rounded)).lineLimit(1)
                    HStack(spacing: 6) {
                        Circle().fill(Palette.ok).frame(width: 7, height: 7).shadow(color: Palette.ok, radius: 4)
                        Text(model.overUSB ? "USB · File transfer" : "Wi‑Fi · \(model.host)").foregroundStyle(Palette.muted)
                    }.font(.system(size: 12.5))
                }
                Spacer()
                Button("Disconnect", action: model.disconnect).buttonStyle(GhostButton()).disabled(model.busy)
            }
            HStack(spacing: 8) {
                ForEach(shortcuts, id: \.label) { shortcut in
                    Button(shortcut.label) { model.jump(shortcut.label, to: shortcut.candidates) }.buttonStyle(GhostButton()).disabled(model.busy)
                }
            }
            Button { model.showBrowser = true } label: { Text("Open phone  →") }
                .buttonStyle(SunsetButton(wide: true))
                .modifier(Breathing())
                .keyboardShortcut(.defaultAction)
            Toggle("Open automatically when I plug in", isOn: $openAutomatically)
                .toggleStyle(.checkbox).font(.system(size: 12.5)).foregroundStyle(Palette.muted)
                .frame(maxWidth: .infinity)
        }
        .padding(16)
        .frame(width: 460)
        .glass(20, border: Palette.ok.opacity(0.45))
        .shadow(color: .black.opacity(0.35), radius: 24, y: 14)
    }
}

struct ErrorCard: View {
    var message: String
    var retry: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.amber)
            Text(message).font(.system(size: 13)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            Button("Try again", action: retry).buttonStyle(GhostButton())
        }
        .padding(14)
        .frame(width: 460)
        .glass(16, border: Palette.amber.opacity(0.4))
    }
}

struct BrowserScreen: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text("DEVICES").font(.system(size: 11, weight: .semibold)).tracking(1.4).foregroundStyle(Palette.muted)
                    .padding(.horizontal, 8).padding(.bottom, 4)
                HStack(spacing: 10) {
                    ArchMark(size: 26)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.deviceName).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                        Text(model.overUSB ? "USB · File transfer" : "Wi‑Fi").font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.75))
                    }
                }
                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                .background(LinearGradient(colors: [Palette.violet.opacity(0.45), Palette.pink.opacity(0.3)], startPoint: .leading, endPoint: .trailing),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Button { model.showBrowser = false } label: { Label("Devices", systemImage: "chevron.left") }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted).font(.system(size: 12.5)).padding(10)
                Spacer()
                Button("Disconnect", action: model.disconnect).buttonStyle(GhostButton()).disabled(model.busy).padding(8)
            }
            .padding(.top, 44).padding(.horizontal, 10).padding(.bottom, 10)
            .frame(width: 220)
            .background(Color.white.opacity(0.04))
            .overlay(alignment: .trailing) { Rectangle().fill(.white.opacity(0.08)).frame(width: 1) }

            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Button(action: model.up) { Image(systemName: "chevron.left") }
                        .buttonStyle(GhostButton()).disabled(model.path.count < 2 || model.busy).help("Up one folder")
                    Text(model.path.map(\.name).joined(separator: "  ›  "))
                        .font(.system(size: 14, weight: .bold)).lineLimit(1).truncationMode(.head)
                    Spacer()
                    Button(action: model.refresh) { Image(systemName: "arrow.clockwise") }
                        .buttonStyle(GhostButton()).disabled(model.busy).help("Refresh")
                    Button("Upload…", action: model.upload).buttonStyle(GhostButton()).disabled(model.busy)
                    Button("Download") { if let file = model.selectedFile { model.download(file) } }
                        .buttonStyle(SunsetButton()).disabled(model.busy || model.selectedFile == nil)
                }
                .padding(.horizontal, 16).padding(.top, 40).padding(.bottom, 10)
                .overlay(alignment: .bottom) { Rectangle().fill(.white.opacity(0.08)).frame(height: 1) }

                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(model.entries) { entry in
                            FileRow(entry: entry, selected: model.selection == entry.id)
                                .onTapGesture(count: 2) { model.open(entry) }
                                .onTapGesture { model.selection = entry.id }
                        }
                    }
                    .padding(10)
                }
                // Each folder fades in as a whole, quickly; rows never wait on their own timers.
                .id(model.path.last?.id ?? "")
                .transition(.opacity.combined(with: .offset(y: 6)))
                .animation(.easeOut(duration: 0.15), value: model.path.last?.id)
                if model.entries.isEmpty && !model.busy {
                    Text("This folder is empty.").foregroundStyle(Palette.muted).frame(maxHeight: .infinity)
                }
                if let error = model.error {
                    Text(error).font(.system(size: 12.5)).foregroundStyle(Palette.amber).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 8)
                }
                if model.transfer != nil {
                    TransferBar(model: model).padding(.horizontal, 16).padding(.bottom, 16)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.5, dampingFraction: 0.75), value: model.transfer != nil)
        }
    }
}

struct FileRow: View {
    var entry: Entry
    var selected: Bool

    private var kind: (symbol: String, color: Color) {
        if entry.directory { return ("folder.fill", Palette.amber) }
        switch (entry.name as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng": return ("photo.fill", Palette.violet)
        case "mp4", "mov", "mkv", "3gp", "webm": return ("play.rectangle.fill", Palette.pink)
        case "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus": return ("music.note", Palette.sunGold)
        case "apk": return ("shippingbox.fill", Palette.ok)
        case "zip", "rar", "7z": return ("doc.zipper", Palette.muted)
        default: return ("doc.fill", Palette.muted)
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: kind.symbol).foregroundStyle(kind.color).frame(width: 22)
            Text(entry.name).lineLimit(1).truncationMode(.middle)
            Spacer()
            Text(entry.directory ? "—" : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .foregroundStyle(Palette.muted).monospacedDigit()
        }
        .font(.system(size: 13.5))
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background {
            if selected {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(LinearGradient(colors: [Palette.violet.opacity(0.55), Palette.pink.opacity(0.35)], startPoint: .leading, endPoint: .trailing))
            }
        }
        .contentShape(Rectangle())
    }
}

/// Transfer progress in the sunset gradient, with a little sun at its tip.
struct TransferBar: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        if let transfer = model.transfer {
            VStack(alignment: .leading, spacing: 9) {
                HStack {
                    Image(systemName: transfer.upload ? "arrow.up.circle.fill" : "arrow.down.circle.fill").foregroundStyle(Palette.amber)
                    Text(transfer.name).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    if transfer.done {
                        if let saved = transfer.saved {
                            Text("Saved to \(saved.deletingLastPathComponent().lastPathComponent)").foregroundStyle(Palette.ok).bold()
                            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([saved]) }.buttonStyle(.link)
                        } else {
                            Text("Uploaded to the phone").foregroundStyle(Palette.ok).bold()
                        }
                        Button { model.transfer = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                    } else {
                        Text("\(Int(transfer.fraction * 100))%").monospacedDigit().foregroundStyle(Palette.muted)
                        Button("Cancel", action: model.cancel).buttonStyle(GhostButton())
                    }
                }
                .font(.system(size: 13))
                GeometryReader { geometry in
                    let width = geometry.size.width * transfer.fraction
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.1))
                        Capsule().fill(Palette.bar).frame(width: max(10, width))
                            .overlay(Capsule().strokeBorder(LinearGradient(colors: [.white.opacity(0.5), .clear], startPoint: .top, endPoint: .bottom)))
                            .shadow(color: Palette.pink.opacity(0.55), radius: 7)
                        if !transfer.done {
                            Circle()
                                .fill(RadialGradient(colors: [Color(red: 1, green: 0.99, blue: 0.94), Palette.sunGold, Palette.amber],
                                                     center: UnitPoint(x: 0.35, y: 0.3), startRadius: 0, endRadius: 9))
                                .frame(width: 16, height: 16)
                                .shadow(color: Palette.amber.opacity(0.9), radius: 7)
                                .offset(x: max(0, width - 8))
                        }
                    }
                    .animation(.easeOut(duration: 0.25), value: transfer.fraction)
                }
                .frame(height: 16)
            }
            .padding(14)
            .glass(14)
        }
    }
}

struct WiFiSheet: View {
    @ObservedObject var model: BrowserModel
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ArchMark(size: 34)
                Text("Pair over Wi‑Fi").font(.system(size: 20, weight: .bold, design: .rounded))
            }
            Text("Tap the sun in the OpenBridge Android app, then type the address it shows. Both devices need to be on the same Wi‑Fi.")
                .foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            HStack {
                TextField("Phone address, like 192.168.1.23", text: $model.host)
                TextField("Port", text: $model.port).frame(width: 90)
            }
            .textFieldStyle(.roundedBorder)
            if let code = model.pairingCode {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Codes match? Tap Pair on the phone.").font(.caption).foregroundStyle(Palette.muted)
                    HStack(spacing: 6) {
                        ForEach(Array(code.enumerated()), id: \.offset) { index, digit in
                            Text(String(digit))
                                .font(.system(size: 30, weight: .bold, design: .rounded))
                                .frame(width: 42, height: 54)
                                .glass(12)
                                .padding(.leading, index == 3 ? 10 : 0)
                        }
                    }
                    .textSelection(.enabled)
                }
            } else if !model.status.isEmpty && model.busy {
                Text(model.status).foregroundStyle(Palette.muted)
            }
            if let error = model.error, !model.overUSB {
                Text(error).foregroundStyle(Palette.amber).textSelection(.enabled)
            }
            HStack {
                Button("Cancel") { if model.busy { model.cancel() }; isPresented = false }.buttonStyle(GhostButton())
                Spacer()
                Button("Pair", action: model.pair)
                    .buttonStyle(SunsetButton())
                    .keyboardShortcut(.defaultAction)
                    .disabled(model.busy || model.host.isEmpty || model.port.isEmpty)
            }
        }
        .padding(24)
        .frame(width: 440)
        .background(Palette.background)
        .preferredColorScheme(.dark)
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
        WindowGroup { RootView() }
            .windowStyle(.hiddenTitleBar)
            .defaultSize(width: 900, height: 640)
    }
}
