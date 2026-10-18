import SwiftUI
import AppKit
import QuickLook
import UniformTypeIdentifiers
import BridgeCore

struct BrowserScreen: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    @State private var dropping = false
    @State private var newName = ""
    /// While typing in search, keys like Space and Return go to the field, not to shortcuts.
    @FocusState private var searching: Bool

    var body: some View {
        HStack(spacing: 0) {
            Sidebar(model: model, mirror: mirror)
            VStack(spacing: 0) {
                Toolbar(model: model, searching: $searching)
                ZStack {
                    if model.grid { grid } else { list }
                    if model.sorted.isEmpty && !model.entries.isEmpty {
                        Text("Nothing here matches.").foregroundStyle(Palette.muted)
                    }
                    if model.entries.isEmpty && !model.busy {
                        Text(model.path.count > 1 ? "This folder is empty. Drop files here to upload them." : "Nothing to show.")
                            .foregroundStyle(Palette.muted)
                    }
                    if dropping {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(Palette.sunset, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
                            .background(Palette.violet.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(Label("Drop to upload to “\(model.path.last?.name ?? "Phone")”", systemImage: "arrow.up.circle.fill")
                                .font(.system(size: 15, weight: .semibold)))
                            .padding(10)
                            .allowsHitTesting(false)
                    }
                }
                .onDrop(of: [UTType.fileURL], isTargeted: $dropping, perform: drop)
                if let error = model.error {
                    Text(error).font(.system(size: 12.5)).foregroundStyle(Palette.amber).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 8)
                }
                if model.transfer != nil {
                    TransferBar(model: model).padding(.horizontal, 16).padding(.bottom, 16)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.5, dampingFraction: 0.75), value: model.transfer != nil)
        }
        .overlay(alignment: .bottom) { Toast(text: model.toast) }
        .quickLookPreview($model.previewURL)
        .background { Shortcuts(model: model, searching: searching) }
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

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 2) {
                ForEach(model.sorted) { entry in
                    FileRow(entry: entry, selected: model.selection.contains(entry.id), thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id])
                        .modifier(Item(model: model, entry: entry))
                }
            }
            .padding(10)
        }
        // Each folder fades in as a whole, quickly; rows never wait on their own timers.
        .id(model.path.last?.id ?? "")
        .transition(.opacity.combined(with: .offset(y: 6)))
        .animation(.easeOut(duration: 0.15), value: model.path.last?.id)
    }

    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 128), spacing: 10)], spacing: 10) {
                ForEach(model.sorted) { entry in
                    FileTile(entry: entry, selected: model.selection.contains(entry.id), thumbnail: model.thumbnails[entry.id], folderSize: model.sizes[entry.id])
                        .modifier(Item(model: model, entry: entry))
                }
            }
            .padding(12)
        }
        .id("grid-" + (model.path.last?.id ?? ""))
        .transition(.opacity)
        .animation(.easeOut(duration: 0.15), value: model.path.last?.id)
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

/// Click, double-click, context menu, drag to Finder and preview loading, shared by rows and tiles.
private struct Item: ViewModifier {
    @ObservedObject var model: BrowserModel
    let entry: Entry
    func body(content: Content) -> some View {
        content
            .onTapGesture(count: 2) { model.open(entry) }
            .onTapGesture { model.click(entry) }
            .onAppear { if entry.directory { model.loadSize(entry) } else { model.loadThumbnail(entry) } }
            .onDrag { model.dragItem(entry) }
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

private struct Sidebar: View {
    @ObservedObject var model: BrowserModel
    var mirror: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("DEVICES").font(.system(size: 11, weight: .semibold)).tracking(1.4).foregroundStyle(Palette.muted)
                .padding(.horizontal, 8).padding(.bottom, 4)
            HStack(spacing: 10) {
                ArchMark(size: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.deviceName).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                    Text(model.overUSB ? "USB · File transfer" : "Wi‑Fi").font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.75))
                }
            }
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [Palette.violet.opacity(0.45), Palette.pink.opacity(0.3)], startPoint: .leading, endPoint: .trailing),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            Button { model.showBrowser = false } label: { Label("Devices", systemImage: "chevron.left") }
                .buttonStyle(.plain).foregroundStyle(Palette.muted).font(.system(size: 12.5)).padding(10)
            if model.overUSB {
                Button(action: mirror) { Label("Mirror screen", systemImage: "rectangle.on.rectangle") }
                    .buttonStyle(.plain).font(.system(size: 13, weight: .semibold)).padding(10)
                    .help("Show and control the phone's screen on your Mac (needs USB debugging)")
            }
            Spacer()
            Button("Disconnect", action: model.disconnect).buttonStyle(GhostButton()).disabled(model.busy).padding(8)
        }
        .padding(.top, 44).padding(.horizontal, 10).padding(.bottom, 10)
        .frame(width: 220)
        .background(Color.white.opacity(0.04))
        .overlay(alignment: .trailing) { Rectangle().fill(.white.opacity(0.08)).frame(width: 1) }
    }
}

