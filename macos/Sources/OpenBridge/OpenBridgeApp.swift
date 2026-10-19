import SwiftUI
import AppKit
import BridgeCore

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
    @ObservedObject var model: BrowserModel
    @State private var wifi = false
    @AppStorage("openAutomatically") private var openAutomatically = false
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var browsing: Bool { model.showBrowser && model.connected }

    var body: some View {
        ZStack {
            Backdrop(energy: model.connected ? 1 : 0, level: model.transfer.map { $0.done ? 0 : $0.fraction } ?? 0)
                .ignoresSafeArea()
            if browsing {
                BrowserScreen(model: model, mirror: { openWindow(id: "mirror") })
                    .transition(.asymmetric(insertion: .scale(scale: 1.04).combined(with: .opacity), removal: .opacity))
            } else {
                ConnectScreen(model: model, wifi: $wifi, openAutomatically: $openAutomatically)
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.55, dampingFraction: 0.82), value: browsing)
        .frame(minWidth: 780, minHeight: 580)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $wifi) { WiFiSheet(model: model, discovery: model.discovery, isPresented: $wifi) }
        .sheet(isPresented: $model.showTexts) { TextsPanel(model: model, history: model.texts) }
        .overlay(alignment: .topTrailing) {
            if let update = model.update {
                UpdatePill(update: update, updater: model.updater, busy: model.busy || model.transfer.map { !$0.done } == true)
                    .padding(.top, 10).padding(.trailing, 14)
            }
        }
        // Toasts show on every screen: texts from the phone arrive whatever is open.
        .overlay(alignment: .bottom) { Toast(text: model.toast).animation(.easeOut(duration: 0.2), value: model.toast) }
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var title: String {
        if model.connected { return model.fastUSB ? "Connected over Fast USB" : model.overUSB ? "Connected over USB" : "Connected over Wi‑Fi" }
        if model.usbPairable != nil { return "Pair over USB" }
        if model.busy { return model.overUSB ? "Phone found" : "Connecting…" }
        return "Waiting for your phone"
    }
    private var subtitle: String {
        if model.connected { return "Your phone is ready." }
        if !model.status.isEmpty { return model.status }
        if model.usbPairable != nil { return "OpenBridge is sharing over the cable. Pair once, then it connects by itself." }
        return "Plug it in, unlock it and choose File transfer."
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                Hero(active: true, connected: model.connected, centerY: 0.27, radius: 62, startScanning: true, label: title)
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
                    } else {
                        if let phone = model.usbPairable {
                            USBPairingCard(model: model, phone: phone).padding(.top, 16)
                        } else if model.usbNeedsAllow {
                            HintCard(symbol: "cable.connector", text: "USB debugging is on. Allow it for this Mac to use Fast USB: instant search, real folder sizes, resumable copies.",
                                     action: "Use Fast USB", busy: model.busy) { model.connectFastUSB(asking: true) }
                                .padding(.top, 16)
                        }
                        if model.wifiIsolated {
                            HintCard(symbol: "wifi.exclamationmark",
                                     text: "This Wi‑Fi keeps devices apart (common on guest, hotel and campus networks). On the phone, open OpenBridge › Settings › Phone hotspot and join that network from this computer, or use a USB cable.")
                                .padding(.top, 12)
                        }
                        if !model.saved.isEmpty {
                            SavedPhonesCard(model: model, discovery: model.discovery).padding(.top, 16)
                        }
                        if let error = model.error {
                            ErrorCard(message: error, busy: model.busy, retry: { if model.overUSB { model.retryUSB() } else { wifi = true } })
                                .padding(.top, 12)
                                .transition(.opacity)
                        }
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
                .animation(reduceMotion ? nil : .spring(response: 0.6, dampingFraction: 0.68), value: model.connected)
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
                    .accessibilityHidden(true)
                    .background(LinearGradient(colors: [Palette.violet, Palette.pink], startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.deviceName).font(.system(size: 18, weight: .bold, design: .rounded)).lineLimit(1)
                    HStack(spacing: 6) {
                        Circle().fill(Palette.ok).frame(width: 7, height: 7).shadow(color: Palette.ok, radius: 4).accessibilityHidden(true)
                        Text(model.fastUSB ? "Fast USB" : model.overUSB ? "USB · File transfer" : "Wi‑Fi · \(model.host)").foregroundStyle(Palette.muted)
                    }.font(.system(size: 12.5))
                }
                Spacer()
                Button("Disconnect", action: model.disconnect).buttonStyle(GhostButton()).disabled(model.busy)
            }
            // Chips wrap onto another row rather than shortening their names.
            FlowLayout(spacing: 8) {
                ForEach(shortcuts, id: \.label) { shortcut in
                    Button(shortcut.label) { model.jump(shortcut.label, to: shortcut.candidates) }.buttonStyle(GhostButton()).disabled(model.busy)
                }
                Button { model.importNewPhotos() } label: { Label("Import new photos", systemImage: "photo.stack") }
                    .buttonStyle(GhostButton()).disabled(model.busy)
                if model.clipboardAvailable {
                    Button { model.showTexts = true } label: { Label("Texts", systemImage: "text.bubble") }
                        .buttonStyle(GhostButton()).help("Send texts to the phone, and see what it sent")
                }
            }
            if model.overUSB && !model.usbDebugging {
                Label("Turn on USB debugging for Fast USB: instant search, real folder sizes, resumable copies", systemImage: "bolt.horizontal")
                    .font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            } else if model.overUSB && model.usbNeedsAllow {
                HStack(spacing: 8) {
                    Label("Allow USB debugging for Fast USB", systemImage: "bolt.horizontal").foregroundStyle(Palette.muted)
                    Button("Use Fast USB") { model.disconnect(); model.connectFastUSB(asking: true) }.buttonStyle(.link).disabled(model.busy)
                }
                .font(.system(size: 12))
            }
            if model.phoneOutdated {
                Label("Update OpenBridge on the phone to get texts from it and Fast USB.", systemImage: "arrow.down.app")
                    .font(.system(size: 12)).foregroundStyle(Palette.amber).fixedSize(horizontal: false, vertical: true)
            } else if let behind = model.phoneBehind {
                Label("The phone has OpenBridge \(behind). Update it there too, so both work alike.", systemImage: "arrow.down.app")
                    .font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
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

/// Phones paired before: they reconnect by themselves while sharing, or with a click.
struct SavedPhonesCard: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    @State private var forgetting: SavedPhone?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("YOUR PHONES").font(.system(size: 11, weight: .semibold)).tracking(1.4).foregroundStyle(Palette.muted)
            ForEach(model.saved) { phone in
                let here = discovery.phones.contains { $0.phoneID == phone.id }
                HStack(spacing: 10) {
                    Image(systemName: "iphone").foregroundStyle(here ? Palette.ok : .white).frame(width: 22).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(phone.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                        Text(here ? "On this Wi‑Fi now" : "Reconnects by itself while the phone is sharing")
                            .font(.system(size: 11.5)).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Button("Connect") { model.connect(phone) }.buttonStyle(GhostButton()).disabled(model.busy)
                    Button { forgetting = phone } label: { Image(systemName: "xmark") }
                        .buttonStyle(.plain).foregroundStyle(Palette.muted).help("Forget this phone")
                        .accessibilityLabel("Forget \(phone.name)")
                }
                .padding(10)
                .glass(12, border: here ? Palette.ok.opacity(0.45) : .white.opacity(0.14))
            }
        }
        .frame(width: 460)
        .confirmationDialog("Forget \(forgetting?.name ?? "this phone")?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } })) {
            Button("Forget", role: .destructive) { if let forgetting { model.forget(forgetting) }; forgetting = nil }
        } message: { Text("You'll need to pair with a code again.") }
    }
}

