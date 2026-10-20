import SwiftUI
import AppKit
import QuickLook
import UniformTypeIdentifiers
import BridgeCore

/// The open folder (or search results), its empty states and the drop target: the same in every look's shell; the
/// look decides only how rows, tiles and their motion are drawn.
struct FolderContent: View {
    @ObservedObject var model: BrowserModel
    let look: Look
    @Binding var dropping: Bool
    /// Tide says "Drop to upload" on its water surface instead of outlining the files.
    var outlineDrop = true

    @ViewBuilder var body: some View {
        // ImageRenderer can't draw AppKit's drop target: README screenshots leave it out.
        if Screenshots.active { files } else { files.onDrop(of: [UTType.fileURL], isTargeted: $dropping, perform: drop) }
    }

    private var files: some View {
        ZStack {
            if let results = model.results { ResultsList(model: model, results: results) }
            else if model.grid { FolderGrid(model: model, look: look) } else { FolderList(model: model, look: look) }
            EmptyNotes(model: model)
            if dropping && outlineDrop { DropOutline(model: model) }
        }
    }

    /// Files dropped from Finder upload into the open folder.
    private func drop(_ providers: [NSItemProvider]) -> Bool {
        let group = DispatchGroup()
        var urls: [URL] = []
        let lock = NSLock()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url { lock.lock(); urls.append(url); lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: .main) { model.upload(urls) }
        return !providers.isEmpty
    }
}

/// What shows when a folder is empty or the filter hides everything.
private struct EmptyNotes: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        if model.results == nil && model.sorted.isEmpty && !model.entries.isEmpty {
            VStack(spacing: 10) {
                Text("Nothing in this folder matches.").foregroundStyle(Palette.muted)
                if !model.query.isEmpty {
                    Button("Search all folders for “\(model.query)”", action: model.searchEverywhere)
                        .buttonStyle(SunsetButton()).disabled(model.busy)
                }
            }
        }
        if model.entries.isEmpty && !model.busy {
            Text(model.path.count > 1 ? "This folder is empty. Drop files here to upload them." : "Nothing to show.")
                .foregroundStyle(Palette.muted)
        }
    }
}

private struct DropOutline: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .strokeBorder(Palette.sunset, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
            .background(Palette.violet.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(Label("Drop to upload to “\(model.path.last?.name ?? "Phone")”", systemImage: "arrow.up.circle.fill")
                .font(.system(size: 15, weight: .semibold)))
            .padding(10)
            .allowsHitTesting(false)
    }
}

/// How a look moves between folders: Dusk fades quickly, Aurora drifts up slowly, Tide springs. With Reduce Motion
/// every look only fades.
enum FolderMotion {
    static func transition(_ look: Look) -> AnyTransition {
        switch look {
        case .dusk: return .opacity.combined(with: .offset(y: 6))
        case .aurora: return .opacity.combined(with: .offset(y: 8))
        case .tide: return .opacity.combined(with: .offset(y: 10))
        }
    }

    static func animation(_ look: Look, reduceMotion: Bool) -> Animation {
        if reduceMotion { return .easeOut(duration: 0.15) }
        switch look {
        case .dusk: return .easeOut(duration: 0.15)
        case .aurora: return .easeOut(duration: 0.45)
        case .tide: return .spring(response: 0.45, dampingFraction: 0.72)
        }
    }
}

/// Tile size and spacing of a look's grid, and how many tiles fit in a row (as the adaptive grid lays them out).
struct GridMetrics {
    let tile: CGFloat
    let spacing: CGFloat
    let padding: CGFloat

    static func of(_ look: Look) -> GridMetrics {
        switch look {
        case .dusk: return GridMetrics(tile: 128, spacing: 10, padding: 12)
        case .aurora: return GridMetrics(tile: 160, spacing: 16, padding: 18)
        case .tide: return GridMetrics(tile: 140, spacing: 14, padding: 14)
        }
    }

    func columns(_ width: CGFloat) -> Int {
        let usable: CGFloat = width - 2 * padding + spacing
        let each: CGFloat = tile + spacing
        return max(1, Int(usable / each))
    }
}

