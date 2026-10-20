import SwiftUI
import AppKit
import BridgeCore

// Parts every look's shell arranges in its own way: the words of the connect screen, the list of phones to switch
// between, the transfer queue and the Settings button.

/// What the connect screen says, in every look.
enum ConnectText {
    @MainActor static func title(_ model: BrowserModel) -> String {
        if model.connected { return model.fastUSB ? "Connected over Fast USB" : model.overUSB ? "Connected over USB" : "Connected over Wi‑Fi" }
        if model.usbPairable != nil { return "Pair over USB" }
        if model.busy { return model.overUSB ? "Phone found" : "Connecting…" }
        return "Waiting for your phone"
    }

    @MainActor static func subtitle(_ model: BrowserModel) -> String {
        if model.connected { return "Your phone is ready." }
        if !model.status.isEmpty { return model.status }
        if model.usbPairable != nil { return "OpenBridge is sharing over the cable. Pair once, then it connects by itself." }
        return "Plug it in, unlock it and choose File transfer."
    }

    /// The transport, short: for chips and status lines.
    @MainActor static func transport(_ model: BrowserModel) -> String {
        model.fastUSB ? "Fast USB" : model.overUSB ? "USB · File transfer" : "Wi‑Fi"
    }

    static let allowFastUSB = "USB debugging is on. Allow it for this Mac to use Fast USB: instant search, real folder sizes, resumable copies."
    static let isolated = "This Wi‑Fi keeps devices apart (common on guest, hotel and campus networks). On the phone, open OpenBridge › Settings › Phone hotspot and join that network from this computer, or use a USB cable."
}

/// "No cable? Pair over Wi‑Fi", at the foot of every connect screen.
struct PairOverWiFiLink: View {
    @ObservedObject var model: BrowserModel
    @Binding var wifi: Bool
    var body: some View {
        HStack(spacing: 6) {
            Text("No cable?").foregroundStyle(Palette.muted)
            if Screenshots.active {
                Text("Pair over Wi‑Fi").foregroundStyle(Color.accentColor)
            } else {
                Button("Pair over Wi‑Fi") { wifi = true }.buttonStyle(.link).disabled(model.busy || model.connected)
            }
        }
        .font(.system(size: 13))
    }
}

/// A phone the shells can show and switch to: the open one, saved ones, ones announcing themselves on this Wi‑Fi,
/// and one on the cable that isn't open yet.
struct RosterPhone: Identifiable, Equatable {
    enum Kind { case open, saved, nearby, cable }
    let id: String
    let name: String
    let kind: Kind
    /// Connected or reachable over a cable (USB File transfer or Fast USB).
    var cable = false
    /// A saved phone announcing itself on this Wi‑Fi now.
    var here = false

    var caption: String {
        switch kind {
        case .open: return cable ? "Open, over the cable" : "Open"
        case .saved: return here ? "On this Wi‑Fi now" : "Saved"
        case .nearby: return "Nearby, not paired yet"
        case .cable: return "On the cable"
        }
    }
}

@MainActor
enum Roster {
    static func phones(_ model: BrowserModel, _ discovery: PhoneDiscovery) -> [RosterPhone] {
        var phones: [RosterPhone] = []
        let openID = model.currentID
        if model.connected {
            phones.append(RosterPhone(id: "open", name: model.deviceName, kind: .open, cable: model.overUSB || model.fastUSB))
        } else if let name = model.usbPairable {
            phones.append(RosterPhone(id: "cable", name: name, kind: .cable, cable: true))
        } else if model.usbNeedsAllow {
            phones.append(RosterPhone(id: "cable", name: "Phone on the cable", kind: .cable, cable: true))
        }
        for phone in model.saved where phone.id != openID {
            phones.append(RosterPhone(id: "saved-" + phone.id, name: phone.name, kind: .saved, here: model.isNearby(phone)))
        }
        let savedIDs = Set(model.saved.map(\.id))
        for phone in discovery.phones where !(phone.phoneID.map { savedIDs.contains($0) } ?? false) {
            phones.append(RosterPhone(id: "nearby-" + phone.id, name: phone.name, kind: .nearby))
        }
        return phones
    }

