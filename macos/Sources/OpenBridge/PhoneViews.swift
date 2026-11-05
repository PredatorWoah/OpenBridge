import SwiftUI
import AppKit
import UniformTypeIdentifiers
import BridgeCore

// The phone features' views: the battery badge, the phone's notifications (in the menu bar panel and a sheet), Ring
// phone, Send link, the drop zone for Send to phone, the photo backup's progress, and the update hint.

/// "87%" with a battery and, while charging, a bolt. Nothing until the phone has said.
struct BatteryBadge: View {
    @ObservedObject var feed: PhoneFeed
    var size: CGFloat = 12

    var body: some View {
        if let battery = feed.battery {
            let low: Bool = battery.level <= 15 && !battery.charging
            HStack(spacing: 3) {
                Image(systemName: BatteryBadge.symbol(battery.level))
                    .accessibilityHidden(true)
                if battery.charging {
                    Image(systemName: "bolt.fill").font(.system(size: size * 0.8, weight: .bold))
                        .foregroundStyle(Palette.amber).accessibilityHidden(true)
                }
                Text("\(battery.level)%").monospacedDigit()
            }
            .font(.system(size: size, weight: .medium))
            .foregroundStyle(low ? Palette.amber : Palette.muted)
            .lineLimit(1)
            .fixedSize()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(BatteryBadge.label(battery))
            .help(BatteryBadge.label(battery))
        }
    }

    static func symbol(_ level: Int) -> String {
        if level >= 88 { return "battery.100" }
        if level >= 63 { return "battery.75" }
        if level >= 38 { return "battery.50" }
        if level >= 13 { return "battery.25" }
        return "battery.0"
    }

    static func label(_ battery: BatteryState) -> String {
        "Phone battery \(battery.level) percent" + (battery.charging ? ", charging" : "")
    }
}

/// One phone notification on a single line each for "App · Title" and the text, with its actions: Reply (a field
/// opens inline), Dismiss on phone, Copy code.
struct PhoneNotificationRow: View {
    @ObservedObject var model: BrowserModel
    let note: PhoneNotification
    var compact = true
    @State private var replying = false
    @State private var reply = ""
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(heading).font(.system(size: compact ? 12 : 13, weight: .semibold))
                    .lineLimit(1).truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(note.time, format: .dateTime.hour().minute())
                    .font(.system(size: 10.5)).foregroundStyle(Palette.muted).monospacedDigit().lineLimit(1).fixedSize()
            }
            if !note.text.isEmpty {
                Text(oneLine(note.text)).font(.system(size: compact ? 11.5 : 12.5)).foregroundStyle(Palette.muted)
                    .lineLimit(compact ? 1 : 3).truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if hasActions { actions }
            if replying { replyField }
        }
        .padding(.vertical, compact ? 6 : 8).padding(.horizontal, compact ? 8 : 10)
        .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    /// "WhatsApp · Mum": the app, then the title.
    private var heading: String {
        if note.title.isEmpty { return note.app }
        if note.app.isEmpty { return note.title }
        return note.app + " · " + note.title
    }

    private var hasActions: Bool { note.canReply || note.canDismiss || !note.code.isEmpty }

    private func oneLine(_ text: String) -> String {
        guard compact else { return text }
        return text.split(whereSeparator: \.isNewline).joined(separator: " ")
    }

    private var actions: some View {
        HStack(spacing: 12) {
            if note.canReply {
                Button(replying ? "Cancel" : "Reply") { replying.toggle(); reply = "" }
            }
            if note.canDismiss {
                Button("Dismiss") { model.act(on: note.key, .dismiss) }
                    .help("Dismiss this notification on the phone")
            }
            if !note.code.isEmpty {
                Button(copied ? "Copied \(note.code)" : "Copy code") {
                    model.copyText(note.code)
                    copied = true
                }
                .help("Copy \(note.code) to this Mac's clipboard")
            }
            Spacer(minLength: 0)
        }
        .buttonStyle(.plain)
        .font(.system(size: 11.5, weight: .semibold))
        .foregroundStyle(Palette.amber)
        .lineLimit(1)
    }

    @ViewBuilder private var replyField: some View {
        if Screenshots.active {
            Text("Reply…").font(.system(size: 12)).foregroundStyle(Palette.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(6).background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
        } else {
            HStack(spacing: 6) {
                TextField("Reply on the phone", text: $reply)
                    .textFieldStyle(.roundedBorder).font(.system(size: 12))
                    .onSubmit(send)
                Button("Send", action: send).disabled(reply.trimmingCharacters(in: .whitespaces).isEmpty)
                    .font(.system(size: 11.5, weight: .semibold))
            }
        }
    }

    private func send() {
        let text: String = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.act(on: note.key, .reply(text))
        reply = ""; replying = false
    }
}

