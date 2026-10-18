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
    /// Filters for the open folder.
    @Published var query = ""
    @Published var mediaOnly = false
    /// Folder sizes counted on the phone (Wi‑Fi only).
    @Published private(set) var sizes: [Entry.ID: FolderSize] = [:]
    /// Items waiting for the user to confirm deletion, and the item being renamed.
    @Published var pendingDelete: [Entry] = []
    @Published var renaming: Entry?
    @Published private(set) var update: AvailableUpdate?
    /// The saved phone connected now, when the connection came from one.
    private var current: SavedPhone?
    /// Off after Disconnect, so a saved phone doesn't reconnect straight away.
    private var autoConnect = true
    private var probing = false
    private var polls = 0
    private var sizeQueue: [Entry] = []
    private var sizing = false
    private var sizesPending: Set<Entry.ID> = []
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
                status = overUSB
                    ? "Couldn't reach the phone over USB. Keep it unlocked, plugged in and in File transfer mode."
                    : "Couldn't reach the phone. Check Wi‑Fi and that sharing is still on, or pair again."
            }
        }
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
            Task { self.update = await Updates.newer(than: version) }
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
        if attached == nil { skipped = nil }
        if connected {
            if overUSB && attached == nil { drop("The phone was unplugged.") }
            return
        }
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

    /// Tries each saved phone quietly; the first that answers opens.
    private func reconnectSaved() {
        probing = true
        let phones = saved.map(located)
        Task {
            defer { probing = false }
            for phone in phones {
                guard let client = try? phone.client() else { continue }
                do {
                    let entries = try await client.list(id: "", quick: true)
                    guard !connected, !busy, autoConnect else { return }
                    adopt(client, as: phone, entries: entries)
                    return
                } catch BridgeError.forgotten {
                    forget(phone)
                    error = "\(phone.name): \(BridgeError.forgotten.localizedDescription)"
                } catch { continue }
            }
        }
    }

    /// Opens a saved phone now (the user clicked it).
    func connect(_ phone: SavedPhone) {
        guard !busy else { return }
        overUSB = false
        run {
            self.status = "Connecting to \(phone.name)…"
            let target = self.located(phone)
            let client = try target.client()
            self.adopt(client, as: target, entries: try await client.list())
        }
    }

    func forget(_ phone: SavedPhone) {
        saved.removeAll { $0.id == phone.id }
        SavedPhones.save(saved)
    }

    /// Shows a connected Wi‑Fi phone and remembers it.
    private func adopt(_ client: BridgeClient, as phone: SavedPhone, entries: [Entry]) {
        overUSB = false
        self.client = client; deviceName = phone.name; host = phone.host
        path = [("", "Phone")]; self.entries = entries
        connected = true; status = ""; error = nil
        current = phone; autoConnect = true
        saved = [phone] + saved.filter { $0.id != phone.id }
        SavedPhones.save(saved)
        didConnect(to: phone.name)
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
                self.client = usb; self.deviceName = usb.name
                self.path = [("", usb.name)]; self.entries = storages
                self.connected = true; self.status = ""
                self.didConnect(to: usb.name)
            } catch {
                self.skipped = attached   // don't retry in a loop; replugging or Try again does
                throw error
            }
        }
    }

    func retryUSB() { skipped = nil; error = nil; connectUSB() }

    func pair(name: String? = nil) {
        guard !busy else { return }
        overUSB = false
        run {
            self.status = "Connecting to the phone…"
            let paired = try await BridgeClient.pair(host: self.host.trimmingCharacters(in: .whitespacesAndNewlines), port: self.port) { code in
                self.pairingCode = code
                self.status = "Check that your phone shows the same code, then tap Pair on the phone."
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
        if overUSB { skipped = MTPClient.attachedPhone() } else { autoConnect = false }
        drop("")
    }

    /// A new connection: old IDs are stale. If this phone had a transfer cut off, continue it.
    private func didConnect(to phone: String) {
        connection += 1
        guard let pending = interrupted, pending.phone == phone, !pending.remaining.isEmpty else { return }
        interrupted = nil
        showBrowser = true
        show("Continuing “\(pending.remaining[0].name)”")
        // Starts once this connection's task has finished.
        DispatchQueue.main.async { self.start(pending.remaining, connection: pending.connection) }
    }

    private func drop(_ reason: String) {
        if let cut = transfer, !cut.done, !cut.remaining.isEmpty { interrupted = cut }
        if let old = client { Task { await old.disconnect() } }
        client = nil; connected = false; showBrowser = false; entries = []; path = [("", "Phone")]
        selection = []; transfer = nil; error = nil; status = reason
        thumbnails = [:]; thumbnailsPending = []; thumbnailsFailed = []
        thumbnailQueue = []; thumbnailsRunning = 0; thumbnailEpoch += 1
        sizes = [:]; sizeQueue = []; sizing = false; sizesPending = []; current = nil; query = ""
        try? FileManager.default.removeItem(at: previews)
    }

    func cancel() { task?.cancel() }

    // MARK: Browsing

    /// Folders first, then in the chosen order.
    var sorted: [Entry] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        return entries.filter { entry in
            (needle.isEmpty || entry.name.localizedCaseInsensitiveContains(needle)) && (!mediaOnly || FileKind(entry).previewable)
        }.sorted { a, b in
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
    }

    /// A file's size, or a folder's measured size (0 until measured).
    func sizeOf(_ entry: Entry) -> Int64 { entry.directory ? sizes[entry.id]?.bytes ?? 0 : entry.size }

    private func display(_ entries: [Entry], at path: [(id: String, name: String)]) {
        self.path = path; self.entries = entries; selection = []; anchor = nil; query = ""
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
    }

    func selectAll() { selection = Set(entries.map(\.id)) }

    // MARK: Previews

    /// Counts a folder's size on the phone, one at a time, when nothing else is running. Wi‑Fi only: MTP can't.
    func loadSize(_ entry: Entry) {
        guard entry.directory, !overUSB, client != nil, sizes[entry.id] == nil, !sizesPending.contains(entry.id) else { return }
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
            self.show("Copied the phone's clipboard to your Mac")
        }
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
            panel.nameFieldStringValue = file.name
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
            let target = Self.free(folder.appendingPathComponent(item.name))
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

    /// A Mac destination that doesn't exist yet: "photo 2.jpg" if "photo.jpg" is there. Never replaces a file.
    static func free(_ url: URL) -> URL {
        let manager = FileManager.default
        if !manager.fileExists(atPath: url.path) { return url }
        let folder = url.deletingLastPathComponent()
        let taken = Set(((try? manager.contentsOfDirectory(atPath: folder.path)) ?? []).map { $0.lowercased() })
        return folder.appendingPathComponent(unique(url.lastPathComponent, taken: taken))
    }
}