private struct Toolbar: View {
    @ObservedObject var model: BrowserModel
    var searching: FocusState<Bool>.Binding
    var body: some View {
        HStack(spacing: 8) {
            Button(action: model.up) { Image(systemName: "chevron.left") }
                .buttonStyle(GhostButton()).disabled(model.path.count < 2 || model.busy).help("Up one folder")
            PathBar(model: model)
            Spacer(minLength: 8)
            HStack(spacing: 5) {
                Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted)
                TextField("Search", text: $model.query).textFieldStyle(.plain).focused(searching)
                if !model.query.isEmpty {
                    Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
                }
            }
            .font(.system(size: 13)).padding(.horizontal, 9).padding(.vertical, 5).frame(width: 170).glass(8)
            Button { model.mediaOnly.toggle() } label: { Image(systemName: model.mediaOnly ? "photo.fill.on.rectangle.fill" : "photo.on.rectangle") }
                .buttonStyle(.plain).foregroundStyle(model.mediaOnly ? Palette.amber : .white).frame(width: 28)
                .help(model.mediaOnly ? "Showing only photos and videos" : "Show only photos and videos")
            Picker("", selection: $model.grid) {
                Image(systemName: "list.bullet").tag(false)
                Image(systemName: "square.grid.2x2").tag(true)
            }
            .pickerStyle(.segmented).frame(width: 76).help("List or grid")
            Menu {
                Picker("Sort by", selection: $model.sortKey) {
                    ForEach(SortKey.allCases) { Text($0.rawValue).tag($0) }
                }
                Divider()
                Picker("Order", selection: $model.ascending) {
                    Text("Ascending").tag(true)
                    Text("Descending").tag(false)
                }
            } label: { Image(systemName: "arrow.up.arrow.down") }
                .menuStyle(.borderlessButton).frame(width: 34).help("Sort by \(model.sortKey.rawValue.lowercased())")
            Menu {
                Button("New Folder…", action: model.newFolder)
                Button("Upload…", action: model.chooseUploads)
                Divider()
                Button("Import New Photos") { model.importNewPhotos() }
                Button("Import New Photos To…") { model.importNewPhotos(choosingFolder: true) }
                Button("Show Files Sent from the Phone", action: model.showReceived).disabled(model.overUSB)
                Divider()
                Button("Rename…") { model.renaming = model.selected.first }.disabled(model.selection.count != 1)
                Button("Delete…") { model.confirmDelete(model.selected) }.disabled(model.selection.isEmpty)
                Divider()
                Button("Send Mac clipboard to phone", action: model.sendClipboard).disabled(!model.clipboardAvailable)
                Button("Copy phone clipboard to Mac", action: model.fetchClipboard).disabled(!model.clipboardAvailable)
                if !model.clipboardAvailable {
                    Text("Clipboard sharing works over Wi‑Fi, or while mirroring the screen.")
                }
                Divider()
                Button("Refresh", action: model.refresh)
            } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).frame(width: 34).disabled(model.busy)
            Button(model.selection.count > 1 ? "Download \(model.selection.count)" : "Download") { model.download(model.selected) }
                .buttonStyle(SunsetButton()).disabled(model.busy || model.selection.isEmpty)
        }
        .padding(.horizontal, 16).padding(.top, 40).padding(.bottom, 10)
        .overlay(alignment: .bottom) { Rectangle().fill(.white.opacity(0.08)).frame(height: 1) }
    }
}

