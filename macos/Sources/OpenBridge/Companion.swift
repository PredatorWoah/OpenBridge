import SwiftUI
import AppKit
import BridgeCore

// The phone features of phone app 1.2.0 (INFO revision 23): notifications and battery (EVENTS), answering
// notifications, Ring phone, Open on phone, Send to phone, and the photo backup that uses the older operations.
// Older phones hide all of it, and the Mac says once that updating the phone app brings it.

extension BrowserModel {
    /// The connected phone answers operations 19 to 23.
    var phoneFeatures: Bool {
        guard connected, client is BridgeClient, let info = phoneInfo else { return false }
        return info.revision >= BridgeClient.phoneFeaturesRevision
    }

    /// The phone to send to, by name, for "Drop files to send to …".
    var sendTarget: String { connected ? deviceName : (saved.first?.name ?? "your phone") }

    // MARK: Connecting and disconnecting

    /// INFO answered (or the phone is too old for it): start what the phone supports, or say once that it can't.
    func companionReady(_ client: BridgeClient, revision: Int) {
        guard let phone = current else { return }
        if revision >= BridgeClient.phoneFeaturesRevision {
            companionHint = false
            startEvents(client, phone: phone)
        } else {
            let key = "companionHintShown." + phone.id
            if BrowserModel.stored?.bool(forKey: key) != true {
                BrowserModel.stored?.set(true, forKey: key)
                companionHint = true
                Diagnostics.log("Phone app has no notifications, Ring or Send (revision \(revision))")
            }
        }
        startBackups(for: phone)
    }

    /// The connection ended: stop asking, keep each phone's cursor and notifications for when it's back.
    func companionStopped() {
        eventsTask?.cancel(); eventsTask = nil
        ringTask?.cancel(); ringTask = nil
        backupTask?.cancel(); backupTask = nil
        backup = nil
        ringing = false
        companionHint = false
        feed.close()
    }

    func dismissCompanionHint() { companionHint = false }

    /// Called about every 1.5 seconds while connected and nothing runs: sends what's queued.
    func companionTick() {
        guard !busy, transfer.map({ $0.done }) ?? true, !pendingSends.isEmpty, phoneFeatures else { return }
        let files: [URL] = pendingSends
        pendingSends = []
        start(files.map { Job.send($0) })
    }

    // MARK: Notifications and battery

    /// Long-polls EVENTS while this connection lasts, continuing from the phone's last cursor.
    private func startEvents(_ client: BridgeClient, phone: SavedPhone) {
        eventsTask?.cancel()
        let epoch: Int = connection
        _ = feed.open(phoneID: phone.id, name: phone.name)
        Diagnostics.log("Listening for phone events")
        eventsTask = Task {
            var failures = 0
            while !Task.isCancelled {
                let cursor: Int64 = feed.cursor
                do {
                    let batch: EventBatch = try await client.events(cursor: cursor, wait: 20_000)
                    guard epoch == connection, !Task.isCancelled else { return }
                    feed.markLive()
                    feed.apply(batch)
                    failures = 0
                } catch let error where BridgeError.isUnsupported(error) {
                    return
                } catch {
                    guard epoch == connection, !Task.isCancelled else { return }
                    if BrowserModel.isNetworkError(error) { checkAlive(reason: "waiting for phone events failed") }
                    failures += 1
                    let seconds: UInt64 = UInt64(min(failures, 5) * 2)
                    try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
                }
            }
        }
    }

    /// Dismisses or answers a phone notification, from the menu bar, the Notifications sheet or a macOS banner.
    func act(on key: String, phoneID: String? = nil, _ action: NotificationAction) {
        guard let bridge = client as? BridgeClient, phoneFeatures, phoneID == nil || phoneID == current?.id else {
            let message = "Connect to the phone first, then try again."
            show(message)
            Announce.notification(message)
            return
        }
        Task {
            do {
                try await bridge.notificationAction(key: key, action)
                switch action {
                case .dismiss:
                    feed.remove(key)
                    show("Dismissed on the phone")
                case .reply:
                    show("Reply sent")
                    Diagnostics.log("Answered a phone notification")
                }
            } catch {
                show(error.localizedDescription)
                Announce.notification(error.localizedDescription)
            }
        }
    }

