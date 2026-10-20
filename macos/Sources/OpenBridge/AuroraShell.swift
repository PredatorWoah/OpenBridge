import SwiftUI
import AppKit
import BridgeCore

/// Aurora, the observatory: the sky is the frame and the files float in it. A sky header with the phone's halo and the
/// constellation switcher, the folder trail on the horizon (where a comet shows transfers), and one floating glass
/// sheet of files. No sidebar.
struct AuroraBrowser: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    @Binding var dropping: Bool
    @FocusState private var searching: Bool

    var body: some View {
        GeometryReader { geometry in
            let compact = ShellSize.compact(geometry.size)
            VStack(spacing: 0) {
                AuroraHeader(model: model, discovery: model.discovery, mirror: mirror, compact: compact)
                AuroraHorizon(model: model)
                    .padding(.horizontal, 24)
                AuroraSheet(model: model, dropping: $dropping, searching: $searching, compact: compact)
                    .padding(.horizontal, compact ? 16 : 24).padding(.bottom, compact ? 16 : 24).padding(.top, 6)
            }
        }
        .modifier(BrowserChrome(model: model, searching: searching))
    }
}

// MARK: - Sky header

private struct AuroraHeader: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    var mirror: () -> Void
    var compact: Bool

    var body: some View {
        HStack(alignment: .center, spacing: compact ? 12 : 18) {
            HeaderHalo(running: running, size: compact ? 40 : 64)
            PhoneTitle(model: model, compact: compact)
            Spacer(minLength: 12)
            if compact {
                SwitcherMenu(model: model, phones: Roster.phones(model, discovery))
            } else {
                ConstellationSwitcher(model: model, phones: Roster.phones(model, discovery))
            }
            HeaderButtons(model: model, mirror: mirror)
        }
        .padding(.horizontal, 28)
        .padding(.top, compact ? 26 : 40)
        .frame(height: compact ? 88 : 150)
    }

    private var running: Bool {
        guard let transfer = model.transfer else { return false }
        return !transfer.done && model.busy
    }
}

/// The phone's halo: its comet orbits while files move, and rests at the top when they don't.
private struct HeaderHalo: View {
    var running: Bool
    var size: CGFloat

    var body: some View {
        let canvas: CGFloat = size * 1.6
        let radius: Double = Double(size) * 0.38
        Hero(active: true, connected: !running, centerY: 0.5, radius: radius, reach: Double(size) * 0.6)
            .frame(width: canvas, height: canvas)
            .frame(width: size, height: size)
            .allowsHitTesting(false)
    }
}

/// The name in a light, spaced type; under it the status, and a chip for the transport.
private struct PhoneTitle: View {
    @ObservedObject var model: BrowserModel
    var compact: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.deviceName)
                .font(.system(size: compact ? 20 : 28, weight: .light))
                .tracking(compact ? 1.5 : 3)
                .lineLimit(1).truncationMode(.tail)
            if !compact {
                HStack(spacing: 10) {
                    Text(status).font(.system(size: 12.5)).foregroundStyle(Palette.muted).lineLimit(1)
                    TransportChip(model: model)
                }
            }
        }
        .layoutPriority(1)
    }

    private var status: String {
        if model.transfer != nil { return TransferProgress.summary(model) }
        if model.busy { return "Working…" }
        return model.overUSB || model.fastUSB ? "Ready, over the cable" : "Ready"
    }
}

struct TransportChip: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        Label(ConnectText.transport(model), systemImage: model.fastUSB ? "bolt.horizontal" : model.overUSB ? "cable.connector" : "wifi")
            .font(.system(size: 11, weight: .semibold))
            .padding(.horizontal, 9).padding(.vertical, 3)
            .background(Palette.amber.opacity(0.10), in: Capsule())
            .overlay(Capsule().strokeBorder(Palette.amber.opacity(0.4)))
            .fixedSize()
    }
}

/// One orb per phone, names as tooltips; the open one brighter, with a ring. Click one to open it.
private struct ConstellationSwitcher: View {
    @ObservedObject var model: BrowserModel
    let phones: [RosterPhone]