/// The open folder as a list, in the look's rows.
private struct FolderList: View {
    @ObservedObject var model: BrowserModel
    let look: Look
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let entries = model.sorted
        let deepest = Self.deepest(entries, model.sizes)
        ScrollViewReader { proxy in
            FileScroll {
                LazyVStack(spacing: spacing) {
                    ForEach(entries) { entry in
                        row(entry, deepest: deepest).modifier(Item(model: model, entry: entry))
                    }
                }
                .padding(padding)
            }
            .onChange(of: model.cursor) { id in if let id { proxy.scrollTo(id) } }
        }
        // Each folder arrives as a whole; rows never wait on their own timers.
        .id(model.path.last?.id ?? "")
        .transition(FolderMotion.transition(look))
        .animation(FolderMotion.animation(look, reduceMotion: reduceMotion), value: model.path.last?.id)
    }

    private var spacing: CGFloat { look == .tide ? 8 : look == .aurora ? 4 : 2 }
    private var padding: CGFloat { look == .tide ? 12 : look == .aurora ? 14 : 10 }

    @ViewBuilder private func row(_ entry: Entry, deepest: Int64) -> some View {
        let selected = model.selection.contains(entry.id)
        let focused = model.cursor == entry.id
        let toggle: () -> Void = { model.toggle(entry) }
        switch look {
        case .dusk:
            FileRow(entry: entry, selected: selected, thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id], focused: focused,
                    toggle: toggle)
        case .aurora:
            AuroraRow(entry: entry, selected: selected, thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id], focused: focused,
                      toggle: toggle)
        case .tide:
            CapsuleRow(entry: entry, selected: selected, thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id],
                       deepest: deepest, focused: focused, toggle: toggle)
        }
    }

    /// The biggest measured folder here, for Tide's depth gauges.
    private static func deepest(_ entries: [Entry], _ sizes: [Entry.ID: FolderSize]) -> Int64 {
        var most: Int64 = 0
        for entry in entries where entry.directory {
            if let bytes = sizes[entry.id]?.bytes, bytes > most { most = bytes }
        }
        return most
    }
}

/// The open folder as a grid, in the look's tiles.
private struct FolderGrid: View {
    @ObservedObject var model: BrowserModel
    let look: Look
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let metrics = GridMetrics.of(look)
        ScrollViewReader { proxy in
            FileScroll {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.tile), spacing: metrics.spacing)], spacing: metrics.spacing) {
                    ForEach(model.sorted) { entry in
                        tile(entry).modifier(Item(model: model, entry: entry))
                    }
                }
                .padding(metrics.padding)
            }
            .onChange(of: model.cursor) { id in if let id { proxy.scrollTo(id) } }
            // How many tiles fit in a row, so ↑ and ↓ move by a row.
            .background(GeometryReader { geometry in
                Color.clear
                    .onAppear { model.gridColumns = metrics.columns(geometry.size.width) }
                    .onChange(of: geometry.size.width) { model.gridColumns = metrics.columns($0) }
            })
        }
        .id("grid-" + (model.path.last?.id ?? ""))
        .transition(look == .dusk ? AnyTransition.opacity : FolderMotion.transition(look))
        .animation(FolderMotion.animation(look, reduceMotion: reduceMotion), value: model.path.last?.id)
    }

    @ViewBuilder private func tile(_ entry: Entry) -> some View {
        let selected = model.selection.contains(entry.id)
        let focused = model.cursor == entry.id
        let toggle: () -> Void = { model.toggle(entry) }
        switch look {
        case .dusk:
            FileTile(entry: entry, selected: selected, thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id], focused: focused,
                     toggle: toggle)
        case .aurora:
            AuroraTile(entry: entry, selected: selected, thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id], focused: focused,
                       toggle: toggle)
        case .tide:
            TideCard(entry: entry, selected: selected, thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id], focused: focused,
                     toggle: toggle)
        }
    }
}

/// Quick Look, keyboard shortcuts, and the delete and rename dialogs: every shell's browser has them.
struct BrowserChrome: ViewModifier {
    @ObservedObject var model: BrowserModel
    var searching: Bool
    @State private var newName = ""