    /// A banner was clicked: OpenBridge comes forward with its notifications.
    func openNotificationsWindow() {
        NSApp.unhide(nil)
        NSApp.activate(ignoringOtherApps: true)
        for window in NSApp.windows where window.canBecomeMain && window.isVisible {
            window.makeKeyAndOrderFront(nil)
        }
        showNotifications = true
    }

    // MARK: Ring phone

    /// Rings the phone for up to 60 seconds, or stops it.
    func toggleRing() {
        guard phoneFeatures, let bridge = client as? BridgeClient else { return }
        let on: Bool = !ringing
        ringing = on
        ringTask?.cancel(); ringTask = nil
        if on {
            ringTask = Task {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                if !Task.isCancelled { ringing = false }
            }
        }
        Task {
            do {
                try await bridge.ring(on)
                if on { show("Ringing \(deviceName)") }
            } catch {
                ringing = false
                show(error.localizedDescription)
            }
        }
    }

    // MARK: Open on phone

    /// A web link on the Mac's clipboard, if there is one.
    func clipboardLink() -> String? {
        let pasteboard = NSPasteboard.general
        let text: String? = pasteboard.string(forType: .URL) ?? pasteboard.string(forType: .string)
        guard let text else { return nil }
        let trimmed: String = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return EventCodec.isWebLink(trimmed) ? trimmed : nil
    }

    /// Shows a link on the phone as a notification that opens it. `sent` runs when it's there.
    func sendLink(_ link: String, sent: @escaping () -> Void = {}) {
        let trimmed: String = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard EventCodec.isWebLink(trimmed) else { show("That isn't a web link (http or https)."); return }
        guard let bridge = client as? BridgeClient, connected else { notConnected("send the link"); return }
        Task {
            do {
                if phoneFeatures {
                    try await bridge.openURL(trimmed)
                    show("Link sent to \(deviceName)")
                } else {
                    // An older phone app: the link goes to its clipboard instead.
                    try await bridge.setClipboard(trimmed)
                    texts.add(trimmed, fromPhone: false)
                    show("Link copied to the phone's clipboard")
                }
                sent()
            } catch {
                show(error.localizedDescription)
                Announce.notification(error.localizedDescription)
            }
        }
    }

    /// Text from Services: onto the phone's clipboard.
    func sendTextToPhone(_ text: String) {
        guard client is BridgeClient, connected else { notConnected("send the text"); return }
        sendText(text) {}
        if let problem = textProblem { Announce.notification(problem) }
    }

    private func notConnected(_ what: String) {
        let message = "Connect your phone with OpenBridge to \(what)."
        show(message)
        Announce.notification(message)
    }

    // MARK: Send to phone

    /// Sends files to the connected phone (no folder to choose), or keeps them until one is connected and free.
    /// Folders are left out: the phone takes files.
    func sendToPhone(_ urls: [URL]) {
        var files: [URL] = []
        var skipped = 0
        for url in urls where url.isFileURL {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            if values?.isRegularFile == true && values?.isSymbolicLink != true { files.append(url) } else { skipped += 1 }
        }
        if skipped > 0 { show("Folders can't be sent this way. Send the files inside them.") }
        guard !files.isEmpty else { return }
        let fresh: [URL] = files.filter { !pendingSends.contains($0) }
        pendingSends += fresh
        let count: Int = pendingSends.count
        let what: String = count == 1 ? "“\(pendingSends[0].lastPathComponent)”" : "\(count) files"
        if phoneFeatures {
            if busy || transfer.map({ !$0.done }) == true { show("\(what) will go to \(deviceName) after this transfer") }
            companionTick()
            return
        }
        let message: String
        if connected && client is BridgeClient && phoneInfo != nil || phoneOutdated {
            message = "Update OpenBridge on \(deviceName) to send files this way. \(what) will wait."
        } else if connected {
            message = "Send to phone works over Wi‑Fi and Fast USB. \(what) will wait."
        } else {
            message = "\(what) will go to \(sendTarget) when it connects."
        }
        show(message)
        Announce.notification(message)
    }

    func cancelPendingSends() { pendingSends = [] }