/// Lays views out left to right at their natural size, starting a new row when one doesn't fit.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let width = rows.map { $0.width }.max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for index in row.items {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row { var items: [Int] = []; var y: CGFloat = 0; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if !rows[rows.count - 1].items.isEmpty && rows[rows.count - 1].width + spacing + size.width > width {
                let last = rows[rows.count - 1]
                rows.append(Row(y: last.y + last.height + spacing))
            }
            var row = rows[rows.count - 1]
            row.width += (row.items.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.items.append(index)
            rows[rows.count - 1] = row
        }
        return rows
    }
}

/// The 6-digit code to compare with the phone's.
struct PairingCode: View {
    let code: String
    var body: some View {
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
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Pairing code \(code.map { String($0) }.joined(separator: " "))")
        }
    }
}

/// A phone sharing over the cable that this Mac hasn't paired with: pair once, with the same code as over Wi‑Fi.
struct USBPairingCard: View {
    @ObservedObject var model: BrowserModel
    let phone: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "cable.connector").font(.system(size: 20)).foregroundStyle(Palette.amber).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(phone).font(.system(size: 15, weight: .bold, design: .rounded))
                    Text("Fast USB: instant search, real folder sizes, resumable copies").font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                Spacer()
            }
            if let code = model.pairingCode {
                PairingCode(code: code)
            }
            HStack {
                Button("Not now", action: model.cancelUSBPairing).buttonStyle(GhostButton()).disabled(model.busy)
                Spacer()
                Button("Pair over USB", action: model.pairOverUSB).buttonStyle(SunsetButton()).disabled(model.busy)
            }
        }
        .padding(16)
        .frame(width: 460)
        .glass(20, border: Palette.amber.opacity(0.45))
    }
}

