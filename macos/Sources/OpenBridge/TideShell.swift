import SwiftUI
import AppKit
import BridgeCore

/// Tide, the harbour: a rail of portholes (one per phone) on the left, a surface bar with the folder trail as a depth
/// path, the files as capsules, and the water at the bottom of the window as the transfer queue: it rises with the
/// progress, and the files on their way float in it.
struct TideBrowser: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    @Binding var dropping: Bool
    @FocusState private var searching: Bool

    var body: some View {
        GeometryReader { geometry in
            let compact = ShellSize.compact(geometry.size)
            HStack(spacing: 0) {
                PortholeRail(model: model, discovery: model.discovery, mirror: mirror, compact: compact)
                TideMain(model: model, dropping: $dropping, searching: $searching, compact: compact)
            }
            .overlay(alignment: .top) {
                if dropping {
                    DropOnSurface(model: model)
                        .padding(.top, surface(geometry.size.height))
                        .transition(.opacity)
                }
            }
        }
        .modifier(BrowserChrome(model: model, searching: searching))
    }

    /// Just above where the raised water's surface is (the backdrop puts it at 80% of the height, less the rise).
    private func surface(_ height: CGFloat) -> CGFloat {
        let level: CGFloat = 0.80 - 0.24 * 0.12
        return max(0, height * level - 64)
    }
}

private struct TideMain: View {
    @ObservedObject var model: BrowserModel
    @Binding var dropping: Bool
    var searching: FocusState<Bool>.Binding
    var compact: Bool

    var body: some View {
        VStack(spacing: 0) {
            SurfaceBar(model: model, searching: searching, compact: compact)
            FolderContent(model: model, look: .tide, dropping: $dropping, outlineDrop: false)
            BrowserError(model: model)
            if model.transfer != nil {
                TransferDock(model: model)
                    .padding(.horizontal, 16).padding(.bottom, 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.5, dampingFraction: 0.68), value: model.transfer != nil)
    }
}

private struct DropOnSurface: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        Label("Drop to upload to “\(model.path.last?.name ?? "Phone")”", systemImage: "arrow.up.circle.fill")
            .font(.system(size: 15, weight: .semibold))
            .padding(.horizontal, 18).padding(.vertical, 10)
            .glass(99, border: Palette.amber.opacity(0.5))
            .modifier(Bob(seed: 3, amplitude: 2))
            .allowsHitTesting(false)
    }
}

// MARK: - Porthole rail

private struct PortholeRail: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    var mirror: () -> Void
    var compact: Bool

    var body: some View {
        let size: CGFloat = compact ? 36 : 48
        VStack(spacing: compact ? 10 : 14) {
            ForEach(Array(Roster.phones(model, discovery).prefix(6).enumerated()), id: \.element.id) { index, phone in
                Button { Roster.choose(phone, model: model, discovery: discovery) } label: {
                    Porthole(size: size, filled: phone.kind == .open, bright: phone.kind == .open, cable: phone.cable)
                        .modifier(Bob(seed: index, amplitude: 1))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(model.busy && phone.kind != .open)
                .help(Roster.label(phone))
                .accessibilityLabel(Roster.label(phone))
                .accessibilityHint(phone.kind == .open ? "Shows the phones" : "Opens this phone")
            }
            Spacer(minLength: 8)
            RailTools(model: model, mirror: mirror, size: size)
        }
        .padding(.top, 44).padding(.bottom, 14)
        .frame(width: compact ? 56 : 76)
        .background(Color.white.opacity(0.03))
        .overlay(alignment: .trailing) { Rectangle().fill(Palette.amber.opacity(0.12)).frame(width: 1) }
    }
}

/// Texts, Mirror and Settings portholes at the foot of the rail.
private struct RailTools: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    var size: CGFloat

    var body: some View {
        VStack(spacing: 10) {
            if model.clipboardAvailable {
                Button { model.showTexts = true } label: { Porthole(size: size * 0.8, symbol: "text.bubble") }
                    .buttonStyle(.plain)
                    .help("Send texts to the phone, and see what it sent").accessibilityLabel("Texts")
            }
            if model.overUSB || model.fastUSB {
                Button(action: mirror) { Porthole(size: size * 0.8, symbol: "rectangle.on.rectangle") }
                    .buttonStyle(.plain)
                    .help("Show and control the phone's screen on your Mac (needs USB debugging)").accessibilityLabel("Mirror screen")
            }
            SettingsButton { Porthole(size: size * 0.8, symbol: "gearshape") }
                .buttonStyle(.plain)
                .help("Settings").accessibilityLabel("Settings")
        }
    }
}

// MARK: - Surface bar

