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
    @StateObject private var model = BrowserModel()
    @State private var wifi = false
    @AppStorage("openAutomatically") private var openAutomatically = false
    @Environment(\.openWindow) private var openWindow

    private var browsing: Bool { model.showBrowser && model.connected }

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            if browsing {
                BrowserScreen(model: model, mirror: { openWindow(id: "mirror") })
                    .transition(.asymmetric(insertion: .scale(scale: 1.04).combined(with: .opacity), removal: .opacity))
            } else {
                ConnectScreen(model: model, wifi: $wifi, openAutomatically: $openAutomatically)
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.55, dampingFraction: 0.82), value: browsing)
        .frame(minWidth: 780, minHeight: 580)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $wifi) { WiFiSheet(model: model, discovery: model.discovery, isPresented: $wifi) }
        .overlay(alignment: .topTrailing) {
            if let update = model.update {
                Link("Update available: \(update.version)", destination: update.page)
                    .font(.system(size: 12, weight: .bold)).foregroundStyle(Palette.background)
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(Palette.amber, in: Capsule())
                    .padding(.top, 10).padding(.trailing, 14)
            }
        }
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
                    } else {
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
                    Image(systemName: "iphone").foregroundStyle(here ? Palette.ok : .white).frame(width: 22)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(phone.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                        Text(here ? "On this Wi‑Fi now" : "Reconnects by itself while the phone is sharing")
                            .font(.system(size: 11.5)).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Button("Connect") { model.connect(phone) }.buttonStyle(GhostButton()).disabled(model.busy)
                    Button { forgetting = phone } label: { Image(systemName: "xmark") }
                        .buttonStyle(.plain).foregroundStyle(Palette.muted).help("Forget this phone")
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

struct ErrorCard: View {
    var message: String
    var busy = false
    var retry: () -> Void
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.amber)
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

    var body: some Scene {
        WindowGroup { RootView() }
            .windowStyle(.hiddenTitleBar)
            .defaultSize(width: 980, height: 660)
        Window("Phone Screen", id: "mirror") { MirrorView() }
            .windowStyle(.hiddenTitleBar)
            .defaultSize(width: 380, height: 780)
    }
}
