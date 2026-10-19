import SwiftUI
import AppKit
import BridgeCore

/// What a file is, from its name: decides its icon, colour, sorting group and whether it has a preview.
enum FileKind: Int, Comparable {
    case folder, image, video, audio, document, archive, app, other

    init(_ entry: Entry) {
        if entry.directory { self = .folder; return }
        switch (entry.name as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "bmp": self = .image
        case "mp4", "mov", "mkv", "3gp", "webm", "m4v", "avi": self = .video
        case "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus": self = .audio
        case "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "rtf", "csv": self = .document
        case "zip", "rar", "7z", "tar", "gz", "iso": self = .archive
        case "apk", "xapk", "apks": self = .app
        default: self = .other
        }
    }
    var previewable: Bool { self == .image || self == .video }
    static func < (a: FileKind, b: FileKind) -> Bool { a.rawValue < b.rawValue }
}

enum SortKey: String, CaseIterable, Identifiable {
    case name = "Name", size = "Size", kind = "Kind"
    var id: String { rawValue }
}

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
    /// A short confirmation that fades away, like "Copied the phone's clipboard".
    @Published var toast: String?
    @Published var deviceName = "Phone"
    @Published var overUSB = false
    /// Whether the file browser is showing (instead of the connect screen).
    @Published var showBrowser = false
    @Published var selection: Set<Entry.ID> = []
    /// The item the keyboard is on (arrow keys move it); it shows a ring and stays scrolled into view.
    @Published var cursor: Entry.ID?
    /// Items per row in the grid, so ↑ and ↓ move by a row.
    @Published var gridColumns = 1
    @Published var transfer: Transfer?
    @Published var thumbnails: [Entry.ID: NSImage] = [:]
    /// A downloaded copy shown with Quick Look.
    @Published var previewURL: URL?
    @Published var sortKey: SortKey = SortKey(rawValue: UserDefaults.standard.string(forKey: "sortKey") ?? "") ?? .name {
        didSet { UserDefaults.standard.set(sortKey.rawValue, forKey: "sortKey") }
    }
    @Published var ascending = !UserDefaults.standard.bool(forKey: "sortDescending") {
        didSet { UserDefaults.standard.set(!ascending, forKey: "sortDescending") }
    }
    @Published var grid = UserDefaults.standard.bool(forKey: "gridView") {
        didSet { UserDefaults.standard.set(grid, forKey: "gridView") }
    }
    /// Phones paired before, which reconnect by themselves while sharing.
    @Published private(set) var saved: [SavedPhone] = SavedPhones.load()
    /// Phones sharing on this Wi‑Fi; runs for the whole session.
    let discovery = PhoneDiscovery()
    /// Filters for the open folder; clearing it closes search results.
    @Published var query = "" { didSet { if query.isEmpty { results = nil } } }
    /// Results of searching every folder below the open one.
    @Published private(set) var results: SearchResult?
    @Published private(set) var resultsFor = ""
    @Published var mediaOnly = false
    /// Folder sizes counted on the phone (Wi‑Fi only).
    @Published private(set) var sizes: [Entry.ID: FolderSize] = [:]
    /// Items waiting for the user to confirm deletion, and the item being renamed.
    @Published var pendingDelete: [Entry] = []
    @Published var renaming: Entry?
    @Published private(set) var update: AvailableUpdate?
    let updater = Updater()
    let texts = TextHistory()
    /// Fast USB: the phone's own sharing reached through USB debugging. (`overUSB` means File transfer, MTP.)
    @Published private(set) var fastUSB = false
    /// A phone with USB debugging on is plugged in.
    @Published private(set) var usbDebugging = false
    /// USB debugging is on, but the phone hasn't allowed this Mac yet: offer to ask it.
    @Published private(set) var usbNeedsAllow = false
    /// OpenBridge answers over the cable, but this Mac hasn't paired with that phone: offer "Pair over USB".
    @Published private(set) var usbPairable: String?
    private var usbCandidate: FastUSB.Found?
    private var tunnel: ADBTunnel?
    /// A phone with USB debugging that Fast USB already tried, or that was disconnected: not tried again until replugged.
    private var fastSkipped: String?
    /// What INFO said about the connected phone (from 0.13.0).
    @Published private(set) var phoneInfo: PhoneInfo?
    /// The connected phone's app is from before 0.13.0: it sends no texts and has no Fast USB.
    @Published private(set) var phoneOutdated = false
    /// The Wi‑Fi seems to keep devices apart: a phone it announces can't be reached.
    @Published private(set) var wifiIsolated = false
    private var unreachable: [String: Int] = [:]
    private var gatewayChecked: (address: String?, at: Date)?
    @Published var showTexts = false
    @Published private(set) var textProblem: String?
    /// Off by default: what's copied on this Mac goes to the phone's clipboard while connected.
    @Published var autoSendTexts = UserDefaults.standard.bool(forKey: "autoSendTexts") {
        didSet { UserDefaults.standard.set(autoSendTexts, forKey: "autoSendTexts"); watchClipboard() }
    }
    private var clipboardTimer: Timer?
    private var pasteboardSeen = 0
    /// The clipboard's change count right after OpenBridge put the phone's text there, and the last text that went
    /// either way, so automatic sending never echoes the phone's own text back (mirroring syncs the clipboard too).
    private var fromPhone = -1
    private var lastShared: String?
    /// The saved phone connected now, when the connection came from one.
    private var current: SavedPhone?
    /// Off after Disconnect, so a saved phone doesn't reconnect straight away.
    private var autoConnect = true
    private var probing = false
    private var polls = 0
    private var sizeQueue: [Entry] = []
    private var sizing = false
    private var sizesPending: Set<Entry.ID> = []
    /// Collects files sent from the phone while a Wi‑Fi connection lasts.
    private var receiver: Task<Void, Never>?
    /// The last file sent from the phone.
    @Published private(set) var received: URL?
    private(set) var client: (any FileSource)?
    private var task: Task<Void, Never>?
    private var watcher: Task<Void, Never>?
    private var toastTask: Task<Void, Never>?
    /// A plugged-in phone that is not opened automatically: it failed to connect, or the user disconnected it.
    /// Cleared when that phone is unplugged.
    private var skipped: String?
    private var anchor: Entry.ID?
    private var thumbnailsPending: Set<Entry.ID> = []
    private var thumbnailsFailed: Set<Entry.ID> = []
    /// Previews waiting their turn, the most recently shown last. They load a few at a time so browsing and
    /// transfers never queue behind them (over USB every request shares one cable).
    private var thumbnailQueue: [Entry] = []
    private var thumbnailsRunning = 0
    /// Bumped when previews are reset, so a preview from an older connection or folder is ignored.
    private var thumbnailEpoch = 0
    private let previews = FileManager.default.temporaryDirectory.appendingPathComponent("OpenBridge Previews", isDirectory: true)

    /// A batch of transfers. Whatever is left after a failure can be resumed.
    struct Transfer {
        var name: String
        var upload: Bool
        var index = 1
        var count = 1
        var fraction = 0.0
        var done = false
        var saved: URL?
        /// Jobs not finished yet (including the one that failed), for Resume.
        var remaining: [Job] = []
        /// The connection the jobs' IDs belong to. Phones hand out new IDs on every connection.
        var connection = 0
        /// The phone the transfer was for, to continue it automatically when that phone comes back.
        var phone = ""
    }

    /// One transfer. `at` is the phone folder by name (from the top), so the job can be found again after
    /// a reconnect, when the old IDs no longer mean anything.
    enum Job {
        case download(Entry, to: URL, at: [String])
        case upload(URL, into: String, at: [String])
        var name: String {
            switch self {
            case .download(let entry, _, _): return entry.name
            case .upload(let url, _, _): return url.lastPathComponent
            }
        }
    }

    /// Bumped on every new connection: IDs from an older one must be looked up again by name.
    private var connection = 0
    /// A transfer cut off by unplugging or disconnecting, continued when the same phone reconnects.
    private var interrupted: Transfer?
    /// The open folder by name, from the top (without the phone itself).
    private var trail: [String] { path.dropFirst().map(\.name) }

    private func run(_ work: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        task = Task {
            defer { busy = false; task = nil; pairingCode = nil; pumpThumbnails(); pumpSizes() }
            do { try await work() }
            catch is CancellationError { status = "Cancelled."; transfer = nil }
            catch BridgeError.forgotten {
                // The phone no longer trusts this Mac: forget it here too, and offer to pair again.
                if let current { forget(current) }
                if connected && !overUSB { drop(BridgeError.forgotten.localizedDescription) }
                else { self.error = BridgeError.forgotten.localizedDescription }
            }
            catch {
                self.error = error.localizedDescription
                Diagnostics.log("Failed (\(transportName)): \(BrowserModel.redacted(error.localizedDescription))")
                status = overUSB
                    ? "Couldn't reach the phone over USB. Keep it unlocked, plugged in and in File transfer mode."
                    : fastUSB ? "Couldn't reach the phone over the cable. Keep it plugged in, with OpenBridge sharing."
                    : "Couldn't reach the phone. Check Wi‑Fi and that sharing is still on, or pair again."
            }
        }
    }

    /// An error message for the diagnostics log: names in quotes (files, folders) left out.
    static func redacted(_ message: String) -> String {
        if message.contains("already exists") { return "a file or folder with that name already exists" }
        return message.replacingOccurrences(of: "“[^”]*”", with: "“…”", options: .regularExpression)
    }

    var transportName: String {
        guard connected else { return "not connected" }
        return fastUSB ? "Fast USB" : overUSB ? "USB File transfer" : "Wi‑Fi"
    }

    func show(_ message: String) {
        toast = message
        toastTask?.cancel()
        toastTask = Task { try? await Task.sleep(nanoseconds: 2_500_000_000); if !Task.isCancelled { toast = nil } }
    }

    // MARK: Connecting

    /// Watches for a phone being plugged in or unplugged, so USB needs no button.
    func startWatching() {
        guard watcher == nil else { return }
        discovery.start()
        if let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String {
            Task { await self.checkForUpdates(current: version) }
        }
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
        let debugging = USBDebugging.attachedPhone()
        if attached == nil { skipped = nil }
        if debugging == nil {
            if usbDebugging { Diagnostics.log("USB debugging: phone gone") }
            fastSkipped = nil; usbNeedsAllow = false
            if usbPairable != nil { forgetUSBCandidate() }
            USBDebugging.shared.disconnect()
        } else if !usbDebugging {
            Diagnostics.log("USB debugging: phone plugged in")
        }
        usbDebugging = debugging != nil
        if connected {
            if overUSB && attached == nil { drop("The phone was unplugged.") }
            else if fastUSB && debugging == nil { drop("The phone was unplugged.") }
            else if !overUSB && !fastUSB, let debugging, debugging != fastSkipped { upgradeToFastUSB(debugging) }
            return
        }
        if usbPairable != nil { return }   // waiting for "Pair over USB"
        // Fast USB first; File transfer when USB debugging is off or doesn't reach OpenBridge.
        if let debugging, debugging != fastSkipped { connectFastUSB(); return }
        if let attached, attached != skipped { connectUSB(); return }
        // About every 4.5 seconds, look for a saved phone that's sharing.
        polls += 1
        if polls % 3 == 0, autoConnect, !saved.isEmpty, !probing { reconnectSaved() }
    }

    // MARK: Saved phones

    /// Where a saved phone is now: its announced address on this Wi‑Fi, or where it was last time.
    private func located(_ phone: SavedPhone) -> SavedPhone {
        guard let seen = discovery.phones.first(where: { $0.phoneID == phone.id }), let port = UInt16(seen.port) else { return phone }
        var moved = phone
        moved.host = seen.host; moved.port = port
        return moved
    }

    func isNearby(_ phone: SavedPhone) -> Bool { discovery.phones.contains { $0.phoneID == phone.id } }

    /// Tries each saved phone quietly; the first that answers opens. Each is tried where it was or is announced, and
    /// at the default gateway: on the phone's own hotspot, the phone is the gateway.
    private func reconnectSaved() {
        probing = true
        let phones = saved.map(located)
        Task {
            defer { probing = false }
            let gateway = await currentGateway()
            for phone in phones {
                var candidates = [(phone, false)]
                if let gateway, gateway != phone.host {
                    var hotspot = phone
                    hotspot.host = gateway; hotspot.port = 41987
                    candidates.append((hotspot, true))
                }
                for (candidate, viaGateway) in candidates {
                    guard let client = try? candidate.client() else { continue }
                    do {
                        let entries = try await client.list(id: "", quick: true)
                        guard !connected, !busy, autoConnect else { return }
                        if viaGateway { Diagnostics.log("Reached a saved phone at the default gateway (its hotspot)") }
                        adopt(client, as: candidate, entries: entries)
                        return
                    } catch BridgeError.forgotten where !viaGateway {
                        forget(phone)
                        error = "\(phone.name): \(BridgeError.forgotten.localizedDescription)"
                        break
                    } catch {
                        // Another phone at the gateway, or nothing there: not this one.
                        if !viaGateway && isNearby(phone) { noteUnreachable(phone.id) }
                    }
                }
            }
        }
    }

    private func currentGateway() async -> String? {
        if let checked = gatewayChecked, Date().timeIntervalSince(checked.at) < 20 { return checked.address }
        let address = await LocalNetwork.gateway()
        gatewayChecked = (address, Date())
        return address
    }

    /// A phone announced on this Wi‑Fi that can't be reached a few times in a row: the network keeps devices apart.
    private func noteUnreachable(_ key: String) {
        unreachable[key, default: 0] += 1
        guard unreachable[key, default: 0] >= 3, !wifiIsolated else { return }
        wifiIsolated = true
        Diagnostics.log("Wi‑Fi: a phone is announced but can't be reached (client isolation?)")
    }

    /// Opens a saved phone now (the user clicked it).
    func connect(_ phone: SavedPhone) {
        guard !busy else { return }
        overUSB = false
        let target = located(phone)
        if target.host.isEmpty {
            if usbDebugging { fastSkipped = nil; connectFastUSB() }
            else { error = "\(phone.name) was paired over USB. Plug it in with USB debugging on, or start sharing on the same Wi‑Fi." }
            return
        }
        run {
            self.status = "Connecting to \(phone.name)…"
            Diagnostics.log("Wi‑Fi: connecting to a saved phone at \(target.host)")
            let client = try target.client()
            self.adopt(client, as: target, entries: try await client.list())
        }
    }

    func forget(_ phone: SavedPhone) {
        saved.removeAll { $0.id == phone.id }
        SavedPhones.save(saved)
    }

    /// Shows a phone connected over Wi‑Fi or Fast USB and remembers it. Over Fast USB the saved Wi‑Fi address stays.
    private func adopt(_ client: BridgeClient, as phone: SavedPhone, entries: [Entry], fastUSB: Bool = false) {
        overUSB = false
        self.fastUSB = fastUSB
        if !fastUSB { tunnel?.stop(); tunnel = nil }
        self.client = client; deviceName = phone.name; host = fastUSB ? "" : phone.host
        path = [("", "Phone")]; self.entries = entries
        connected = true; status = ""; error = nil
        current = phone; autoConnect = true
        wifiIsolated = false; unreachable = [:]
        phoneInfo = nil; phoneOutdated = false
        saved = [phone] + saved.filter { $0.id != phone.id }
        SavedPhones.save(saved)
        Diagnostics.log(fastUSB ? "Connected over Fast USB" : "Connected over Wi‑Fi at \(phone.host):\(phone.port)")
        startReceiving(client)
        watchClipboard()
        didConnect(to: phone.name)
        askInfo(client)   // after didConnect: it belongs to this connection
    }

    // MARK: Files sent from the phone

    static var receivedFolder: URL {
        (FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first ?? FileManager.default.homeDirectoryForCurrentUser)
            .appendingPathComponent("OpenBridge", isDirectory: true)
    }

    /// Collects what's sent from the phone while this connection lasts: files into Downloads/OpenBridge, texts onto
    /// the clipboard (and into Texts).
    private func startReceiving(_ phone: BridgeClient) {
        receiver?.cancel()
        let name = deviceName
        receiver = Task {
            var withTexts = true
            while !Task.isCancelled {
                let waiting: [OutboxItem]
                do {
                    if withTexts { waiting = try await phone.outboxAll(wait: 20_000) }
                    else { waiting = try await phone.outbox(wait: 20_000).map { OutboxItem(entry: $0, text: false) } }
                } catch let error where withTexts && BridgeError.isUnsupported(error) {
                    withTexts = false   // a phone app before 0.13.0 sends only files
                    continue
                } catch BridgeError.message(let text) where text.contains("Unsupported operation") || text.contains("not available") {
                    return   // a phone app before 0.12.0 can't send: stop asking
                } catch {
                    try? await Task.sleep(nanoseconds: 3_000_000_000); continue
                }
                for item in waiting {
                    guard !Task.isCancelled else { return }
                    do {
                        if item.text {
                            let text = try await phone.text(item.entry)
                            receivedText(text, from: name)
                            try await phone.done(item.entry)
                        } else {
                            try? FileManager.default.createDirectory(at: Self.receivedFolder, withIntermediateDirectories: true)
                            let target = Self.free(Self.receivedFolder.appendingPathComponent(Self.macName(item.entry.name)))
                            try await phone.receive(item.entry, to: target)
                            received = target
                            show("Received “\(item.entry.name)” from the phone")
                            Diagnostics.log("Received a file from the phone")
                        }
                    } catch {
                        Diagnostics.log("Receiving from the phone stopped: \(BrowserModel.redacted(error.localizedDescription))")
                        try? await Task.sleep(nanoseconds: 2_000_000_000); break
                    }
                }
            }
        }
    }

    /// A text from the phone: onto the clipboard, into Texts, and said out loud.
    private func receivedText(_ text: String, from phone: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        fromPhone = pasteboard.changeCount
        pasteboardSeen = fromPhone
        lastShared = text
        texts.add(text, fromPhone: true)
        let message = "Text from \(phone) copied"
        show(message)
        Announce.spoken(message)
        Announce.notification(message)
        Diagnostics.log("Received a text from the phone")
    }

    /// The phone's app version, for diagnostics and to suggest updating it. Phones before 0.13.0 don't know INFO.
    private func askInfo(_ client: BridgeClient) {
        let epoch = connection
        Task {
            do {
                let info = try await client.info()
                guard epoch == connection else { return }
                phoneInfo = info
                Diagnostics.log("Phone app \(info.version), revision \(info.revision)")
            } catch {
                guard epoch == connection else { return }
                if BridgeError.isUnsupported(error) {
                    phoneOutdated = true
                    Diagnostics.log("Phone app is older than 0.13 (no INFO)")
                }
            }
        }
    }

    /// The connected phone's app is older than this Mac's (both have INFO): suggest updating it.
    var phoneBehind: String? {
        guard let info = phoneInfo, let mine = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String,
              Updates.isNewer(mine, than: info.version) else { return nil }
        return info.version
    }

    // MARK: Fast USB

    /// Fast USB: the phone's own sharing over the cable, through USB debugging. Without `asking`, a phone that hasn't
    /// allowed this Mac isn't asked (no surprise prompt on plugging in); "Use Fast USB" asks. Falls back to File
    /// transfer on the next poll when it can't connect.
    func connectFastUSB(asking: Bool = false) {
        guard !busy else { return }
        let device = USBDebugging.attachedPhone()
        fastSkipped = device   // one quiet try per plug-in
        usbNeedsAllow = false
        overUSB = false
        run {
            self.status = asking ? "If your phone asks “Allow USB debugging?”, tap Allow." : "Connecting over Fast USB…"
            Diagnostics.log("Fast USB: looking for OpenBridge over the cable")
            let saved = self.saved
            let prompt: (() -> Void)? = asking ? { Task { @MainActor in self.status = "Tap Allow on your phone." } } : nil
            let found: FastUSB.Found?
            do {
                found = try await FastUSB.find(allowPrompt: prompt, startSharing: true,
                                               known: { fingerprint in saved.contains { $0.fingerprint == fingerprint } },
                                               log: { Diagnostics.log($0) })
            } catch ADBError.notAllowed {
                Diagnostics.log("Fast USB: the phone hasn't allowed USB debugging from this Mac")
                self.usbNeedsAllow = true
                self.status = ""
                return
            } catch {
                Diagnostics.log("Fast USB unavailable: \(error.localizedDescription)")
                USBDebugging.shared.closeIfUnused()
                self.status = ""
                if asking { throw error }
                return
            }
            guard let found else {
                Diagnostics.log("Fast USB: OpenBridge didn't answer on the phone")
                USBDebugging.shared.closeIfUnused()
                self.status = asking ? "OpenBridge didn't answer on the phone. Open it there and tap the sun, then try again." : ""
                return
            }
            if let phone = saved.first(where: { $0.fingerprint == found.fingerprint }) {
                try await self.adoptFastUSB(found, as: phone)
            } else {
                self.forgetUSBCandidate()
                self.usbCandidate = found
                self.usbPairable = found.model ?? "Your phone"
                self.status = ""
                Diagnostics.log("Fast USB: OpenBridge answers, but this Mac hasn't paired with the phone")
            }
        }
    }

    private func adoptFastUSB(_ found: FastUSB.Found, as phone: SavedPhone) async throws {
        let client = try BridgeClient(tunnel: found.tunnel.localPort, fingerprint: phone.fingerprint, secret: phone.secret)
        let entries: [Entry]
        do { entries = try await client.list() } catch BridgeError.forgotten {
            // The phone forgot this Mac: pair again, over the cable.
            forget(phone)
            forgetUSBCandidate()
            usbCandidate = found; usbPairable = phone.name
            status = ""
            return
        } catch {
            found.tunnel.stop()
            throw error
        }
        if tunnel !== found.tunnel { tunnel?.stop() }
        tunnel = found.tunnel
        adopt(client, as: phone, entries: entries, fastUSB: true)
    }

    /// Pairs with a phone that answers over the cable: the same code comparison as over Wi‑Fi.
    func pairOverUSB() {
        guard let found = usbCandidate, !busy else { return }
        run {
            self.status = "Connecting to the phone…"
            Diagnostics.log("Fast USB: pairing")
            let paired = try await BridgeClient.pair(tunnel: found.tunnel.localPort) { code in
                self.pairingCode = code
                self.status = "Check that your phone shows the same code, then tap Pair on the phone."
            }
            self.pairingCode = nil
            let entries = try await paired.list()
            let info = try? await paired.info()
            // No Wi‑Fi address yet: it's learned when the phone is seen on Wi‑Fi.
            let phone = SavedPhone(name: info?.name ?? found.model ?? "Phone", host: "", port: 41987,
                                   fingerprint: paired.fingerprint, secret: paired.secret)
            self.usbCandidate = nil; self.usbPairable = nil
            if self.tunnel !== found.tunnel { self.tunnel?.stop() }
            self.tunnel = found.tunnel
            self.adopt(paired, as: phone, entries: entries, fastUSB: true)
        }
    }

    func cancelUSBPairing() {
        forgetUSBCandidate()
        fastSkipped = USBDebugging.attachedPhone()
        USBDebugging.shared.closeIfUnused()
    }

    private func forgetUSBCandidate() {
        if let candidate = usbCandidate, candidate.tunnel !== tunnel { candidate.tunnel.stop() }
        usbCandidate = nil; usbPairable = nil
    }

    /// Connected over Wi‑Fi and the same phone is plugged in with USB debugging: move to Fast USB, in the same folder.
    /// Quiet: no prompt, no starting sharing on the phone, nothing shown if it doesn't work.
    private func upgradeToFastUSB(_ device: String) {
        // Phones before 0.13.0 have no Fast USB; wait for INFO, and for any transfer to finish.
        guard let phone = current, !busy, transfer == nil || transfer?.done == true,
              let revision = phoneInfo?.revision, revision >= 18 else { return }
        fastSkipped = device
        let names = trail
        busy = true
        Task {
            defer { busy = false }
            do {
                guard let found = try await FastUSB.find(allowPrompt: nil, startSharing: false, known: { $0 == phone.fingerprint },
                                                         log: { Diagnostics.log($0) }) else { return }
                guard found.fingerprint == phone.fingerprint, connected, current?.id == phone.id else { found.tunnel.stop(); return }
                let client: BridgeClient
                let entries: [Entry]
                do {
                    client = try BridgeClient(tunnel: found.tunnel.localPort, fingerprint: phone.fingerprint, secret: phone.secret)
                    entries = try await client.list()
                } catch {
                    found.tunnel.stop()   // staying on Wi‑Fi: the tunnel's listener and thread would stay open
                    throw error
                }
                tunnel?.stop(); tunnel = found.tunnel
                adopt(client, as: phone, entries: entries, fastUSB: true)
                if let (trail, inside) = try? await resolve(names, client) { display(inside, at: trail) }
                show("Switched to Fast USB")
            } catch {
                Diagnostics.log("Fast USB: staying on Wi‑Fi (\(error.localizedDescription))")
            }
            if !fastUSB { USBDebugging.shared.closeIfUnused() }
        }
    }

    func showReceived() {
        if let received, FileManager.default.fileExists(atPath: received.path) { NSWorkspace.shared.activateFileViewerSelecting([received]) }
        else {
            try? FileManager.default.createDirectory(at: Self.receivedFolder, withIntermediateDirectories: true)
            NSWorkspace.shared.open(Self.receivedFolder)
        }
    }

    // MARK: Importing photos

    /// Downloads the camera's photos and videos that weren't imported from this phone before.
    func importNewPhotos(choosingFolder: Bool = false) {
        guard let client, !busy, let destination = Imports.folder(choosing: choosingFolder) else { return }
        let key = current?.id ?? deviceName
        run {
            self.status = "Looking for new photos…"
            var found = try await self.resolve(["DCIM", "Camera"], client)
            if found == nil { found = try await self.resolve(["DCIM"], client) }
            guard let (trail, entries) = found else { self.show("This phone has no DCIM folder to import from."); return }
            let media = entries.filter { !$0.directory && FileKind($0).previewable }
            let done = Imports.load(key)
            // Already imported, or already in the folder (an import that was cut off): skip.
            let fresh = media.filter { !done.contains(Imports.line($0)) && !Imports.present($0, in: destination) }
            self.status = ""
            guard !fresh.isEmpty else {
                Imports.save(key, done.union(media.map(Imports.line)))
                self.show("No new photos or videos since the last import.")
                return
            }
            self.transfer = Transfer(name: "Preparing…", upload: false)
            let at = trail.dropFirst().map(\.name)
            try await self.perform(fresh.map { .download($0, to: Self.free(destination.appendingPathComponent(Self.macName($0.name))), at: at) }, client: client)
            Imports.save(key, done.union(media.map(Imports.line)))
            self.show(fresh.count == 1 ? "Imported 1 photo or video" : "Imported \(fresh.count) photos and videos")
        }
    }

    /// USB cable in File transfer (MTP) mode: no app or pairing needed on the phone.
    func connectUSB() {
        guard !busy else { return }   // never relabel a connection that is still in progress
        overUSB = true
        let attached = MTPClient.attachedPhone()
        run {
            self.status = "Opening File transfer…"
            do {
                let usb = try await MTPClient.connectUSB()
                let storages = try await usb.list(id: "")
                Diagnostics.log("Connected over USB File transfer")
                self.fastUSB = false
                self.client = usb; self.deviceName = usb.name
                self.path = [("", usb.name)]; self.entries = storages
                self.connected = true; self.status = ""
                self.didConnect(to: usb.name)
            } catch {
                self.skipped = attached   // don't retry in a loop; replugging or Try again does
                Diagnostics.log("USB File transfer failed: \(error.localizedDescription)")
                throw error
            }
        }
    }

    func retryUSB() { skipped = nil; error = nil; connectUSB() }

    func pair(name: String? = nil) {
        guard !busy else { return }
        overUSB = false
        let address = host.trimmingCharacters(in: .whitespacesAndNewlines)
        run {
            self.status = "Connecting to the phone…"
            Diagnostics.log("Wi‑Fi: pairing with \(address)")
            let paired: BridgeClient
            do {
                paired = try await BridgeClient.pair(host: address, port: self.port) { code in
                    self.pairingCode = code
                    self.status = "Check that your phone shows the same code, then tap Pair on the phone."
                }
            } catch {
                if self.pairingCode == nil && self.discovery.phones.contains(where: { $0.host == address }) { self.noteUnreachable(address) }
                throw error
            }
            self.pairingCode = nil
            let entries = try await paired.list()
            let phone = SavedPhone(name: name ?? "Phone at \(paired.host)", host: paired.host, port: paired.port,
                                   fingerprint: paired.fingerprint, secret: paired.secret)
            self.adopt(paired, as: phone, entries: entries)
        }
    }

    func disconnect() {
        guard !busy else { return }
        if overUSB { skipped = MTPClient.attachedPhone() }
        else {
            autoConnect = false
            // Don't come straight back over the cable either.
            if fastUSB { fastSkipped = USBDebugging.attachedPhone(); skipped = MTPClient.attachedPhone() }
        }
        Diagnostics.log("Disconnected")
        drop("")
    }

    /// A new connection: old IDs are stale. If this phone had a transfer cut off, continue it.
    private func didConnect(to phone: String) {
        connection += 1
        Announce.spoken("Connected to \(phone) over \(transportName)")
        guard let pending = interrupted, pending.phone == phone, !pending.remaining.isEmpty else { return }
        interrupted = nil
        showBrowser = true
        show("Continuing “\(pending.remaining[0].name)”")
        // Starts once this connection's task has finished.
        DispatchQueue.main.async { self.start(pending.remaining, connection: pending.connection) }
    }

    private func drop(_ reason: String) {
        if connected { Announce.spoken(reason.isEmpty ? "Disconnected" : reason) }
        if !reason.isEmpty { Diagnostics.log("Connection ended: \(reason)") }
        if fastUSB { USBDebugging.shared.closeIfUnused() }
        tunnel?.stop(); tunnel = nil; fastUSB = false
        phoneInfo = nil; phoneOutdated = false
        clipboardTimer?.invalidate(); clipboardTimer = nil
        if let cut = transfer, !cut.done, !cut.remaining.isEmpty { interrupted = cut }
        if let old = client { Task { await old.disconnect() } }
        client = nil; connected = false; showBrowser = false; entries = []; path = [("", "Phone")]
        selection = []; transfer = nil; error = nil; status = reason
        thumbnails = [:]; thumbnailsPending = []; thumbnailsFailed = []
        thumbnailQueue = []; thumbnailsRunning = 0; thumbnailEpoch += 1
        sizes = [:]; sizeQueue = []; sizing = false; sizesPending = []; current = nil; query = ""
        receiver?.cancel(); receiver = nil
        searchReturn = nil; canReturnToResults = false
        try? FileManager.default.removeItem(at: previews)
    }

    func cancel() { task?.cancel() }

    // MARK: Browsing

    /// Folders first, then in the chosen order.
    var sorted: [Entry] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        return entries.filter { entry in
            (needle.isEmpty || entry.name.localizedCaseInsensitiveContains(needle)) && (!mediaOnly || FileKind(entry).previewable)
        }.sorted(by: inOrder)
    }

    /// Search hits in the chosen order, with the photos-and-videos filter, never the same item twice.
    var sortedResults: [Found] {
        var seen = Set<Entry.ID>()
        return (results?.found ?? []).filter { seen.insert($0.id).inserted && (!mediaOnly || FileKind($0.entry).previewable) }
            .sorted { inOrder($0.entry, $1.entry) }
    }

    /// Folders first, then by the chosen key and direction.
    private func inOrder(_ a: Entry, _ b: Entry) -> Bool {
        if a.directory != b.directory { return a.directory }
        let order: ComparisonResult
        switch sortKey {
        case .name: order = a.name.localizedStandardCompare(b.name)
        case .size:
            let (sa, sb) = (sizeOf(a), sizeOf(b))
            order = sa == sb ? a.name.localizedStandardCompare(b.name) : (sa < sb ? .orderedAscending : .orderedDescending)
        case .kind:
            let (ka, kb) = (FileKind(a), FileKind(b))
            order = ka == kb ? a.name.localizedStandardCompare(b.name) : (ka < kb ? .orderedAscending : .orderedDescending)
        }
        return ascending ? order == .orderedAscending : order == .orderedDescending
    }

    /// A file's size, or a folder's measured size (0 until measured).
    func sizeOf(_ entry: Entry) -> Int64 { entry.directory ? sizes[entry.id]?.bytes ?? 0 : entry.size }

    private func display(_ entries: [Entry], at path: [(id: String, name: String)]) {
        self.path = path; self.entries = entries; selection = []; anchor = nil; cursor = nil; query = ""; results = nil
        for entry in sizeQueue { sizesPending.remove(entry.id) }
        sizeQueue = []
        // Previews still waiting belong to the folder just left.
        for entry in thumbnailQueue { thumbnailsPending.remove(entry.id) }
        thumbnailQueue = []
    }

    func refresh() {
        guard let client else { return }
        run { self.entries = try await client.list(id: self.path.last!.id) }
    }

    func open(_ entry: Entry) {
        if entry.directory { enter(entry) } else { download([entry]) }
    }

    func enter(_ entry: Entry) {
        guard entry.directory, let client else { return }
        run { self.display(try await client.list(id: entry.id), at: self.path + [(entry.id, entry.name)]) }
    }

    func up() {
        guard path.count > 1 else { return }
        go(to: path.count - 2)
    }

    /// Opens a folder from the path bar.
    func go(to level: Int) {
        guard level < path.count - 1, let client else { return }
        let trail = Array(path.prefix(level + 1))
        run { self.display(try await client.list(id: trail.last!.id), at: trail) }
    }

    /// Opens a well-known folder such as DCIM/Camera. Tries each spelling in turn; phones differ.
    func jump(_ label: String, to candidates: [[String]]) {
        guard let client else { return }
        run {
            for names in candidates {
                if let (trail, entries) = try await self.resolve(names, client) {
                    self.display(entries, at: trail); self.showBrowser = true
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

    // MARK: Selection

    var selected: [Entry] { sorted.filter { selection.contains($0.id) } }

    /// Click selects one item, ⌘-click adds or removes, ⇧-click selects a range, like Finder.
    func click(_ entry: Entry) {
        let flags = NSEvent.modifierFlags
        if flags.contains(.command) {
            if selection.contains(entry.id) { selection.remove(entry.id) } else { selection.insert(entry.id) }
            anchor = entry.id
        } else if flags.contains(.shift), let anchor, let from = sorted.firstIndex(where: { $0.id == anchor }),
                  let to = sorted.firstIndex(where: { $0.id == entry.id }) {
            selection = Set(sorted[min(from, to)...max(from, to)].map(\.id))
        } else {
            selection = [entry.id]; anchor = entry.id
        }
        cursor = entry.id
    }

    func selectAll() { selection = Set(entries.map(\.id)) }

    /// What the arrow keys walk through: search hits when they're shown, else the folder in its order.
    private var keyboardOrder: [Entry.ID] { results != nil ? sortedResults.map(\.id) : sorted.map(\.id) }

    /// Arrow keys: moves the selection by `step` items (a row in the grid); with Shift, extends it from the anchor.
    func moveCursor(_ step: Int, extending: Bool = false) {
        let order = keyboardOrder
        guard !order.isEmpty else { return }
        let from = cursor.flatMap { order.firstIndex(of: $0) } ?? (step > 0 ? -1 : order.count)
        let to = min(max(from + step, 0), order.count - 1)
        cursor = order[to]
        if extending, let anchor, let start = order.firstIndex(of: anchor) {
            selection = Set(order[min(start, to)...max(start, to)])
        } else {
            selection = [order[to]]; anchor = order[to]
        }
    }

    /// ⌘↓ or ⌘O: opens the item the keyboard is on (a folder opens, a file downloads; a search hit likewise).
    func openCursor() {
        if results != nil, let id = cursor ?? selection.first, let hit = sortedResults.first(where: { $0.id == id }) { openFound(hit); return }
        if let entry = selected.first(where: { $0.id == cursor }) ?? selected.first { open(entry) }
    }

    /// Return, as in Finder: rename the one selected item.
    func renameSelected() {
        guard results == nil, selected.count == 1 else { return }
        renaming = selected[0]
    }

    /// Esc: clears the search first, then the selection.
    func escape() {
        if !query.isEmpty { query = "" } else { selection = []; cursor = nil }
    }

    // MARK: Previews

    /// Counts a folder's size on the phone, one at a time, when nothing else is running.
    func loadSize(_ entry: Entry) {
        guard entry.directory, client != nil, sizes[entry.id] == nil, !sizesPending.contains(entry.id) else { return }
        sizesPending.insert(entry.id)
        sizeQueue.append(entry)
        pumpSizes()
    }

    private func pumpSizes() {
        guard !sizing, !busy, let client, let entry = sizeQueue.popLast() else { return }
        sizing = true
        let epoch = thumbnailEpoch
        Task {
            let size = try? await client.measure(entry)
            guard epoch == thumbnailEpoch else { return }
            sizing = false
            if let size { sizes[entry.id] = size }
            pumpSizes()
        }
    }

    // MARK: Searching everywhere

    /// Searches every folder below the open one for names containing the query.
    func searchEverywhere() {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard let client, !text.isEmpty, let folder = path.last?.id else { return }
        searchReturn = nil; canReturnToResults = false
        run {
            self.status = "Searching…"
            let found = try await client.search(in: folder, for: text)
            self.status = ""
            self.results = found; self.resultsFor = text
        }
    }

    /// Opens a search hit: a folder opens, a file downloads.
    func openFound(_ hit: Found) {
        if hit.entry.directory { reveal(hit, inside: true) } else { download([hit.entry]) }
    }

    /// Where search results came from, so "Back to results" can return after opening one.
    private var searchReturn: (query: String, results: SearchResult, path: [(id: String, name: String)], entries: [Entry])?
    @Published private(set) var canReturnToResults = false

    func returnToResults() {
        guard let saved = searchReturn else { return }
        searchReturn = nil; canReturnToResults = false
        path = saved.path; entries = saved.entries; selection = []
        query = saved.query; results = saved.results; resultsFor = saved.query
    }

    /// Goes to the folder holding a hit (or into it) and selects it.
    func reveal(_ hit: Found, inside: Bool = false) {
        guard let client else { return }
        if let results { searchReturn = (resultsFor, results, path, entries) }
        let names = trail + hit.location.split(separator: "/").map(String.init) + (inside ? [hit.entry.name] : [])
        run {
            guard let (trail, entries) = try await self.resolve(names, client) else { self.show("That folder is no longer on the phone."); return }
            self.query = ""
            self.display(entries, at: trail)
            self.canReturnToResults = self.searchReturn != nil
            if !inside, let match = entries.first(where: { $0.name == hit.entry.name }) { self.selection = [match.id]; self.anchor = match.id }
        }
    }

    // MARK: Editing

    /// Asks before deleting: there's no bin on the phone.
    func confirmDelete(_ items: [Entry]) { if !items.isEmpty && !busy { pendingDelete = items } }

    func deleteConfirmed() {
        let items = pendingDelete
        pendingDelete = []
        guard let client, !items.isEmpty, let folder = path.last?.id else { return }
        run {
            for item in items { try await client.delete(item) }
            self.entries = try await client.list(id: folder)
            self.selection = []; self.sizes = [:]; self.sizesPending = []
            self.show(items.count == 1 ? "Deleted “\(items[0].name)”" : "Deleted \(items.count) items")
        }
    }

    func rename(_ entry: Entry, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let client, !trimmed.isEmpty, trimmed != entry.name, let folder = path.last?.id else { return }
        run {
            let renamed = try await client.rename(entry, to: trimmed)
            self.entries = try await client.list(id: folder)
            self.selection = [renamed.id]; self.anchor = renamed.id
        }
    }

    func loadThumbnail(_ entry: Entry) {
        guard FileKind(entry).previewable, client != nil, thumbnails[entry.id] == nil,
              !thumbnailsPending.contains(entry.id), !thumbnailsFailed.contains(entry.id) else { return }
        thumbnailsPending.insert(entry.id)
        thumbnailQueue.append(entry)
        pumpThumbnails()
    }

    /// Starts waiting previews: one at a time over USB, two over Wi‑Fi, the most recently shown first. None start
    /// while a folder is loading or a file is moving, so the preview queue can never hold up what you asked for.
    private func pumpThumbnails() {
        let limit = overUSB ? 1 : 2
        while thumbnailsRunning < limit, !busy, let client, let entry = thumbnailQueue.popLast() {
            thumbnailsRunning += 1
            let epoch = thumbnailEpoch
            Task {
                let data = try? await client.thumbnail(entry, size: 256)
                guard epoch == thumbnailEpoch else { return }
                thumbnailsRunning -= 1
                thumbnailsPending.remove(entry.id)
                if let data, let image = NSImage(data: data) { thumbnails[entry.id] = image }
                else { thumbnailsFailed.insert(entry.id) }
                pumpThumbnails()
            }
        }
    }

    /// Quick Look: downloads a temporary copy, then shows it.
    func preview() {
        guard let entry = selected.first(where: { !$0.directory }), let client else { return }
        guard entry.size <= 2 << 30 else { show("That file is too large to preview. Download it instead."); return }
        let folder = previews.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let target = folder.appendingPathComponent(entry.name)
        run {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            self.transfer = Transfer(name: entry.name, upload: false)
            try await client.download(entry, to: target) { done, total in self.update(done, total) }
            self.transfer = nil
            self.previewURL = target
        }
    }

    // MARK: Clipboard

    var clipboardAvailable: Bool { client is BridgeClient }

    func sendClipboard() {
        guard let bridge = client as? BridgeClient else { return }
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else { show("The Mac's clipboard has no text."); return }
        run {
            try await bridge.setClipboard(text)
            self.lastShared = text
            self.texts.add(text, fromPhone: false)
            self.show("Sent to the phone's clipboard")
        }
    }

    func fetchClipboard() {
        guard let bridge = client as? BridgeClient else { return }
        run {
            let text = try await bridge.clipboard()
            guard !text.isEmpty else { self.show("The phone's clipboard is empty."); return }
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            self.fromPhone = NSPasteboard.general.changeCount
            self.pasteboardSeen = self.fromPhone
            self.lastShared = text
            self.texts.add(text, fromPhone: true)
            self.show("Copied the phone's clipboard to your Mac")
        }
    }

    /// Puts a text on the phone's clipboard, from the Texts panel. `sent` runs once it's there.
    func sendText(_ text: String, sent: @escaping () -> Void) {
        guard let bridge = client as? BridgeClient, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard text.utf8.count <= Wire.maxClipboard else { textProblem = "That text is too long for the phone's clipboard (256 KB at most)."; return }
        textProblem = nil
        Task {
            do {
                try await bridge.setClipboard(text)
                lastShared = text
                texts.add(text, fromPhone: false)
                sent()
                show("Sent to the phone")
                Diagnostics.log("Sent a text to the phone")
            } catch {
                textProblem = error.localizedDescription
            }
        }
    }

    /// Copies a text from the Texts history. It's already been between the phone and this Mac, so automatic sending
    /// leaves it alone.
    func copyText(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        pasteboardSeen = pasteboard.changeCount
        lastShared = text
    }

    /// While connected over Wi‑Fi or Fast USB with automatic sending on, looks at the clipboard every second.
    private func watchClipboard() {
        clipboardTimer?.invalidate(); clipboardTimer = nil
        guard autoSendTexts, connected, client is BridgeClient else { return }
        pasteboardSeen = NSPasteboard.general.changeCount
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in self.clipboardChanged() }
        }
    }

    private func clipboardChanged() {
        let pasteboard = NSPasteboard.general
        let count = pasteboard.changeCount
        guard count != pasteboardSeen else { return }
        pasteboardSeen = count
        guard count != fromPhone, autoSendTexts, let bridge = client as? BridgeClient else { return }
        // Password managers mark what they copy as concealed or transient: never send that.
        let types = pasteboard.types ?? []
        guard !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")),
              !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")),
              let text = pasteboard.string(forType: .string), !text.isEmpty, text.utf8.count <= Wire.maxClipboard,
              text != lastShared else { return }
        lastShared = text
        Task {
            do {
                try await bridge.setClipboard(text)
                texts.add(text, fromPhone: false)
                Diagnostics.log("Sent copied text to the phone")
            } catch {
                Diagnostics.log("Couldn't send copied text: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Updates

    /// Asks GitHub for a newer release. Returns it, or nil when up to date or the check failed.
    @discardableResult
    func checkForUpdates(current: String? = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) async -> AvailableUpdate? {
        guard let current else { return nil }
        let found = await Updates.newer(than: current)
        Diagnostics.log(found.map { "Update check: \($0.version) is available" } ?? "Update check: no newer release found")
        if let found { update = found }
        return found
    }

    // MARK: Folders

    func newFolder() {
        guard let client, !busy, path.count > 1 || !overUSB else {
            show("Open a storage on the phone first."); return
        }
        let alert = NSAlert()
        alert.messageText = "New folder"
        alert.informativeText = "Create a folder on the phone in “\(path.last!.name)”."
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
        field.stringValue = "New folder"
        alert.accessoryView = field
        alert.addButton(withTitle: "Create"); alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let name = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let directory = path.last!.id
        run {
            _ = try await client.makeFolder(named: name, in: directory)
            self.entries = try await client.list(id: directory)
            self.show("Created “\(name)”")
        }
    }

    // MARK: Transfers

    /// Downloads files and folders. One file asks where to save it; several, or folders, ask for a folder.
    func download(_ items: [Entry]) {
        guard let client, !busy, !items.isEmpty else { return }
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        if items.count == 1, let file = items.first, !file.directory {
            let panel = NSSavePanel()
            panel.nameFieldStringValue = Self.macName(file.name)
            panel.directoryURL = downloads
            panel.message = "Choose where to save. OpenBridge never replaces existing files."
            guard panel.runModal() == .OK, let target = panel.url else { return }
            start([.download(file, to: target, at: trail)])
            return
        }
        let panel = NSOpenPanel()
        panel.canChooseFiles = false; panel.canChooseDirectories = true; panel.canCreateDirectories = true
        panel.directoryURL = downloads; panel.prompt = "Download Here"
        panel.message = "Choose a folder for \(items.count == 1 ? "“\(items[0].name)”" : "\(items.count) items"). Existing files are kept; copies get a new name."
        guard panel.runModal() == .OK, let folder = panel.url else { return }
        run {
            self.transfer = Transfer(name: "Preparing…", upload: false)
            try await self.perform(try await self.plan(items, into: folder, at: self.trail, client: client), client: client)
        }
    }

    /// Expands folders into individual downloads, creating the folders on the Mac.
    private func plan(_ items: [Entry], into folder: URL, at location: [String], client: any FileSource) async throws -> [Job] {
        var jobs: [Job] = []
        for item in items {
            try Task.checkCancellation()
            let target = Self.free(folder.appendingPathComponent(Self.macName(item.name)))
            if item.directory {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: false)
                jobs += try await plan(try await client.list(id: item.id), into: target, at: location + [item.name], client: client)
            } else {
                jobs.append(.download(item, to: target, at: location))
            }
        }
        return jobs
    }

    /// Uploads files and folders (from the Upload button or dropped from Finder) into the open folder.
    func upload(_ urls: [URL]) {
        guard let client, !busy, !urls.isEmpty else { return }
        if overUSB && path.count < 2 { show("Open a storage on the phone first, then upload."); return }
        let directory = path.last!.id
        run {
            self.transfer = Transfer(name: "Preparing…", upload: true)
            try await self.perform(try await self.plan(urls, into: directory, at: self.trail, client: client), client: client)
        }
    }

    func chooseUploads() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true; panel.canChooseDirectories = true; panel.allowsMultipleSelection = true
        panel.prompt = "Upload"
        guard panel.runModal() == .OK else { return }
        upload(panel.urls)
    }

    /// Expands Mac folders into uploads, creating the folders on the phone. Names already taken get a new name.
    private func plan(_ urls: [URL], into directory: String, at location: [String], client: any FileSource) async throws -> [Job] {
        var taken = Set(try await client.list(id: directory).map { $0.name.lowercased() })
        var jobs: [Job] = []
        for url in urls {
            try Task.checkCancellation()
            let values = try url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .isHiddenKey])
            if values.isSymbolicLink == true || values.isHidden == true { continue }
            if values.isDirectory == true {
                let name = Self.unique(url.lastPathComponent, taken: taken)
                taken.insert(name.lowercased())
                let folder = try await client.makeFolder(named: name, in: directory)
                let children = try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
                jobs += try await plan(children, into: folder.id, at: location + [name], client: client)
            } else {
                let name = Self.unique(url.lastPathComponent, taken: taken)
                taken.insert(name.lowercased())
                if name == url.lastPathComponent {
                    jobs.append(.upload(url, into: directory, at: location))
                } else {
                    // Upload under the new name by way of a temporary link with that name.
                    let staged = FileManager.default.temporaryDirectory.appendingPathComponent("OpenBridge Uploads/\(UUID().uuidString)", isDirectory: true)
                    try FileManager.default.createDirectory(at: staged, withIntermediateDirectories: true)
                    let renamed = staged.appendingPathComponent(name)
                    do { try FileManager.default.linkItem(at: url, to: renamed) }
                    catch { try FileManager.default.copyItem(at: url, to: renamed) }   // another volume
                    jobs.append(.upload(renamed, into: directory, at: location))
                }
            }
        }
        return jobs
    }

    /// Runs transfers one after another. On a failure the rest stay queued, and Resume continues them;
    /// an interrupted download picks up where it stopped.
    func start(_ jobs: [Job], connection planned: Int? = nil) {
        guard let client, !jobs.isEmpty else { return }
        run { try await self.perform(jobs, client: client, connection: planned) }
    }

    private func perform(_ jobs: [Job], client: any FileSource, connection planned: Int? = nil) async throws {
        guard !jobs.isEmpty else { transfer = nil; show("Nothing to transfer."); return }
        let upload: Bool
        if case .upload = jobs[0] { upload = true } else { upload = false }
        let planned = planned ?? connection
        transfer = Transfer(name: jobs[0].name, upload: upload, count: jobs.count, remaining: jobs, connection: planned, phone: deviceName)
        let folders = FolderCache()   // phone folders looked up again by name, for this run
        do {
            var lastSaved: URL?
            var touchedFolder = false
            for (index, job) in jobs.enumerated() {
                try Task.checkCancellation()
                self.transfer?.name = job.name; self.transfer?.index = index + 1; self.transfer?.fraction = 0
                self.transfer?.remaining = Array(jobs[index...])
                // IDs from an earlier connection are stale: find the file or folder again by its path.
                let current = planned == connection ? job : try await relocate(job, client: client, cache: folders)
                switch current {
                case .download(let entry, let target, _):
                    try await client.download(entry, to: target) { done, total in self.update(done, total) }
                    lastSaved = target
                case .upload(let url, let directory, _):
                    try await client.upload(url, directoryID: directory) { done, total in self.update(done, total) }
                    touchedFolder = touchedFolder || directory == self.path.last?.id
                }
            }
            self.transfer?.remaining = []
            self.transfer?.fraction = 1; self.transfer?.done = true; self.transfer?.saved = lastSaved
            try? FileManager.default.removeItem(at: FileManager.default.temporaryDirectory.appendingPathComponent("OpenBridge Uploads"))
            if touchedFolder || upload {
                // Publication succeeded even if a subsequent folder refresh fails.
                do { self.entries = try await client.list(id: self.path.last!.id) }
                catch { self.error = "Uploaded, but the folder didn't refresh: \(error.localizedDescription)" }
            }
        }
    }

    func resume() {
        guard let transfer, !transfer.remaining.isEmpty else { return }
        start(transfer.remaining, connection: transfer.connection)
    }

    /// Finds a job's file or folder on the current connection by the names in its path.
    private final class FolderCache { var listings: [String: [Entry]] = [:] }

    private func relocate(_ job: Job, client: any FileSource, cache: FolderCache) async throws -> Job {
        func folder(_ names: [String]) async throws -> (id: String, entries: [Entry]) {
            var id = "", entries = try await cached("", names: [])
            for (depth, name) in names.enumerated() {
                guard let next = entries.first(where: { $0.directory && $0.name == name })
                        ?? entries.first(where: { $0.directory && $0.name.caseInsensitiveCompare(name) == .orderedSame }) else {
                    throw BridgeError.message("“\(names.joined(separator: " › "))” is no longer on the phone.")
                }
                id = next.id
                entries = try await cached(id, names: Array(names.prefix(depth + 1)))
            }
            return (id, entries)
        }
        func cached(_ id: String, names: [String]) async throws -> [Entry] {
            let key = names.joined(separator: "/")
            if let hit = cache.listings[key] { return hit }
            let entries = try await client.list(id: id)
            cache.listings[key] = entries
            return entries
        }
        switch job {
        case .download(let entry, let target, let location):
            let (_, entries) = try await folder(location)
            guard let file = entries.first(where: { !$0.directory && $0.name == entry.name }) else {
                throw BridgeError.message("“\(entry.name)” is no longer on the phone.")
            }
            return .download(file, to: target, at: location)
        case .upload(let url, _, let location):
            return .upload(url, into: try await folder(location).id, at: location)
        }
    }

    /// Drag a file from the list to Finder: it downloads to a temporary place, and Finder copies it.
    func dragItem(_ entry: Entry) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = entry.name
        guard !entry.directory, let client else { return provider }
        provider.registerFileRepresentation(forTypeIdentifier: "public.data", fileOptions: [], visibility: .all) { completion in
            Task { @MainActor in
                guard !self.busy else { completion(nil, false, BridgeError.message("Wait for the current transfer to finish.")); return }
                self.busy = true
                defer { self.busy = false }
                let folder = self.previews.appendingPathComponent(UUID().uuidString, isDirectory: true)
                do {
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let target = folder.appendingPathComponent(entry.name)
                    self.transfer = Transfer(name: entry.name, upload: false)
                    try await client.download(entry, to: target) { done, total in self.update(done, total) }
                    self.transfer = nil
                    completion(target, false, nil)
                } catch {
                    self.transfer = nil
                    self.error = error.localizedDescription
                    completion(nil, false, error)
                }
            }
            return nil
        }
        return provider
    }

    private func update(_ done: Int64, _ total: Int64) {
        transfer?.fraction = total == 0 ? 0 : Double(done) / Double(total)
    }

    // MARK: Names

    /// "photo.jpg" becomes "photo 2.jpg", "photo 3.jpg"…, skipping names in `taken` (compared ignoring case).
    static func unique(_ name: String, taken: Set<String>) -> String {
        guard taken.contains(name.lowercased()) else { return name }
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        for number in 2... {
            let candidate = ext.isEmpty ? "\(base) \(number)" : "\(base) \(number).\(ext)"
            if !taken.contains(candidate.lowercased()) { return candidate }
        }
        return name
    }

    /// A phone's name for a file, short enough for the Mac (255 bytes, with room for " 2" and the partial download's
    /// suffix). Phones allow longer names in Chinese and other scripts over USB.
    static func macName(_ name: String) -> String { Wire.fitted(name, limit: 220) }

    /// A Mac destination that doesn't exist yet: "photo 2.jpg" if "photo.jpg" is there. Never replaces a file.
    static func free(_ url: URL) -> URL {
        let manager = FileManager.default
        if !manager.fileExists(atPath: url.path) { return url }
        let folder = url.deletingLastPathComponent()
        let taken = Set(((try? manager.contentsOfDirectory(atPath: folder.path)) ?? []).map { $0.lowercased() })
        return folder.appendingPathComponent(unique(url.lastPathComponent, taken: taken))
    }
}