    var body: some View {
        HStack(spacing: 14) {
            ForEach(Array(phones.prefix(8).enumerated()), id: \.element.id) { index, phone in
                Button { Roster.choose(phone, model: model, discovery: model.discovery) } label: {
                    Orb(size: phone.kind == .open ? 12 : 9, brightness: brightness(phone), ringed: phone.kind == .open,
                        color: phone.kind == .nearby ? Palette.pink : Palette.amber)
                        .modifier(Twinkle(seed: index, low: phone.kind == .open ? 0.85 : 0.5))
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(model.busy && phone.kind != .open)
                .help(Roster.label(phone))
                .accessibilityLabel(Roster.label(phone))
                .accessibilityHint(phone.kind == .open ? "Shows the phones" : "Opens this phone")
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
    }

    private func brightness(_ phone: RosterPhone) -> Double {
        switch phone.kind {
        case .open: return 1
        case .saved: return phone.here ? 0.8 : 0.45
        case .nearby, .cable: return 0.6
        }
    }
}

/// Compact: the switcher folds into one orb with a menu.
private struct SwitcherMenu: View {
    @ObservedObject var model: BrowserModel
    let phones: [RosterPhone]

    var body: some View {
        Menu {
            ForEach(phones) { phone in
                Button(phone.kind == .open ? "\(phone.name) (open)" : phone.name) {
                    Roster.choose(phone, model: model, discovery: model.discovery)
                }
                .disabled(model.busy && phone.kind != .open)
            }
        } label: { Image(systemName: "sparkles") }
            .menuStyle(.borderlessButton).frame(width: 34)
            .help("Phones").accessibilityLabel("Phones")
    }
}

/// Texts, Mirror and Settings.
private struct HeaderButtons: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            if model.clipboardAvailable {
                SkyButton(symbol: "text.bubble", label: "Texts", help: "Send texts to the phone, and see what it sent") { model.showTexts = true }
            }
            if model.overUSB || model.fastUSB {
                SkyButton(symbol: "rectangle.on.rectangle", label: "Mirror screen",
                          help: "Show and control the phone's screen on your Mac (needs USB debugging)", action: mirror)
            }
            SettingsButton { SkyIcon(symbol: "gearshape") }
                .buttonStyle(.plain)
                .help("Settings").accessibilityLabel("Settings")
        }
    }
}

private struct SkyButton: View {
    let symbol: String
    let label: String
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { SkyIcon(symbol: symbol) }
            .buttonStyle(.plain)
            .help(help).accessibilityLabel(label)
    }
}

private struct SkyIcon: View {
    let symbol: String
    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 14, weight: .regular))
            .frame(width: 32, height: 32)
            .background(Color.white.opacity(0.06), in: Circle())
            .overlay(Circle().strokeBorder(Palette.amber.opacity(0.25)))
            .contentShape(Circle())
    }
}

// MARK: - Horizon

/// The folder trail on the horizon line, and the comet with its queue.
private struct AuroraHorizon: View {
    @ObservedObject var model: BrowserModel
    @State private var showQueue = false

    var body: some View {
        HStack(spacing: 10) {
            ConstellationPath(model: model)
            Spacer(minLength: 8)
            if model.transfer != nil {
                Button { showQueue.toggle() } label: {
                    Text(TransferProgress.summary(model))
                        .font(.system(size: 12, weight: .semibold)).monospacedDigit()
                        .foregroundStyle(TransferProgress.stopped(model) ? Color(red: 1, green: 0.72, blue: 0.3) : Palette.amber)
                }
                .buttonStyle(.plain)
                .fixedSize()
                .help("Transfers")
                .accessibilityLabel("Transfers, \(TransferProgress.summary(model))")
                .onHover { inside in if inside { showQueue = true } }
                .popover(isPresented: $showQueue, arrowEdge: .bottom) { TransferQueue(model: model) }
            }
        }
        .frame(height: 34)
        .background(alignment: .bottom) {
            CometLine(progress: model.transfer.map { TransferProgress.overall($0) },
                      stopped: TransferProgress.stopped(model), done: model.transfer?.done ?? false)
                .frame(height: 10)
        }
        .onChange(of: TransferProgress.stopped(model)) { stopped in if stopped { showQueue = true } }
    }
}

