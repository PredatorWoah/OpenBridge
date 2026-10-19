import SwiftUI
import AppKit
import BridgeCore

/// A text sent between the phone and this Mac.
struct TextItem: Codable, Identifiable, Equatable {
    var id = UUID()
    let text: String
    let fromPhone: Bool
    let date: Date
}

/// The last 30 texts, in ~/Library/Application Support/OpenBridge/texts.json: readable only by this user (mode 0600),
/// left out of Time Machine, removed with Clear. Never logged.
enum TextFile {
    static let limit = 30
    private static var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("OpenBridge", isDirectory: true).appendingPathComponent("texts.json")
    }

    static func load() -> [TextItem] {
        guard let data = try? Data(contentsOf: file), let items = try? JSONDecoder().decode([TextItem].self, from: data) else { return [] }
        return Array(items.prefix(limit))
    }

    static func save(_ items: [TextItem]) {
        let manager = FileManager.default
        guard !items.isEmpty else { try? manager.removeItem(at: file); return }
        do {
            let folder = file.deletingLastPathComponent()
            try manager.createDirectory(at: folder, withIntermediateDirectories: true)
            let temporary = folder.appendingPathComponent("texts.tmp")
            try? manager.removeItem(at: temporary)
            guard manager.createFile(atPath: temporary.path, contents: try JSONEncoder().encode(items), attributes: [.posixPermissions: 0o600]) else { return }
            if manager.fileExists(atPath: file.path) { _ = try manager.replaceItemAt(file, withItemAt: temporary) }
            else { try manager.moveItem(at: temporary, to: file) }
            var location = file
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? location.setResourceValues(values)
        } catch {}
    }
}

/// Texts history, newest first.
@MainActor
final class TextHistory: ObservableObject {
    @Published private(set) var items: [TextItem] = TextFile.load()

    func add(_ text: String, fromPhone: Bool) {
        items.insert(TextItem(text: text, fromPhone: fromPhone, date: Date()), at: 0)
        if items.count > TextFile.limit { items.removeLast(items.count - TextFile.limit) }
        TextFile.save(items)
    }

    func clear() {
        items = []
        TextFile.save(items)
    }
}

/// Texts between the phone and this Mac: write one and send it, send or get a clipboard, and what was sent before.
struct TextsPanel: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var history: TextHistory
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @FocusState private var writing: Bool

    private var available: Bool { model.clipboardAvailable }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "text.bubble.fill").foregroundStyle(Palette.amber).accessibilityHidden(true)
                Text("Texts").font(.system(size: 20, weight: .bold, design: .rounded))
                Spacer()
                Button("Clear", action: history.clear).buttonStyle(GhostButton()).disabled(history.items.isEmpty)
                    .help("Remove every text from this Mac's history")
                Button { dismiss() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted)
                    .keyboardShortcut(.cancelAction).help("Close").accessibilityLabel("Close")
            }
            if !available {
                Text(model.overUSB ? "Texts work over Wi‑Fi and Fast USB. Over File transfer, mirror the screen to share the clipboard."
                     : "Connect to your phone to send texts.")
                    .font(.system(size: 12.5)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            } else if model.phoneOutdated {
                Text("Update OpenBridge on the phone to receive texts it sends. Sending to its clipboard works already.")
                    .font(.system(size: 12.5)).foregroundStyle(Palette.amber).fixedSize(horizontal: false, vertical: true)
            }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $draft)
                    .font(.system(size: 13.5))
                    .scrollContentBackground(.hidden)
                    .focused($writing)
                    .frame(height: 72)
                    .accessibilityLabel("Text to send")
                if draft.isEmpty {
                    Text("Type or paste a text for the phone").foregroundStyle(Palette.muted).font(.system(size: 13.5))
                        .padding(.leading, 5).allowsHitTesting(false).accessibilityHidden(true)
                }
            }
            .padding(8)
            .glass(12)
            HStack(spacing: 8) {
                Button { model.sendText(draft) { draft = "" } } label: { Label("Send", systemImage: "paperplane.fill") }
                    .buttonStyle(SunsetButton())
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(!available || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .help("Put this text on the phone's clipboard (⌘↩)")
                Button("Send clipboard", action: model.sendClipboard).buttonStyle(GhostButton()).disabled(!available || model.busy)
                    .help("Send the text on this Mac's clipboard to the phone")
                Button("Get phone clipboard", action: model.fetchClipboard).buttonStyle(GhostButton()).disabled(!available || model.busy)
                    .help("Copy the phone's clipboard to this Mac (OpenBridge must be open on the phone)")
            }
            Toggle("Send copied text to the phone automatically", isOn: $model.autoSendTexts)
                .toggleStyle(.checkbox).font(.system(size: 12.5)).foregroundStyle(Palette.muted)
            if let problem = model.textProblem {
                Text(problem).font(.system(size: 12.5)).foregroundStyle(Palette.amber).textSelection(.enabled)
            }
            Divider().overlay(Color.white.opacity(0.1))
            if history.items.isEmpty {
                Text("Texts you send or receive appear here.").font(.system(size: 12.5)).foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, minHeight: 80)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(history.items) { item in TextRow(item: item) }
                    }
                }
                .frame(minHeight: 120, maxHeight: 280)
            }
        }
        .padding(20)
        .frame(width: 460)
        .background(Palette.background)
        .preferredColorScheme(.dark)
        .onAppear { writing = available }
    }
}

private struct TextRow: View {
    let item: TextItem
    @State private var copied = false

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Label(item.fromPhone ? "From phone" : "To phone", systemImage: item.fromPhone ? "arrow.down.left" : "arrow.up.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(item.fromPhone ? Palette.ok : Palette.amber)
                    Text(item.date, format: .dateTime.hour().minute()).font(.system(size: 11)).foregroundStyle(Palette.muted)
                }
                Text(item.text).font(.system(size: 13)).lineLimit(4).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(item.text, forType: .string)
                copied = true
            } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc") }
                .buttonStyle(.plain).foregroundStyle(copied ? Palette.ok : Palette.muted)
                .help("Copy").accessibilityLabel(copied ? "Copied" : "Copy")
        }
        .padding(10)
        .glass(10)
        .accessibilityElement(children: .combine)
    }
}