/// The folder trail as a depth path and the controls; a gentle wave forms its lower edge. Compact, the controls fold
/// into ⋯.
private struct SurfaceBar: View {
    @ObservedObject var model: BrowserModel
    var searching: FocusState<Bool>.Binding
    var compact: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                UpButton(model: model)
                ResultsButton(model: model)
                DepthPath(model: model)
                Spacer(minLength: 8)
                SearchBox(model: model, searching: searching, width: compact ? 140 : 180, radius: 99)
                SortMenu(model: model)
                ViewToggle(model: model)
                MediaToggle(model: model)
                if !compact {
                    Button(action: model.chooseUploads) { Image(systemName: "square.and.arrow.up") }
                        .buttonStyle(.plain).frame(width: 28).disabled(model.busy)
                        .help("Upload files to this folder").accessibilityLabel("Upload")
                    Button(action: model.newFolder) { Image(systemName: "folder.badge.plus") }
                        .buttonStyle(.plain).frame(width: 28).disabled(model.busy)
                        .help("New folder (⌘⇧N)").accessibilityLabel("New folder")
                }
                MoreMenu(model: model, devices: true, folded: false)
                DownloadButton(model: model)
            }
            .padding(.horizontal, 16).padding(.top, 40).padding(.bottom, 8)
            WaveLine().frame(height: 10)
        }
    }
}

/// "Internal storage › DCIM › Camera", each step a little deeper in tone. Deep paths fold their middle into "…".
private struct DepthPath: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        let path = Array(model.path.enumerated())
        let hidden = path.count > 3 ? Array(path[1..<(path.count - 2)]) : []
        let shown = hidden.isEmpty ? path : [path[0]] + path.suffix(2)
        HStack(spacing: 4) {
            ForEach(shown, id: \.offset) { index, crumb in
                if index > 0 { chevron }
                if index == path.count - 2 && !hidden.isEmpty {
                    Menu {
                        ForEach(hidden, id: \.offset) { hiddenIndex, folder in Button(folder.name) { model.go(to: hiddenIndex) } }
                    } label: { Text("…").font(.system(size: 14, weight: .medium)).foregroundStyle(Palette.muted) }
                        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help("Folders in between")
                        .accessibilityLabel("Folders in between")
                    chevron
                }
                let last = index == path.count - 1
                Button(crumb.name) { model.go(to: index) }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: last ? .bold : .medium))
                    .foregroundStyle(tone(index, of: path.count))
                    .lineLimit(1).truncationMode(.middle)
                    .layoutPriority(last ? 1 : 0)
            }
        }
    }

    private var chevron: some View {
        Image(systemName: "chevron.right").font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.muted).accessibilityHidden(true)
    }

    /// The top is pale like the surface; each folder down is a little deeper aqua.
    private func tone(_ index: Int, of count: Int) -> Color {
        let colors = Palette.look.colors
        let depth: Double = count > 1 ? Double(index) / Double(count - 1) : 1
        return RGB.mix(colors.cream, colors.amber, depth).color()
    }
}

// MARK: - Rows and cards

/// Tide's list row: a rounded capsule with the name, a depth gauge for folders, and the size. Selecting fills it with
/// aqua from the left, like water.
struct CapsuleRow: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil
    /// The biggest measured folder alongside, for the gauge.
    var deepest: Int64 = 0
    var focused = false

    var body: some View {
        let (name, color) = fileSymbol(FileKind(entry))
        HStack(spacing: 12) {
            FileIcon(symbol: name, color: color, thumbnail: thumbnail, size: 26)
            Text(entry.name).lineLimit(1).truncationMode(.middle)
            Spacer(minLength: 8)
            if entry.directory { DepthGauge(fraction: gauge) }
            Text(entry.directory ? folderText(folderSize) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .foregroundStyle(Palette.muted).monospacedDigit()
                .frame(minWidth: 64, alignment: .trailing)
                .help(folderSize.map { $0.files > 0 ? "\($0.files) files" : "" } ?? "")
        }
        .font(.system(size: 13.5))
        .padding(.horizontal, 16).padding(.vertical, 9)
        .background { CapsuleWater(selected: selected) }
        .overlay(Capsule().strokeBorder(Palette.amber.opacity(focused ? 0.95 : 0), lineWidth: 1.5))
        .contentShape(Capsule())
    }

    private var gauge: Double? {
        guard let bytes = folderSize?.bytes else { return nil }
        guard deepest > 0 else { return 0 }
        return Double(bytes) / Double(deepest)
    }
}