    /// Opens a phone from a switcher. Another phone than the open one disconnects that first.
    static func choose(_ phone: RosterPhone, model: BrowserModel, discovery: PhoneDiscovery) {
        switch phone.kind {
        case .open:
            model.showBrowser.toggle()
        case .cable:
            if model.usbPairable != nil { model.pairOverUSB() } else { model.connectFastUSB(asking: true) }
        case .saved:
            guard let saved = model.saved.first(where: { "saved-" + $0.id == phone.id }) else { return }
            if model.connected { model.disconnect() }
            model.connect(saved)
        case .nearby:
            guard let found = discovery.phones.first(where: { "nearby-" + $0.id == phone.id }) else { return }
            if model.connected { model.disconnect() }
            model.host = found.host; model.port = found.port
            model.pair(name: found.name)
        }
    }

    /// What VoiceOver and the tooltip say for a phone.
    static func label(_ phone: RosterPhone) -> String { "\(phone.name), \(phone.caption.lowercased())" }

    static func forgettable(_ phone: RosterPhone, model: BrowserModel) -> SavedPhone? {
        guard phone.kind == .saved else { return nil }
        return model.saved.first { "saved-" + $0.id == phone.id }
    }
}

/// How far a batch of transfers has come, as a whole: finished files plus the running one.
enum TransferProgress {
    static func overall(_ transfer: BrowserModel.Transfer) -> Double {
        if transfer.done { return 1 }
        let count: Double = Double(max(transfer.count, 1))
        let finished: Double = Double(max(transfer.index - 1, 0))
        return min(1, (finished + transfer.fraction) / count)
    }

    /// Stopped by an error, with something left to resume.
    @MainActor static func stopped(_ model: BrowserModel) -> Bool {
        guard let transfer = model.transfer else { return false }
        return !transfer.done && !model.busy
    }

    @MainActor static func summary(_ model: BrowserModel) -> String {
        guard let transfer = model.transfer else { return "" }
        if transfer.done { return transfer.upload ? "Uploaded to the phone" : "Done" }
        if stopped(model) { return "Stopped" }
        let percent = Int(overall(transfer) * 100)
        return transfer.count > 1 ? "\(percent)% · \(transfer.index) of \(transfer.count)" : "\(percent)%"
    }
}

/// The transfer queue: the running file with its progress and actions, and the files waiting after it. Aurora shows
/// it in the comet's popover.
struct TransferQueue: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        if let transfer = model.transfer {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: transfer.upload ? "arrow.up.circle.fill" : "arrow.down.circle.fill").foregroundStyle(Palette.amber)
                        .accessibilityHidden(true)
                    Text(transfer.name).lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 8)
                    Text(TransferProgress.summary(model)).foregroundStyle(Palette.muted).monospacedDigit()
                }
                .font(.system(size: 13, weight: .semibold))
                ProgressView(value: transfer.done ? 1 : transfer.fraction).tint(Palette.amber)
                    .accessibilityLabel("Progress of \(transfer.name)")
                waiting(transfer)
                TransferActions(model: model)
            }
            .padding(14)
            .frame(width: 320)
        }
    }

    @ViewBuilder private func waiting(_ transfer: BrowserModel.Transfer) -> some View {
        let next = transfer.remaining.dropFirst().prefix(5)
        if !next.isEmpty {
            VStack(alignment: .leading, spacing: 3) {
                Text("NEXT").font(.system(size: 10.5, weight: .semibold)).tracking(1.2).foregroundStyle(Palette.muted)
                ForEach(Array(next.enumerated()), id: \.offset) { _, job in
                    Text(job.name).font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1).truncationMode(.middle)
                }
                let more = transfer.remaining.count - 1 - next.count
                if more > 0 { Text("and \(more) more").font(.system(size: 12)).foregroundStyle(Palette.muted) }
            }
        }
    }
}