// MARK: - Floating sheet

/// The files on one large frosted sheet floating over the sky, with a slim toolbar along its top edge.
private struct AuroraSheet: View {
    @ObservedObject var model: BrowserModel
    @Binding var dropping: Bool
    var searching: FocusState<Bool>.Binding
    var compact: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        VStack(spacing: 0) {
            SheetToolbar(model: model, searching: searching, compact: compact)
            Rectangle().fill(Palette.amber.opacity(0.12)).frame(height: 1)
            FolderContent(model: model, look: .aurora, dropping: $dropping)
            BrowserError(model: model)
        }
        .background(Palette.night.opacity(0.45), in: shape)
        .background(.ultraThinMaterial, in: shape)
        .clipShape(shape)
        .overlay(shape.strokeBorder(LinearGradient(colors: [Palette.amber.opacity(0.45), Palette.amber.opacity(0.08)],
                                                   startPoint: .top, endPoint: .bottom), lineWidth: 1))
        .shadow(color: Palette.amber.opacity(0.10), radius: 18)
        .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
    }
}

private struct SheetToolbar: View {
    @ObservedObject var model: BrowserModel
    var searching: FocusState<Bool>.Binding
    var compact: Bool

    var body: some View {
        HStack(spacing: 8) {
            UpButton(model: model)
            ResultsButton(model: model)
            SearchBox(model: model, searching: searching, width: compact ? 150 : 210, radius: 10)
            Spacer(minLength: 6)
            if !compact {
                SortMenu(model: model)
                ViewToggle(model: model)
                MediaToggle(model: model)
                Button(action: model.chooseUploads) { Image(systemName: "square.and.arrow.up") }
                    .buttonStyle(.plain).frame(width: 28).disabled(model.busy)
                    .help("Upload files to this folder").accessibilityLabel("Upload")
                Button(action: model.newFolder) { Image(systemName: "folder.badge.plus") }
                    .buttonStyle(.plain).frame(width: 28).disabled(model.busy)
                    .help("New folder (⌘⇧N)").accessibilityLabel("New folder")
            }
            MoreMenu(model: model, devices: true, folded: compact)
            DownloadButton(model: model)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
    }
}

// MARK: - Rows and tiles

/// Aurora's list row: the same columns as Dusk's, with more air; selection and focus glow instead of filling.
struct AuroraRow: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil
    var focused = false

    var body: some View {
        let (name, color) = fileSymbol(FileKind(entry))
        HStack(spacing: 14) {
            FileIcon(symbol: name, color: color, thumbnail: thumbnail, size: 28)
            Text(entry.name).lineLimit(1).truncationMode(.middle)
            Spacer()
            Text(entry.directory ? folderText(folderSize) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .foregroundStyle(Palette.muted).monospacedDigit()
                .help(folderSize.map { $0.files > 0 ? "\($0.files) files" : "" } ?? "")
        }
        .font(.system(size: 14))
        .padding(.horizontal, 16).padding(.vertical, 10)
        .modifier(SoftGlow(radius: 12, selected: selected, focused: focused))
        .contentShape(Rectangle())
    }
}

/// Aurora's gallery tile: a large picture with its caption under it.
struct AuroraTile: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil
    var focused = false