/// A capsule's glass, and the water that fills it from the left when selected.
private struct CapsuleWater: View {
    var selected: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let colors = Palette.look.colors
        ZStack(alignment: .leading) {
            Capsule().fill(Color.white.opacity(0.05))
            Capsule()
                .fill(LinearGradient(colors: [colors.violet.color(0.55), colors.amber.color(0.4)], startPoint: .leading, endPoint: .trailing))
                .scaleEffect(x: selected ? 1 : 0.001, y: 1, anchor: .leading)
                .opacity(selected ? 1 : 0)
        }
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.07)))
        .animation(reduceMotion ? .easeOut(duration: 0.1) : .spring(response: 0.45, dampingFraction: 0.7), value: selected)
    }
}

/// Tide's grid card: round-cornered, rising into place with a little bob when it appears.
struct TideCard: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil
    var focused = false

    var body: some View {
        let (name, color) = fileSymbol(FileKind(entry))
        let card = RoundedRectangle(cornerRadius: 22, style: .continuous)
        VStack(spacing: 7) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.06))
                if let thumbnail {
                    Image(nsImage: thumbnail).resizable().scaledToFill()
                } else {
                    Image(systemName: name).font(.system(size: 34)).foregroundStyle(color)
                }
            }
            .frame(width: 116, height: 96)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .accessibilityHidden(true)
            Text(entry.name).font(.system(size: 12)).lineLimit(2).multilineTextAlignment(.center).truncationMode(.middle)
            Text(entry.directory ? (folderSize == nil ? " " : folderText(folderSize)) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .font(.system(size: 10.5)).foregroundStyle(Palette.muted)
        }
        .padding(10)
        .frame(width: 140)
        .background { card.fill(selected ? Palette.amber.opacity(0.22) : Color.white.opacity(0.04)) }
        .overlay(card.strokeBorder(Palette.amber.opacity(selected ? 0.55 : 0.08), lineWidth: 1))
        .overlay(card.strokeBorder(Palette.amber.opacity(focused ? 0.95 : 0), lineWidth: 1.5).padding(-2))
        .contentShape(card)
        .modifier(RiseIn(seed: entry.name.count % 8, depth: 8))
    }
}

// MARK: - Transfers afloat

/// While files move, they float in the water as capsules: the one on its way, with its progress and Cancel or
/// Resume, and the next ones behind it. A finished file sinks away as the next one takes its place.
private struct TransferDock: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        if let transfer = model.transfer {
            HStack(spacing: 10) {
                FloatingTransfer(model: model, transfer: transfer)
                    .id(transfer.index)
                    .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                            removal: .offset(y: 30).combined(with: .opacity)))
                WaitingCapsules(transfer: transfer)
                Spacer(minLength: 0)
            }
            .animation(.spring(response: 0.55, dampingFraction: 0.65), value: transfer.index)
        }
    }
}

private struct FloatingTransfer: View {
    @ObservedObject var model: BrowserModel
    let transfer: BrowserModel.Transfer

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: transfer.upload ? "arrow.up.circle.fill" : "arrow.down.circle.fill").foregroundStyle(Palette.amber)
                .accessibilityHidden(true)
            Text(transfer.name).lineLimit(1).truncationMode(.middle).frame(maxWidth: 260, alignment: .leading)
            Text(TransferProgress.summary(model)).foregroundStyle(Palette.muted).monospacedDigit().fixedSize()
            TransferActions(model: model).fixedSize()
        }
        .font(.system(size: 13))
        .padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 7)
        .background { FillingCapsule(fraction: transfer.done ? 1 : transfer.fraction, stopped: TransferProgress.stopped(model)) }
        .modifier(Bob(seed: transfer.index, amplitude: 1.5))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Transfer, \(TransferProgress.summary(model))")
    }
}

/// A capsule whose water level, left to right, is the file's progress.
private struct FillingCapsule: View {
    var fraction: Double
    var stopped: Bool

    var body: some View {
        let colors = Palette.look.colors
        let tint: Color = stopped ? colors.pink.color(0.45) : colors.amber.color(0.35)
        GeometryReader { geometry in
            let width: CGFloat = geometry.size.width * CGFloat(min(max(fraction, 0), 1))
            ZStack(alignment: .leading) {
                Color.clear.frosted(Capsule())
                Rectangle().fill(tint).frame(width: width)
            }
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(colors.amber.color(0.4)))
            .animation(.easeOut(duration: 0.3), value: fraction)
        }
    }
}

/// The files after the current one, as small capsules (the first few, and how many more).
private struct WaitingCapsules: View {
    let transfer: BrowserModel.Transfer