    func body(content: Content) -> some View {
        content
            .quickLookPreview($model.previewURL)
            .background { if !Screenshots.active { Shortcuts(model: model, searching: searching) } }
            .confirmationDialog(deleteTitle, isPresented: Binding(get: { !model.pendingDelete.isEmpty }, set: { if !$0 { model.pendingDelete = [] } })) {
                Button("Delete", role: .destructive, action: model.deleteConfirmed)
            } message: { Text("Folders are deleted with everything in them. The phone has no bin, so this can't be undone.") }
            .alert("Rename", isPresented: Binding(get: { model.renaming != nil }, set: { if !$0 { model.renaming = nil } })) {
                TextField("Name", text: $newName)
                Button("Rename") { if let entry = model.renaming { model.rename(entry, to: newName) }; model.renaming = nil }
                Button("Cancel", role: .cancel) { model.renaming = nil }
            }
            .onChange(of: model.renaming) { entry in if let entry { newName = entry.name } }
    }

    private var deleteTitle: String {
        model.pendingDelete.count == 1 ? "Delete “\(model.pendingDelete[0].name)” from the phone?" : "Delete \(model.pendingDelete.count) items from the phone?"
    }
}

/// The last error, under the files.
struct BrowserError: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        if let error = model.error {
            Text(error).font(.system(size: 12.5)).foregroundStyle(Palette.amber).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 8)
        }
    }
}

/// Search hits from every folder below the open one, with where each one is: in the chosen order, as a list or a
/// grid, like the folder itself.
private struct ResultsList: View {
    @ObservedObject var model: BrowserModel
    let results: SearchResult

    var body: some View {
        let hits = model.sortedResults
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(hits.isEmpty ? "Nothing found for “\(model.resultsFor)”"
                         : "\(hits.count)\(results.complete ? "" : "+") found for “\(model.resultsFor)”")
                        .font(.system(size: 13, weight: .semibold))
                    if !results.complete {
                        Text("Stopped after the first ones. Search a smaller folder or a longer name for the rest.")
                            .font(.system(size: 12)).foregroundStyle(Palette.muted)
                    }
                }
                Spacer()
                Button("Done") { model.query = "" }.buttonStyle(GhostButton()).help("Close the results and stay in this folder")
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            ScrollView {
                if model.grid {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 128), spacing: 10)], spacing: 10) {
                        ForEach(hits) { hit in
                            VStack(spacing: 2) {
                                FileTile(entry: hit.entry, selected: false, thumbnail: model.thumbnails[hit.entry.id])
                                Text(place(hit)).font(.system(size: 10.5)).foregroundStyle(Palette.muted).lineLimit(1).truncationMode(.head)
                                    .frame(width: 120)
                            }
                            .modifier(Hit(model: model, hit: hit))
                        }
                    }
                    .padding(12)
                } else {
                    LazyVStack(spacing: 2) {
                        ForEach(hits) { hit in
                            row(hit)
                                .background { if model.cursor == hit.id { RoundedRectangle(cornerRadius: 8, style: .continuous).fill(selectionFill) } }
                                .modifier(Hit(model: model, hit: hit))
                        }
                    }
                    .padding(10)
                }
            }
        }
    }

    private func place(_ hit: Found) -> String {
        hit.location.isEmpty ? (model.path.last?.name ?? "") : hit.location.replacingOccurrences(of: "/", with: " › ")
    }

    private func row(_ hit: Found) -> some View {
        let (symbolName, color) = fileSymbol(FileKind(hit.entry))
        return HStack(spacing: 12) {
            Group {
                if let thumbnail = model.thumbnails[hit.entry.id] {
                    Image(nsImage: thumbnail).resizable().scaledToFill().frame(width: 26, height: 26)
                        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                } else {
                    Image(systemName: symbolName).foregroundStyle(color)
                }
            }
            .frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text(hit.entry.name).lineLimit(1).truncationMode(.middle)
                Text(place(hit)).font(.system(size: 11.5)).foregroundStyle(Palette.muted).lineLimit(1).truncationMode(.head)
            }
            Spacer()
            if !hit.entry.directory {
                Text(ByteCountFormatter.string(fromByteCount: hit.entry.size, countStyle: .file)).foregroundStyle(Palette.muted).monospacedDigit()
            }
        }
        .font(.system(size: 13.5))
        .padding(.horizontal, 12).padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// Double-click and context menu for a search hit, and its preview.