    /// Files went to the phone: say what the phone named them.
    func didSend(_ sent: [(name: String, saved: String)]) {
        let message: String
        if sent.count == 1 {
            let item = sent[0]
            message = item.saved == item.name ? "Sent “\(item.name)” to \(deviceName)"
                : "Sent “\(item.name)” to \(deviceName) as “\(item.saved)”"
        } else {
            message = "Sent \(sent.count) files to \(deviceName), in Download › OpenBridge"
        }
        show(message)
        Announce.spoken(message)
        Announce.notification(message)
        Diagnostics.log("Sent \(sent.count) file(s) to the phone")
    }

    func chooseFilesToSend() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = true
        panel.prompt = "Send"
        panel.message = "Choose files to send to \(sendTarget). They go to Download › OpenBridge on the phone."
        guard panel.runModal() == .OK else { return }
        sendToPhone(panel.urls)
    }

    // MARK: Photo backup

    /// Backs up new photos on connect, then every 10 minutes while connected, when this phone has it on.
    private func startBackups(for phone: SavedPhone) {
        backupTask?.cancel()
        let epoch: Int = connection
        backupTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)   // let the connection settle first
            while !Task.isCancelled && epoch == connection {
                if BackupSettings.enabled(phone.id), backupDue(phone.id), idleForBackup {
                    await backUp(phone, epoch: epoch)
                    lastBackup[phone.id] = Date()
                }
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    /// Runs the backup now (from Settings).
    func backUpNow() {
        guard let phone = current, connected, backup == nil else { return }
        lastBackup[phone.id] = nil
        let epoch: Int = connection
        Task {
            await backUp(phone, epoch: epoch, always: true)
            lastBackup[phone.id] = Date()
        }
    }

    private func backupDue(_ phoneID: String) -> Bool {
        guard let last = lastBackup[phoneID] else { return true }
        return Date().timeIntervalSince(last) >= 600
    }

    /// Nothing else is moving: no folder loading, transfer or backup.
    private var idleForBackup: Bool {
        !busy && backup == nil && (transfer.map { $0.done } ?? true)
    }

    /// Another transfer is running: the backup waits before its next file.
    private var transferRunning: Bool { busy && transfer.map { !$0.done } == true }

    /// Copies the camera's photos and videos that weren't imported from this phone before into the phone's backup
    /// folder. Uses the import record, so what "Import new photos" brought over is skipped too. Runs beside the UI.
    private func backUp(_ phone: SavedPhone, epoch: Int, always: Bool = false) async {
        guard always || BackupSettings.enabled(phone.id), let client, epoch == connection, backup == nil else { return }
        let destination: URL = BackupSettings.folder(for: phone)
        do {
            try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
            var found = try await resolve(["DCIM", "Camera"], client)
            if found == nil { found = try await resolve(["DCIM"], client) }
            guard epoch == connection, let (_, entries) = found else { return }
            let media: [Entry] = entries.filter { !$0.directory && FileKind($0).previewable }
            let record: Set<String> = Imports.load(phone.id)
            let fresh: [Entry] = media.filter { !record.contains(Imports.line($0)) && !Imports.present($0, in: destination) }
            guard !fresh.isEmpty else {
                Imports.save(phone.id, record.union(media.map(Imports.line)))
                if always { show("No new photos to back up") }
                return
            }
            Diagnostics.log("Backing up \(fresh.count) new photo(s)")
            var copied = 0
            backupSpeed.reset()
            for (index, entry) in fresh.enumerated() {
                backup = BackupProgress(phone: phone.name, name: entry.name, index: index + 1, count: fresh.count,
                                        fraction: 0, paused: false)
                // Another transfer goes first: wait for it.
                while transferRunning && epoch == connection && !Task.isCancelled {
                    backup?.paused = true
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                }
                backup?.paused = false
                guard epoch == connection, !Task.isCancelled else { break }
                // Imported meanwhile ("Import new photos"): skip.
                if Imports.load(phone.id).contains(Imports.line(entry)) { continue }
                let target: URL = BrowserModel.free(destination.appendingPathComponent(BrowserModel.macName(entry.name)))
                backupSpeed.restartSample()
                try await client.download(entry, to: target) { done, total in
                    self.backup?.fraction = total == 0 ? 0 : Double(done) / Double(total)
                    self.backupSpeed.record(done)
                }
                var updated: Set<String> = Imports.load(phone.id)
                updated.insert(Imports.line(entry))
                Imports.save(phone.id, updated)
                copied += 1
            }
            backup = nil
            if copied > 0 {
                let noun: String = copied == 1 ? "photo" : "photos"
                PhoneNotifier.shared.notice(title: "Backed up \(copied) \(noun) from \(phone.name)",
                                            body: "In \(destination.path.replacingOccurrences(of: NSHomeDirectory(), with: "~"))")
                show("Backed up \(copied) \(noun) from \(phone.name)")
                Diagnostics.log("Backed up \(copied) photo(s)")
            }
        } catch {
            backup = nil
            if epoch == connection { Diagnostics.log("Photo backup stopped: \(BrowserModel.redacted(error.localizedDescription))") }
        }
    }
}