    var body: some View {
        let next = Array(transfer.remaining.dropFirst().prefix(2))
        let more = transfer.remaining.count - 1 - next.count
        ForEach(Array(next.enumerated()), id: \.offset) { index, job in
            Text(job.name)
                .font(.system(size: 11.5)).foregroundStyle(Palette.muted)
                .lineLimit(1).truncationMode(.middle).frame(maxWidth: 120)
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(Color.white.opacity(0.05), in: Capsule())
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.1)))
                .modifier(Bob(seed: index + 9, amplitude: 1))
                .accessibilityLabel("Next: \(job.name)")
        }
        if more > 0 {
            Text("+\(more)").font(.system(size: 11.5, weight: .semibold)).foregroundStyle(Palette.muted)
                .accessibilityLabel("and \(more) more")
        }
    }
}

// MARK: - Connect screen

/// Tide's connect screen: the pool in the centre; the phones nearby and the saved ones rise from the water as
/// bubbles. The pairing code floats above the pool.
struct TideConnect: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    @Binding var wifi: Bool
    @Binding var openAutomatically: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var forgetting: SavedPhone? = nil

    var body: some View {
        GeometryReader { geometry in
            let small = geometry.size.height < 640
            let centerY: Double = small ? 0.27 : 0.3
            let radius: Double = small ? 54 : 70
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height * CGFloat(centerY))
            let top: CGFloat = center.y - CGFloat(radius)
            ZStack(alignment: .top) {
                Hero(active: true, connected: model.connected, centerY: centerY, radius: radius, startScanning: true,
                     label: ConnectText.title(model))
                    .allowsHitTesting(false)
                if let code = model.pairingCode {
                    BigCode(code: code, size: small ? 24 : 30)
                        .position(x: center.x, y: max(30, top - 40))
                        .modifier(Bob(seed: 1, amplitude: 2))
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                VStack(spacing: 6) {
                    Spacer().frame(height: center.y + CGFloat(radius) + 26)
                    Text(ConnectText.title(model)).font(.system(size: 24, weight: .bold, design: .rounded))
                    Text(ConnectText.subtitle(model)).font(.system(size: 13)).foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center).modifier(Selectable())
                    ViewThatFits(in: .vertical) {
                        TideConnectDetails(model: model, discovery: discovery, wifi: $wifi, openAutomatically: $openAutomatically,
                                           forgetting: $forgetting)
                        ScrollView {
                            TideConnectDetails(model: model, discovery: discovery, wifi: $wifi, openAutomatically: $openAutomatically,
                                               forgetting: $forgetting)
                        }
                    }
                    Spacer(minLength: 0)
                    PairOverWiFiLink(model: model, wifi: $wifi).padding(.bottom, 18)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity)
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.6, dampingFraction: 0.62), value: model.connected)
        .animation(reduceMotion ? nil : .spring(response: 0.5, dampingFraction: 0.6), value: model.pairingCode)
        .confirmationDialog("Forget \(forgetting?.name ?? "this phone")?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } })) {
            Button("Forget", role: .destructive) { if let forgetting { model.forget(forgetting) }; forgetting = nil }
        } message: { Text("You'll need to pair with a code again.") }
    }
}

private struct TideConnectDetails: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var discovery: PhoneDiscovery
    @Binding var wifi: Bool
    @Binding var openAutomatically: Bool
    @Binding var forgetting: SavedPhone?

    var body: some View {
        VStack(spacing: 12) {
            if model.connected {
                DeviceCard(model: model, openAutomatically: $openAutomatically)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
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
                BubbleField(model: model, phones: Roster.phones(model, discovery).filter { $0.kind == .saved || $0.kind == .nearby },
                            forgetting: $forgetting)
            }
        }
        .padding(.top, 12)
    }
}

/// Phones as bubbles risen from the water, bobbing slowly. Click one to connect or pair.
private struct BubbleField: View {
    @ObservedObject var model: BrowserModel
    let phones: [RosterPhone]
    @Binding var forgetting: SavedPhone?

    var body: some View {
        FlowLayout(spacing: 18) {
            ForEach(Array(phones.prefix(8).enumerated()), id: \.element.id) { index, phone in
                Button { Roster.choose(phone, model: model, discovery: model.discovery) } label: {
                    Bubble(name: phone.name, bright: phone.kind == .saved && phone.here)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(model.busy)
                .help(phone.kind == .nearby ? "Pair with \(phone.name)" : "Connect to \(phone.name)")
                .accessibilityLabel(Roster.label(phone))
                .accessibilityHint(phone.kind == .nearby ? "Pairs with this phone" : "Connects to this phone")
                .contextMenu {
                    if let saved = Roster.forgettable(phone, model: model) {
                        Button("Forget…") { forgetting = saved }
                    }
                }
                .modifier(Bob(seed: index, amplitude: 3))
                .modifier(RiseIn(seed: index, depth: 40))
            }
        }
        .frame(maxWidth: 560)
        .padding(.top, 10)
    }
}