struct Hit: ViewModifier {
    @ObservedObject var model: BrowserModel
    let hit: Found
    @ViewBuilder func body(content: Content) -> some View {
        if Screenshots.active { content } else { interactive(content) }
    }

    private func interactive(_ content: Content) -> some View {
        content
            .onTapGesture(count: 2) { model.openFound(hit) }
            .onAppear { if !hit.entry.directory { model.loadThumbnail(hit.entry) } }
            .accessibilityElement(children: .combine)
            .accessibilityAction { model.openFound(hit) }
            .accessibilityAction(named: "Show in Folder") { model.reveal(hit) }
            .contextMenu {
                Button(hit.entry.directory ? "Open" : "Download…") { model.openFound(hit) }
                Button("Show in Folder") { model.reveal(hit) }
            }
    }
}

/// Click, double-click, context menu, drag to Finder and preview loading, shared by rows and tiles.
struct Item: ViewModifier {
    @ObservedObject var model: BrowserModel
    let entry: Entry
    @ViewBuilder func body(content: Content) -> some View {
        // ImageRenderer can't draw drag sources or context menus: README screenshots leave them out.
        if Screenshots.active { content } else { interactive(content) }
    }

    private func interactive(_ content: Content) -> some View {
        content
            .onTapGesture(count: 2) { model.open(entry) }
            .onTapGesture { model.click(entry) }
            .onAppear { if entry.directory { model.loadSize(entry) } else { model.loadThumbnail(entry) } }
            .onDrag { model.dragItem(entry) }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(model.selection.contains(entry.id) ? AccessibilityTraits.isSelected : AccessibilityTraits())
            .accessibilityHint(entry.directory ? "Opens the folder" : "Downloads the file")
            .accessibilityAction { model.open(entry) }
            .accessibilityAction(named: Text(verbatim: model.selection.contains(entry.id) ? "Deselect \(entry.name)" : "Select \(entry.name)")) {
                model.toggle(entry)
            }
            .accessibilityAction(named: "Rename") { model.renaming = entry }
            .accessibilityAction(named: "Delete") { model.confirmDelete([entry]) }
            .contextMenu {
                Button(entry.directory ? "Open" : "Download…") { model.open(entry) }
                if !entry.directory {
                    Button("Quick Look") { model.selection = [entry.id]; model.preview() }
                }
                if model.selection.count > 1 && model.selection.contains(entry.id) {
                    Button("Download \(model.selection.count) Items…") { model.download(model.selected) }
                }
                Divider()
                Button("Rename…") { model.renaming = entry }
                Button(model.selection.count > 1 && model.selection.contains(entry.id) ? "Delete \(model.selection.count) Items…" : "Delete…") {
                    model.confirmDelete(model.selection.contains(entry.id) ? model.selected : [entry])
                }
            }
    }
}

// MARK: - Toolbar parts, arranged by each look's shell

/// Up one folder.
struct UpButton: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        Button(action: model.up) { Image(systemName: "chevron.left") }
            .buttonStyle(GhostButton()).disabled(model.path.count < 2 || model.busy).help("Up one folder (⌘↑)")
            .accessibilityLabel("Up one folder")
    }
}

/// Back to the search results, after opening a folder from them.
struct ResultsButton: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        if model.canReturnToResults {
            Button(action: model.returnToResults) { Label("Results", systemImage: "magnifyingglass") }
                .buttonStyle(GhostButton()).fixedSize().help("Back to the search results")
        }
    }
}

struct DownloadButton: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        Button(model.selection.count > 1 ? "Download \(model.selection.count)" : "Download") { model.download(model.selected) }
            .buttonStyle(SunsetButton()).disabled(model.busy || model.selection.isEmpty)
            .fixedSize()
    }
}