    var body: some View {
        let (name, color) = fileSymbol(FileKind(entry))
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        VStack(spacing: 8) {
            ZStack {
                shape.fill(Color.white.opacity(0.05))
                if let thumbnail {
                    Image(nsImage: thumbnail).resizable().scaledToFill()
                } else {
                    Image(systemName: name).font(.system(size: 40, weight: .light)).foregroundStyle(color)
                }
            }
            .frame(width: 144, height: 120)
            .clipShape(shape)
            .modifier(SoftGlow(radius: 14, selected: selected, focused: focused))
            .accessibilityHidden(true)
            Text(entry.name).font(.system(size: 12)).lineLimit(2).multilineTextAlignment(.center).truncationMode(.middle)
            Text(entry.directory ? (folderSize == nil ? " " : folderText(folderSize)) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .font(.system(size: 10.5)).foregroundStyle(Palette.muted)
        }
        .padding(8)
        .frame(width: 160)
        .contentShape(Rectangle())
    }
}

/// A file's preview, or its symbol.
struct FileIcon: View {
    let symbol: String
    let color: Color
    let thumbnail: NSImage?
    var size: CGFloat = 26

    var body: some View {
        Group {
            if let thumbnail {
                Image(nsImage: thumbnail).resizable().scaledToFill().frame(width: size, height: size)
                    .clipShape(RoundedRectangle(cornerRadius: size * 0.2, style: .continuous))
            } else {
                Image(systemName: symbol).foregroundStyle(color)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Selected: a faint mint wash and an outer glow. Keyboard focus: a mint ring, which shows on a selected item too.
private struct SoftGlow: ViewModifier {
    var radius: CGFloat
    var selected: Bool
    var focused: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .background {
                if selected {
                    shape.fill(Palette.amber.opacity(0.08))
                        .shadow(color: Palette.amber.opacity(0.45), radius: 10)
                }
            }
            .overlay(shape.strokeBorder(Palette.amber.opacity(selected ? 0.5 : 0), lineWidth: 1))
            .overlay(shape.strokeBorder(Palette.amber.opacity(focused ? 0.95 : 0), lineWidth: 1.5).padding(-2))
    }
}

// MARK: - Connect screen

/// Aurora's connect screen: a big halo in the middle of the sky, with the phones nearby and the saved ones as stars
/// in a loose ring around it. The pairing code shows large inside the halo.
struct AuroraConnect: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    @Binding var wifi: Bool
    @Binding var openAutomatically: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var forgetting: SavedPhone? = nil

    var body: some View {
        GeometryReader { geometry in
            let small = geometry.size.height < 640
            let centerY: Double = small ? 0.25 : 0.32
            let radius: Double = small ? 52 : 74
            let ring = CGSize(width: small ? 200 : 250, height: small ? 84 : 118)
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height * CGFloat(centerY))
            ZStack(alignment: .top) {
                Hero(active: true, connected: model.connected, centerY: centerY, radius: radius, startScanning: true,
                     label: ConnectText.title(model))
                    .allowsHitTesting(false)
                StarRing(model: model, phones: Roster.phones(model, discovery).filter { $0.kind != .open },
                         center: center, ring: ring, forgetting: $forgetting)
                if let code = model.pairingCode {
                    BigCode(code: code, size: small ? 22 : 28, caption: false)
                        .position(center)
                        .transition(.opacity)
                }
                VStack(spacing: 6) {
                    Spacer().frame(height: center.y + ring.height + 34)
                    Text(ConnectText.title(model)).font(.system(size: 24, weight: .light)).tracking(2)
                    Text(ConnectText.subtitle(model)).font(.system(size: 13)).foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center).textSelection(.enabled)
                    ViewThatFits(in: .vertical) {
                        AuroraConnectDetails(model: model, wifi: $wifi, openAutomatically: $openAutomatically)
                        ScrollView { AuroraConnectDetails(model: model, wifi: $wifi, openAutomatically: $openAutomatically) }
                    }
                    Spacer(minLength: 0)
                    PairOverWiFiLink(model: model, wifi: $wifi).padding(.bottom, 18)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.6), value: model.connected)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: model.pairingCode)
        .confirmationDialog("Forget \(forgetting?.name ?? "this phone")?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } })) {
            Button("Forget", role: .destructive) { if let forgetting { model.forget(forgetting) }; forgetting = nil }
        } message: { Text("You'll need to pair with a code again.") }
    }
}

/// What to do next, under the halo: the phone's card when connected, otherwise quiet hints.
private struct AuroraConnectDetails: View {
    @ObservedObject var model: BrowserModel
    @Binding var wifi: Bool
    @Binding var openAutomatically: Bool