/// The newest five notifications, in the menu bar panel, with "All" for the sheet.
struct PanelNotifications: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var feed: PhoneFeed
    var open: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("NOTIFICATIONS").font(.system(size: 10.5, weight: .semibold)).tracking(1.2).foregroundStyle(Palette.muted)
                Spacer(minLength: 4)
                if !feed.notifications.isEmpty {
                    Button("All (\(feed.notifications.count))") { model.showNotifications = true; open() }
                        .buttonStyle(.plain).font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.muted)
                }
            }
            if feed.notifications.isEmpty {
                Text("Nothing new on \(feed.phoneName.isEmpty ? "the phone" : feed.phoneName)")
                    .font(.system(size: 11.5)).foregroundStyle(Palette.muted).lineLimit(1).truncationMode(.tail)
            } else {
                ForEach(feed.notifications.prefix(5)) { note in
                    PhoneNotificationRow(model: model, note: note)
                }
            }
        }
    }
}

/// Every notification from the phone, newest first.
struct PhoneNotificationsSheet: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var feed: PhoneFeed
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "bell.badge.fill").foregroundStyle(Palette.amber).accessibilityHidden(true)
                Text("Phone notifications").font(.system(size: 20, weight: .bold, design: .rounded)).lineLimit(1)
                Spacer(minLength: 8)
                BatteryBadge(feed: feed, size: 12.5)
                Button { dismiss() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted)
                    .keyboardShortcut(.cancelAction).help("Close").accessibilityLabel("Close")
            }
            Text(subtitle).font(.system(size: 12.5)).foregroundStyle(Palette.muted).lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            if feed.notifications.isEmpty {
                Text("Notifications on the phone appear here while it's connected.")
                    .font(.system(size: 12.5)).foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, minHeight: 120)
            } else {
                FileScroll {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(feed.notifications) { note in
                            PhoneNotificationRow(model: model, note: note, compact: false)
                        }
                    }
                }
                .frame(minHeight: 160, maxHeight: 420)
            }
            settings
        }
        .padding(20)
        .frame(width: 480)
        .background(Palette.background)
        .preferredColorScheme(.dark)
    }

    private var subtitle: String {
        if !model.phoneFeatures { return "Connect a phone with OpenBridge 1.2 or later over Wi‑Fi or Fast USB." }
        return "From \(model.deviceName). Turn on Notifications on computers in OpenBridge on the phone to see them."
    }

    @ViewBuilder private var settings: some View {
        if Screenshots.active {
            Text("Show phone notifications on this Mac · Play sound").font(.system(size: 12)).foregroundStyle(Palette.muted)
        } else {
            HStack(spacing: 16) {
                Toggle("Show on this Mac", isOn: $feed.showOnMac)
                Toggle("Play sound", isOn: $feed.playSound).disabled(!feed.showOnMac)
            }
            .toggleStyle(.checkbox).font(.system(size: 12.5)).foregroundStyle(Palette.muted)
        }
    }
}

/// Ring phone and Send link, with the drop zone for Send to phone.
struct PanelPhoneActions: View {
    @ObservedObject var model: BrowserModel
    @State private var linking = false
    @State private var link = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button(action: model.toggleRing) {
                    Label(model.ringing ? "Stop ringing" : "Ring phone", systemImage: model.ringing ? "bell.slash" : "bell.and.waves.left.and.right")
                        .lineLimit(1)
                }
                .buttonStyle(PanelButton())
                .help(model.ringing ? "Stop the phone ringing" : "Ring the phone at full volume for up to a minute, even on silent")
                Button(action: sendLink) { Label("Send link", systemImage: "link").lineLimit(1) }
                    .buttonStyle(PanelButton())
                    .help("Open a web link on the phone: the one on the clipboard, or type one")
                Spacer(minLength: 0)
            }
            if linking { linkField }
            SendDropZone(model: model)
        }
    }

    private func sendLink() {
        if let found = model.clipboardLink() { model.sendLink(found); linking = false; return }
        linking.toggle()
    }

    @ViewBuilder private var linkField: some View {
        if Screenshots.active {
            Text("https://").font(.system(size: 12)).foregroundStyle(Palette.muted)
        } else {
            HStack(spacing: 6) {
                TextField("https://", text: $link)
                    .textFieldStyle(.roundedBorder).font(.system(size: 12))
                    .onSubmit(sendTyped)
                Button("Send", action: sendTyped).buttonStyle(PanelButton(prominent: true))
                    .disabled(!EventCodec.isWebLink(link))
            }
        }
    }

    private func sendTyped() {
        model.sendLink(link) { link = ""; linking = false }
    }
}