struct SearchBox: View {
    @ObservedObject var model: BrowserModel
    var searching: FocusState<Bool>.Binding
    var width: CGFloat = 180
    var radius: CGFloat = 8

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted).accessibilityHidden(true)
            if Screenshots.active {
                Text("Search").foregroundStyle(Palette.muted).frame(maxWidth: .infinity, alignment: .leading)
            } else {
                TextField("Search", text: $model.query).textFieldStyle(.plain).focused(searching)
                    .onSubmit { model.searchEverywhere() }
            }
            if !model.query.isEmpty {
                Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                    .help("Clear the search (Esc)").accessibilityLabel("Clear search")
            }
        }
        .font(.system(size: 13)).padding(.horizontal, 9).padding(.vertical, 5).frame(width: width).glass(radius)
        .help("Type to filter this folder. Press Return to search every folder inside it.")
    }
}

struct MediaToggle: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        Button { model.mediaOnly.toggle() } label: { Image(systemName: model.mediaOnly ? "photo.fill.on.rectangle.fill" : "photo.on.rectangle") }
            .buttonStyle(.plain).foregroundStyle(model.mediaOnly ? Palette.amber : .white).frame(width: 28)
            .help(model.mediaOnly ? "Showing only photos and videos" : "Show only photos and videos")
            .accessibilityLabel("Only photos and videos")
            .accessibilityValue(model.mediaOnly ? "On" : "Off")
    }
}

struct ViewToggle: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        if Screenshots.active {
            ClosedSegments(grid: model.grid)
        } else {
            Picker("View", selection: $model.grid) {
                Image(systemName: "list.bullet").accessibilityLabel("List").tag(false)
                Image(systemName: "square.grid.2x2").accessibilityLabel("Grid").tag(true)
            }
            .labelsHidden()
            .pickerStyle(.segmented).frame(width: 76).help("List or grid")
        }
    }
}

/// Sort key and order, as menu items (also inside the compact ⋯ menu).
struct SortChoices: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        Picker("Sort by", selection: $model.sortKey) {
            ForEach(SortKey.allCases) { Text($0.rawValue).tag($0) }
        }
        Divider()
        Picker("Order", selection: $model.ascending) {
            Text("Ascending").tag(true)
            Text("Descending").tag(false)
        }
    }
}

struct SortMenu: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        if Screenshots.active {
            ClosedMenu(symbol: "arrow.up.arrow.down")
        } else {
            Menu { SortChoices(model: model) } label: { Image(systemName: "arrow.up.arrow.down") }
                .menuStyle(.borderlessButton).frame(width: 34).help("Sort by \(model.sortKey.rawValue.lowercased())")
                .accessibilityLabel("Sort, by \(model.sortKey.rawValue.lowercased())")
        }
    }
}

/// Everything else: folders, uploads, imports, rename and delete, texts and the clipboard. Shells without a sidebar
/// add Devices and Disconnect (`devices`); compact ones fold the view, filter and sort controls in too (`folded`).
struct MoreMenu: View {
    @ObservedObject var model: BrowserModel
    var devices = false
    var folded = false

    var body: some View {
        if Screenshots.active { ClosedMenu(symbol: "ellipsis.circle") } else { menu }
    }

    private var menu: some View {
        let busy: Bool = model.busy
        return Menu {
            if folded {
                Toggle("Only Photos and Videos", isOn: $model.mediaOnly)
                Picker("View", selection: $model.grid) {
                    Text("List").tag(false)
                    Text("Grid").tag(true)
                }
                Menu("Sort") { SortChoices(model: model) }
                Divider()
            }
            // While something runs, only what can't wait stays: Devices and Disconnect.
            Group {
                Button("New Folder…", action: model.newFolder)
                Button("Upload…", action: model.chooseUploads)
                Divider()
                Button("Import New Photos") { model.importNewPhotos() }
                Button("Import New Photos To…") { model.importNewPhotos(choosingFolder: true) }
            }
            .disabled(busy)
            Button("Show Files Sent from the Phone", action: model.showReceived).disabled(model.overUSB)
            Divider()
            Button("Rename…") { model.renaming = model.selected.first }.disabled(busy || model.selection.count != 1)
            Button("Delete…") { model.confirmDelete(model.selected) }.disabled(busy || model.selection.isEmpty)
            Divider()
            Button("Texts…") { model.showTexts = true }.disabled(!model.clipboardAvailable)
            Button("Send Mac clipboard to phone", action: model.sendClipboard).disabled(busy || !model.clipboardAvailable)
            Button("Copy phone clipboard to Mac", action: model.fetchClipboard).disabled(busy || !model.clipboardAvailable)
            if !model.clipboardAvailable {
                Text("Texts and the clipboard work over Wi‑Fi and Fast USB, or while mirroring the screen.")
            }
            Divider()
            Button("Refresh", action: model.refresh).disabled(busy)
            if devices {
                Divider()
                Button("Devices") { model.showBrowser = false }
                Button("Disconnect", action: model.disconnect)
            }
        } label: { Image(systemName: "ellipsis.circle") }
            .menuStyle(.borderlessButton).frame(width: 34)
            .help("More actions").accessibilityLabel("More actions")
    }
}