/// Photo backup per saved phone: on or off, and where to (by default ~/Pictures/OpenBridge/<phone name>).
enum BackupSettings {
    private static var defaults: UserDefaults? { Screenshots.active ? nil : UserDefaults.standard }

    static func enabled(_ phoneID: String) -> Bool { defaults?.bool(forKey: "backup." + phoneID) ?? false }

    static func setEnabled(_ on: Bool, for phoneID: String) { defaults?.set(on, forKey: "backup." + phoneID) }

    static func folder(for phone: SavedPhone) -> URL {
        if let path = defaults?.string(forKey: "backupFolder." + phone.id) { return URL(fileURLWithPath: path, isDirectory: true) }
        return defaultFolder(for: phone.name)
    }

    static func defaultFolder(for name: String) -> URL {
        let pictures: URL = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Pictures", isDirectory: true)
        let safe: String = Wire.fitted(name.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-"), limit: 120)
        let folder: String = safe.trimmingCharacters(in: .whitespaces).isEmpty ? "Phone" : safe
        return pictures.appendingPathComponent("OpenBridge", isDirectory: true).appendingPathComponent(folder, isDirectory: true)
    }

    /// Asks for a folder; nil when cancelled.
    @MainActor static func choose(for phone: SavedPhone) -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false; panel.canChooseDirectories = true; panel.canCreateDirectories = true
        panel.directoryURL = folder(for: phone).deletingLastPathComponent()
        panel.prompt = "Back Up Here"
        panel.message = "Choose where new photos and videos from \(phone.name) are backed up."
        guard panel.runModal() == .OK, let chosen = panel.url else { return nil }
        defaults?.set(chosen.path, forKey: "backupFolder." + phone.id)
        return chosen
    }
}

/// Files and texts handed to OpenBridge from outside: dropped on the Dock icon, Open With › OpenBridge, or the
/// Services menu. Kept until the model is ready; the same file twice within a moment counts once (the app delegate
/// and SwiftUI may both report one drop).
@MainActor
final class PhoneInbox {
    static let shared = PhoneInbox()
    weak var model: BrowserModel? { didSet { flush() } }
    private var waiting: [URL] = []
    private var recent: [URL: Date] = [:]

    func receive(_ urls: [URL]) {
        let now = Date()
        recent = recent.filter { now.timeIntervalSince($0.value) < 3 }
        let fresh: [URL] = urls.filter { url in url.isFileURL && recent[url] == nil }
        for url in fresh { recent[url] = now }
        guard !fresh.isEmpty else { return }
        waiting += fresh
        flush()
    }

    private func flush() {
        guard let model, !waiting.isEmpty else { return }
        let urls: [URL] = waiting
        waiting = []
        model.sendToPhone(urls)
    }
}

/// The Services menu entry "Send to Phone with OpenBridge" (Info.plist NSServices): files are sent, a web link opens
/// on the phone, other text goes to the phone's clipboard.
final class PhoneServices: NSObject {
    static let shared = PhoneServices()

    @objc func sendToPhone(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        let files: [URL] = (pasteboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL]) ?? []
        if !files.isEmpty {
            Task { @MainActor in PhoneInbox.shared.receive(files) }
            return
        }
        let link: String? = pasteboard.string(forType: .URL)
        let text: String? = link ?? pasteboard.string(forType: .string)
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            error.pointee = "There's nothing to send." as NSString
            return
        }
        Task { @MainActor in
            guard let model = PhoneInbox.shared.model else { return }
            if EventCodec.isWebLink(text) { model.sendLink(text) } else { model.sendTextToPhone(text) }
        }
    }
}