/// Clickable folder trail: "Phone › Internal storage › DCIM".
private struct PathBar: View {
    @ObservedObject var model: BrowserModel
    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(model.path.enumerated()), id: \.offset) { index, crumb in
                if index > 0 { Image(systemName: "chevron.right").font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.muted) }
                Button(crumb.name) { model.go(to: index) }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: index == model.path.count - 1 ? .bold : .medium))
                    .foregroundStyle(index == model.path.count - 1 ? .white : Palette.muted)
                    .lineLimit(1)
            }
        }
        .truncationMode(.head)
    }
}

/// Keyboard shortcuts: Space for Quick Look, ⌘A to select all, ⌘↓ or Return to open, ⌘⇧N for a new folder,
/// ⌘⌫ to delete. While typing in search, Space, Return and ⌘A belong to the field.
private struct Shortcuts: View {
    @ObservedObject var model: BrowserModel
    var searching: Bool
    var body: some View {
        ZStack {
            Button("") { model.preview() }.keyboardShortcut(.space, modifiers: []).disabled(searching)
            Button("") { model.selectAll() }.keyboardShortcut("a", modifiers: .command).disabled(searching)
            Button("") { if let first = model.selected.first { model.open(first) } }.keyboardShortcut(.downArrow, modifiers: .command)
            Button("") { if let first = model.selected.first { model.open(first) } }.keyboardShortcut(.return, modifiers: []).disabled(searching)
            Button("") { model.confirmDelete(model.selected) }.keyboardShortcut(.delete, modifiers: .command).disabled(searching)
            Button("") { model.up() }.keyboardShortcut(.upArrow, modifiers: .command)
            Button("") { model.newFolder() }.keyboardShortcut("n", modifiers: [.command, .shift])
        }
        .opacity(0).allowsHitTesting(false).accessibilityHidden(true)
    }
}

private func symbol(for kind: FileKind) -> (String, Color) {
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

private let selectionFill = LinearGradient(colors: [Palette.violet.opacity(0.55), Palette.pink.opacity(0.35)], startPoint: .leading, endPoint: .trailing)

/// "2.3 GB", "≥ 41 GB" when the phone stopped counting early, or "—" before it's measured.
private func folderText(_ size: FolderSize?) -> String {
    guard let size else { return "—" }
    return (size.complete ? "" : "≥ ") + ByteCountFormatter.string(fromByteCount: size.bytes, countStyle: .file)
}

struct FileRow: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil

    var body: some View {
        let (name, color) = symbol(for: FileKind(entry))
        HStack(spacing: 12) {
            Group {
                if let thumbnail {
                    Image(nsImage: thumbnail).resizable().scaledToFill().frame(width: 26, height: 26)
                        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                } else {
                    Image(systemName: name).foregroundStyle(color)
                }
            }
            .frame(width: 26, height: 26)
            Text(entry.name).lineLimit(1).truncationMode(.middle)
            Spacer()
            Text(entry.directory ? folderText(folderSize) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .foregroundStyle(Palette.muted).monospacedDigit()
                .help(folderSize.map { "\($0.files) files" } ?? "")
        }
        .font(.system(size: 13.5))
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background { if selected { RoundedRectangle(cornerRadius: 8, style: .continuous).fill(selectionFill) } }
        .contentShape(Rectangle())
    }
}

struct FileTile: View {
    var entry: Entry
    var selected: Bool
    var thumbnail: NSImage?
    var folderSize: FolderSize? = nil

    var body: some View {
        let (name, color) = symbol(for: FileKind(entry))
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
            Text(entry.name).font(.system(size: 12)).lineLimit(2).multilineTextAlignment(.center).truncationMode(.middle)
            Text(entry.directory ? (folderSize == nil ? " " : folderText(folderSize)) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                .font(.system(size: 10.5)).foregroundStyle(Palette.muted)
        }
        .padding(8)
        .frame(width: 128)
        .background { if selected { RoundedRectangle(cornerRadius: 14, style: .continuous).fill(selectionFill) } }
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
                    } else if !model.busy {
                        // Stopped by an error: continue where it left off.
                        Button("Resume", action: model.resume).buttonStyle(SunsetButton()).disabled(transfer.remaining.isEmpty)
                        Button { model.transfer = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain).foregroundStyle(Palette.muted)
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