/// Keyboard shortcuts, as in Finder: arrows move the selection (Shift extends it), ⌘↓ or ⌘O opens, ⌘↑ goes up,
/// Return renames, Space is Quick Look, ⌘A selects all, ⌘⌫ or Delete deletes (after asking), ⌘⇧N makes a folder,
/// Esc clears the search, then the selection. While typing in search, the field keeps its keys (but Esc clears it).
private struct Shortcuts: View {
    @ObservedObject var model: BrowserModel
    var searching: Bool
    var body: some View {
        let row = model.grid && model.results == nil ? model.gridColumns : 1
        ZStack {
            Button("") { model.moveCursor(-row) }.keyboardShortcut(.upArrow, modifiers: []).disabled(searching)
            Button("") { model.moveCursor(row) }.keyboardShortcut(.downArrow, modifiers: []).disabled(searching)
            Button("") { model.moveCursor(-row, extending: true) }.keyboardShortcut(.upArrow, modifiers: .shift).disabled(searching)
            Button("") { model.moveCursor(row, extending: true) }.keyboardShortcut(.downArrow, modifiers: .shift).disabled(searching)
            Button("") { model.moveCursor(-1) }.keyboardShortcut(.leftArrow, modifiers: []).disabled(searching || row == 1)
            Button("") { model.moveCursor(1) }.keyboardShortcut(.rightArrow, modifiers: []).disabled(searching || row == 1)
            Button("") { model.preview() }.keyboardShortcut(.space, modifiers: []).disabled(searching)
            Button("") { model.selectAll() }.keyboardShortcut("a", modifiers: .command).disabled(searching)
            Button("") { model.openCursor() }.keyboardShortcut(.downArrow, modifiers: .command).disabled(searching)
            Button("") { model.openCursor() }.keyboardShortcut("o", modifiers: .command)
            Button("") { model.renameSelected() }.keyboardShortcut(.return, modifiers: []).disabled(searching)
            Button("") { model.confirmDelete(model.selected) }.keyboardShortcut(.delete, modifiers: .command).disabled(searching)
            Button("") { model.confirmDelete(model.selected) }.keyboardShortcut(.deleteForward, modifiers: []).disabled(searching)
            Button("") { model.up() }.keyboardShortcut(.upArrow, modifiers: .command).disabled(searching)
            Button("") { model.newFolder() }.keyboardShortcut("n", modifiers: [.command, .shift])
            Button("") { model.escape() }.keyboardShortcut(.escape, modifiers: [])
        }
        .opacity(0).allowsHitTesting(false).accessibilityHidden(true)
    }
}

func fileSymbol(_ kind: FileKind) -> (String, Color) {
    switch kind {
    case .folder: return ("folder.fill", Palette.amber)
    case .image: return ("photo.fill", Palette.violet)
    case .video: return ("play.rectangle.fill", Palette.pink)
    case .audio: return ("music.note", Palette.sunGold)
    case .document: return ("doc.text.fill", Palette.muted)
    case .archive: return ("doc.zipper", Palette.muted)
    case .app: return ("shippingbox.fill", Palette.ok)
    case .other: return ("doc.fill", Palette.muted)
    }
}

var selectionFill: LinearGradient {
    LinearGradient(colors: [Palette.violet.opacity(0.55), Palette.pink.opacity(0.35)], startPoint: .leading, endPoint: .trailing)
}