/// Cancel while running, Resume after an error, Show in Finder and Close when done: the same choices as Dusk's bar.
struct TransferActions: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        if let transfer = model.transfer {
            HStack(spacing: 8) {
                if transfer.done {
                    if let saved = transfer.saved {
                        Text("Saved").foregroundStyle(Palette.ok).bold()
                        Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([saved]) }.buttonStyle(.link)
                    } else {
                        Text(transfer.upload ? "Uploaded to the phone" : "Done").foregroundStyle(Palette.ok).bold()
                    }
                    Spacer(minLength: 4)
                    Button { model.transfer = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                        .help("Close").accessibilityLabel("Close")
                } else if !model.busy {
                    // Stopped by an error: continue where it left off.
                    Button("Resume", action: model.resume).buttonStyle(SunsetButton()).disabled(transfer.remaining.isEmpty)
                    Spacer(minLength: 4)
                    Button { model.transfer = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                        .help("Forget what's left").accessibilityLabel("Forget what's left")
                } else {
                    Spacer(minLength: 4)
                    Button("Cancel", action: model.cancel).buttonStyle(GhostButton())
                }
            }
            .font(.system(size: 13))
        }
    }
}

/// Opens OpenBridge's Settings window. macOS 14 only opens it through `SettingsLink`; macOS 13 through the menu action.
struct SettingsButton<Content: View>: View {
    @ViewBuilder var label: () -> Content

    var body: some View {
        if Screenshots.active { label() } else { link }
    }

    @ViewBuilder private var link: some View {
        if #available(macOS 14.0, *) {
            SettingsLink(label: label)
        } else {
            Button {
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            } label: { label() }
        }
    }
}

/// The browser is narrow or short: the looks switch to their compact layouts.
enum ShellSize {
    static func compact(_ size: CGSize) -> Bool { size.width < 900 || size.height < 560 }
}

/// A quiet line of advice on the Aurora and Tide connect screens, with an optional link-style action.
struct QuietHint: View {
    let symbol: String
    let text: String
    var action: String?
    var busy = false
    var perform: () -> Void = {}

    var body: some View {
        VStack(spacing: 4) {
            Label(text, systemImage: symbol)
                .font(.system(size: 12)).foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            if let action { Button(action, action: perform).buttonStyle(.link).font(.system(size: 12)).disabled(busy) }
        }
        .frame(maxWidth: 460)
    }
}

/// A phone sharing over the cable that this Mac hasn't paired with, for the Aurora and Tide connect screens (their
/// code shows in the hero, not here).
struct CablePairPrompt: View {
    @ObservedObject var model: BrowserModel
    let phone: String

    var body: some View {
        VStack(spacing: 8) {
            Text("\(phone) is sharing over the cable. Fast USB: instant search, real folder sizes, resumable copies.")
                .font(.system(size: 12.5)).foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Button("Not now", action: model.cancelUSBPairing).buttonStyle(GhostButton()).disabled(model.busy)
                Button("Pair over USB", action: model.pairOverUSB).buttonStyle(SunsetButton()).disabled(model.busy)
            }
        }
        .frame(maxWidth: 460)
    }
}

/// The 6-digit code as one large line, for showing inside or above a hero.
struct BigCode: View {
    let code: String
    var size: CGFloat = 34
    /// "Codes match?" under the digits; off where the screen already says it.
    var caption = true

    var body: some View {
        let spaced = code.count == 6 ? String(code.prefix(3)) + " " + String(code.suffix(3)) : code
        VStack(spacing: 2) {
            Text(spaced).font(.system(size: size, weight: .bold, design: .rounded)).monospacedDigit()
                .textSelection(.enabled)
            if caption { Text("Codes match? Tap Pair on the phone.").font(.system(size: 11)).foregroundStyle(Palette.muted) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Pairing code \(code.map { String($0) }.joined(separator: " ")). Codes match? Tap Pair on the phone.")
    }
}