/// "Drop files to send to vivo X300 Pro": dropped files go to the phone's Download › OpenBridge.
struct SendDropZone: View {
    @ObservedObject var model: BrowserModel
    @State private var targeted = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 10, style: .continuous)
        HStack(spacing: 8) {
            Image(systemName: "arrow.up.doc").foregroundStyle(Palette.amber).accessibilityHidden(true)
            Text(label).font(.system(size: 12)).lineLimit(1).truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !Screenshots.active {
                Button("Choose…", action: model.chooseFilesToSend)
                    .buttonStyle(.plain).font(.system(size: 11.5, weight: .semibold)).foregroundStyle(Palette.muted)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 9)
        .background(Color.white.opacity(targeted ? 0.12 : 0.04), in: shape)
        .overlay(shape.strokeBorder(Palette.amber.opacity(targeted ? 0.8 : 0.35), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
        .onDrop(of: [UTType.fileURL], isTargeted: $targeted) { providers in
            SendDropZone.load(providers) { urls in model.sendToPhone(urls) }
            return true
        }
        .accessibilityElement(children: .combine)
    }

    private var label: String {
        let waiting: Int = model.pendingSends.count
        if waiting > 0 { return "\(waiting) waiting · drop more to send to \(model.sendTarget)" }
        return "Drop files to send to \(model.sendTarget)"
    }

    /// The file URLs in dropped items, delivered on the main actor.
    static func load(_ providers: [NSItemProvider], then deliver: @escaping @MainActor ([URL]) -> Void) {
        let group = DispatchGroup()
        let found = Collected()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url, url.isFileURL { found.add(url) }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            let urls: [URL] = found.all
            Task { @MainActor in deliver(urls) }
        }
    }

    /// URLs collected from several loading threads.
    private final class Collected: @unchecked Sendable {
        private let lock = NSLock()
        private var urls: [URL] = []
        func add(_ url: URL) { lock.lock(); urls.append(url); lock.unlock() }
        var all: [URL] { lock.lock(); defer { lock.unlock() }; return urls }
    }
}

/// The photo backup in the menu bar panel: the file, a bar, and the speed.
struct BackupLines: View {
    @ObservedObject var model: BrowserModel
    let backup: BackupProgress
    var now = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "photo.stack").foregroundStyle(Palette.amber).accessibilityHidden(true)
                Text("Backing up photos").font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                Spacer(minLength: 4)
                Text("\(backup.index) of \(backup.count)").font(.system(size: 11.5)).foregroundStyle(Palette.muted)
                    .monospacedDigit().lineLimit(1).fixedSize()
            }
            PanelProgressBar(fraction: backup.overall)
                .accessibilityElement()
                .accessibilityLabel("Photo backup progress")
                .accessibilityValue("\(Int(backup.overall * 100)) percent")
            HStack(spacing: 8) {
                Text(backup.name).lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 4)
                Text(speed).monospacedDigit().lineLimit(1).fixedSize()
            }
            .font(.system(size: 11.5)).foregroundStyle(Palette.muted)
        }
    }

    private var speed: String {
        if backup.paused { return "Paused for another transfer" }
        let rate: Double = model.backupSpeed.current(at: now)
        return rate > 0 ? MenuBarFormat.speed(rate) : "Measuring speed…"
    }
}

/// A slim line at the foot of the window while photos are backed up, in every look.
struct BackupStrip: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        if let backup = model.backup {
            HStack(spacing: 10) {
                Image(systemName: "photo.stack").foregroundStyle(Palette.amber).accessibilityHidden(true)
                Text(backup.paused ? "Photo backup waits for this transfer" : "Backing up photos · \(backup.index) of \(backup.count)")
                    .lineLimit(1).fixedSize()
                ProgressView(value: backup.overall).tint(Palette.amber).frame(width: 120)
                    .accessibilityLabel("Photo backup progress")
                Text(backup.name).foregroundStyle(Palette.muted).lineLimit(1).truncationMode(.middle)
                    .frame(maxWidth: 220, alignment: .leading)
            }
            .font(.system(size: 12))
            .padding(.horizontal, 14).padding(.vertical, 7)
            .glass(99)
        }
    }
}

/// Said once per phone when its OpenBridge is from before 1.2.0.
struct CompanionHintLine: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        if model.companionHint {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "arrow.down.app").foregroundStyle(Palette.amber).accessibilityHidden(true)
                Text("Update OpenBridge on the phone for notifications, Ring and Send.")
                    .font(.system(size: 12)).foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button { model.dismissCompanionHint() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted).font(.system(size: 10, weight: .bold))
                    .help("Hide").accessibilityLabel("Hide this hint")
            }
        }
    }
}