/// "2.3 GB", "≥ 41 GB" when the phone stopped counting early, or "—" before it's measured.
func folderText(_ size: FolderSize?) -> String {
    guard let size else { return "—" }
    return (size.complete ? "" : "≥ ") + ByteCountFormatter.string(fromByteCount: size.bytes, countStyle: .file)
}

/// Where the keyboard is: a ring that shows even on a selected item.
func focusRing(_ radius: CGFloat, _ on: Bool) -> some View {
    RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Palette.amber.opacity(on ? 0.9 : 0), lineWidth: 1.5)
}

struct FileRow: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil
    var focused = false
    /// Its checkbox; none where selecting doesn't apply (search results).
    var toggle: (() -> Void)? = nil

    var body: some View {
        let (name, color) = fileSymbol(FileKind(entry))
        HStack(spacing: 12) {
            if let toggle { RowCheckbox(name: entry.name, selected: selected, toggle: toggle) }
            Group {
                if let thumbnail {
                    Image(nsImage: thumbnail).resizable().scaledToFill().frame(width: 26, height: 26)
                        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                } else {
                    Image(systemName: name).foregroundStyle(color)
                }
            }
            .frame(width: 26, height: 26)
            .accessibilityHidden(true)
            Text(entry.name).lineLimit(1).truncationMode(.middle)
            Spacer()
            Text(entry.directory ? folderText(folderSize) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .foregroundStyle(Palette.muted).monospacedDigit()
                .help(folderSize.map { $0.files > 0 ? "\($0.files) files" : "" } ?? "")
        }
        .font(.system(size: 13.5))
        .padding(.leading, toggle == nil ? 12 : 6).padding(.trailing, 12).padding(.vertical, 6)
        .background { if selected { RoundedRectangle(cornerRadius: 8, style: .continuous).fill(selectionFill) } }
        .overlay(focusRing(8, focused))
        .contentShape(Rectangle())
    }
}

struct FileTile: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil
    var focused = false
    /// Its checkbox; none where selecting doesn't apply (search results).
    var toggle: (() -> Void)? = nil

    var body: some View {
        let (name, color) = fileSymbol(FileKind(entry))
        VStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(.white.opacity(0.06))
                if let thumbnail {
                    Image(nsImage: thumbnail).resizable().scaledToFill()
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                } else {
                    Image(systemName: name).font(.system(size: 34)).foregroundStyle(color)
                }
            }
            .frame(width: 108, height: 96)
            .clipped()
            .overlay(alignment: .topLeading) {
                if let toggle { RowCheckbox(name: entry.name, selected: selected, onPicture: true, toggle: toggle).padding(2) }
            }
            Text(entry.name).font(.system(size: 12)).lineLimit(2).multilineTextAlignment(.center).truncationMode(.middle)
            Text(entry.directory ? (folderSize == nil ? " " : folderText(folderSize)) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .font(.system(size: 10.5)).foregroundStyle(Palette.muted)
        }
        .padding(8)
        .frame(width: 128)
        .background { if selected { RoundedRectangle(cornerRadius: 14, style: .continuous).fill(selectionFill) } }
        .overlay(focusRing(14, focused))
        .contentShape(Rectangle())
    }
}

struct Toast: View {
    var text: String?
    var body: some View {
        if let text {
            Text(text).font(.system(size: 13, weight: .semibold))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .glass(99)
                .padding(.bottom, 22)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
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
                    if transfer.count > 1 {
                        Text("\(transfer.index) of \(transfer.count)").foregroundStyle(Palette.muted).monospacedDigit()
                    }
                    Spacer()
                    if transfer.done {
                        if let saved = transfer.saved {
                            Text("Saved").foregroundStyle(Palette.ok).bold()
                            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([saved]) }.buttonStyle(.link)
                        } else {
                            Text(transfer.upload ? "Uploaded to the phone" : "Done").foregroundStyle(Palette.ok).bold()
                        }
                        Button { model.transfer = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                            .help("Close").accessibilityLabel("Close")
                    } else if !model.busy {
                        // Stopped by an error: continue where it left off.
                        Button("Resume", action: model.resume).buttonStyle(SunsetButton()).disabled(transfer.remaining.isEmpty)
                        Button { model.transfer = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                            .help("Forget what's left").accessibilityLabel("Forget what's left")
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