    var body: some View {
        VStack(spacing: 12) {
            if model.connected {
                DeviceCard(model: model, openAutomatically: $openAutomatically)
                    .transition(.opacity.combined(with: .offset(y: 8)))
            } else {
                if let phone = model.usbPairable {
                    CablePairPrompt(model: model, phone: phone)
                } else if model.usbNeedsAllow {
                    QuietHint(symbol: "cable.connector", text: ConnectText.allowFastUSB, action: "Use Fast USB", busy: model.busy) {
                        model.connectFastUSB(asking: true)
                    }
                }
                if model.wifiIsolated {
                    QuietHint(symbol: "wifi.exclamationmark", text: ConnectText.isolated)
                }
                if let error = model.error {
                    ErrorCard(message: error, busy: model.busy, retry: { if model.overUSB { model.retryUSB() } else { wifi = true } })
                        .transition(.opacity)
                }
            }
        }
        .padding(.top, 12)
    }
}

/// The phones as stars in a loose ring around the halo: saved ones brighter, names under them. Click one to connect
/// or pair.
private struct StarRing: View {
    @ObservedObject var model: BrowserModel
    let phones: [RosterPhone]
    let center: CGPoint
    let ring: CGSize
    @Binding var forgetting: SavedPhone?

    var body: some View {
        let shown = Array(phones.prefix(10).enumerated())
        ZStack {
            ForEach(shown, id: \.element.id) { index, phone in
                StarButton(phone: phone, seed: index, busy: model.busy,
                           choose: { Roster.choose(phone, model: model, discovery: model.discovery) },
                           forget: forgetAction(phone))
                    .position(place(index, of: shown.count))
                    .transition(.opacity)
            }
        }
    }

    private func forgetAction(_ phone: RosterPhone) -> (() -> Void)? {
        guard let saved = Roster.forgettable(phone, model: model) else { return nil }
        return { forgetting = saved }
    }

    /// Evenly round the ellipse, starting off the top, each a little nearer or farther so the ring looks loose.
    private func place(_ index: Int, of count: Int) -> CGPoint {
        let step: Double = 2 * Double.pi / Double(max(count, 1))
        let angle: Double = -Double.pi / 2 + step * (Double(index) + 0.5)
        let loose: Double = 0.9 + 0.2 * LookArt.hash(index + 5)
        let x: Double = Double(center.x) + Double(ring.width) * loose * cos(angle)
        let y: Double = Double(center.y) + Double(ring.height) * loose * sin(angle)
        return CGPoint(x: x, y: y)
    }
}

private struct StarButton: View {
    let phone: RosterPhone
    let seed: Int
    let busy: Bool
    let choose: () -> Void
    let forget: (() -> Void)?

    /// Saved phones shine brighter, brightest when they're on this Wi‑Fi now.
    private var glow: Double {
        guard phone.kind == .saved else { return 0.5 }
        return phone.here ? 1 : 0.75
    }

    var body: some View {
        Button(action: choose) {
            VStack(spacing: 5) {
                Orb(size: phone.kind == .saved ? 13 : 9, brightness: glow,
                    color: phone.kind == .nearby ? Palette.pink : Palette.amber)
                    .modifier(Twinkle(seed: seed))
                    .frame(width: 26, height: 26)
                Text(phone.name)
                    .font(.system(size: 11.5, weight: phone.kind == .saved ? .regular : .light))
                    .foregroundStyle(phone.kind == .saved ? Color.white : Palette.muted)
                    .lineLimit(1).truncationMode(.tail)
                    .frame(maxWidth: 120)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .help(phone.kind == .nearby ? "Pair with \(phone.name)" : "Connect to \(phone.name)")
        .accessibilityLabel(Roster.label(phone))
        .accessibilityHint(phone.kind == .nearby ? "Pairs with this phone" : "Connects to this phone")
        .contextMenu {
            if let forget { Button("Forget…", action: forget) }
        }
    }
}