/// A calm suggestion on the waiting screen, with an optional action.
struct HintCard: View {
    let symbol: String
    let text: String
    var action: String?
    var busy = false
    var perform: () -> Void = {}
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).foregroundStyle(Palette.amber).accessibilityHidden(true)
            Text(text).font(.system(size: 12.5)).frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
            if let action { Button(action, action: perform).buttonStyle(GhostButton()).disabled(busy) }
        }
        .padding(14)
        .frame(width: 460)
        .glass(16)
    }
}

struct ErrorCard: View {
    var message: String
    var busy = false
    var retry: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.amber).accessibilityLabel("Problem")
            Text(message).font(.system(size: 13)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            Button("Try again", action: retry).buttonStyle(GhostButton()).disabled(busy)
        }
        .padding(14)
        .frame(width: 460)
        .glass(16, border: Palette.amber.opacity(0.4))
    }
}

struct WiFiSheet: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ArchMark(size: 34)
                Text("Pair over Wi‑Fi").font(.system(size: 20, weight: .bold, design: .rounded))
            }
            Text("Tap the sun in the OpenBridge Android app. Your phone appears here; or type the address it shows. Both devices need to be on the same Wi‑Fi.")
                .foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 6) {
                Text("PHONES NEARBY").font(.system(size: 11, weight: .semibold)).tracking(1.4).foregroundStyle(Palette.muted)
                if discovery.phones.isEmpty {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Looking for phones sharing on this Wi‑Fi…").foregroundStyle(Palette.muted)
                    }
                    .font(.system(size: 12.5))
                }
                ForEach(discovery.phones) { phone in
                    Button {
                        if let known = model.saved.first(where: { $0.id == phone.phoneID }) { model.connect(known) }
                        else { model.host = phone.host; model.port = phone.port; model.pair(name: phone.name) }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "iphone").frame(width: 22)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(phone.name).font(.system(size: 13.5, weight: .semibold))
                                Text("\(phone.host) · port \(phone.port)").font(.system(size: 11.5)).foregroundStyle(Palette.muted)
                            }
                            Spacer()
                            Text("Pair").font(.system(size: 12.5, weight: .bold)).foregroundStyle(Palette.amber)
                        }
                        .padding(10).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).glass(12).disabled(model.busy)
                }
            }
            Text("OR TYPE IT").font(.system(size: 11, weight: .semibold)).tracking(1.4).foregroundStyle(Palette.muted)
            HStack {
                TextField("Phone address, like 192.168.1.23", text: $model.host)
                TextField("Port", text: $model.port).frame(width: 90)
            }
            .textFieldStyle(.roundedBorder)
            if let code = model.pairingCode {
                PairingCode(code: code)
            } else if !model.status.isEmpty && model.busy {
                Text(model.status).foregroundStyle(Palette.muted)
            }
            if let error = model.error, !model.overUSB {
                Text(error).foregroundStyle(Palette.amber).textSelection(.enabled)
            }
            HStack {
                Button("Cancel") { if model.busy { model.cancel() }; isPresented = false }.buttonStyle(GhostButton())
                Spacer()
                Button("Pair") { model.pair() }
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
    /// One model for the app, so every window and Settings see the same connection.
    @StateObject private var model = BrowserModel()
    @StateObject private var looks = LookStore.shared

    var body: some Scene {
        // A new look rebuilds the window, so every colour and picture follows it.
        WindowGroup { RootView(model: model).id(looks.look) }
            .windowStyle(.hiddenTitleBar)
            .defaultSize(width: 980, height: 660)
        Window("Phone Screen", id: "mirror") { MirrorView() }
            .windowStyle(.hiddenTitleBar)
            .defaultSize(width: 380, height: 780)
        Settings { SettingsView(model: model) }
    }
}
