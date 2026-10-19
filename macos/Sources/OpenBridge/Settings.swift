import SwiftUI
import AppKit
import BridgeCore

/// OpenBridge › Settings… (⌘,): the look, texts, updates, diagnostics and about.
struct SettingsView: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject private var looks = LookStore.shared

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    ForEach(Look.allCases) { look in LookCard(look: look, chosen: looks.look == look) { looks.look = look } }
                }
                .padding(.vertical, 4)
            } header: {
                Text("Look")
            } footer: {
                Text("Aurora and Tide are previews: they may change before they're finished.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Texts") {
                Toggle("Send copied text to the phone automatically", isOn: $model.autoSendTexts)
                Text("While connected over Wi‑Fi or Fast USB. Passwords copied from password managers are never sent.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Updates") {
                HStack {
                    Button(checking ? "Checking…" : "Check for Updates") { check() }.disabled(checking)
                    Spacer()
                    Text(updateStatus).foregroundStyle(.secondary)
                }
            }
            Section {
                HStack {
                    Button(copied ? "Copied" : "Copy Diagnostics") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(Diagnostics.report(for: model), forType: .string)
                        copied = true
                    }
                    Spacer()
                }
                Text("Versions, the connection and recent events, to paste into a bug report. No pairing secrets, texts or file names; nothing is sent anywhere.")
                    .font(.caption).foregroundStyle(.secondary)
            } header: { Text("Diagnostics") }
            Section("About") {
                LabeledContent("Version", value: Diagnostics.appVersion)
                Text("OpenBridge moves files between your Android phone and this Mac. No accounts, no cloud. Free and open source (MIT).")
                    .font(.caption).foregroundStyle(.secondary)
                Link("OpenBridge on GitHub", destination: URL(string: "https://github.com/PredatorWoah/OpenBridge")!)
            }
        }
        .formStyle(.grouped)
        .frame(width: 520)
        .fixedSize(horizontal: false, vertical: true)
    }

    @State private var checking = false
    @State private var checked = false
    @State private var copied = false

    private var updateStatus: String {
        if let update = model.update { return "OpenBridge \(update.version) is available" }
        return checked ? "You have the latest version" : ""
    }

    @MainActor private func check() {
        checking = true
        Task {
            await model.checkForUpdates()
            checking = false; checked = true
        }
    }
}

/// One look to choose: a live miniature, its name, and "Preview" on drafts.
private struct LookCard: View {
    let look: Look
    let chosen: Bool
    let choose: () -> Void

    var body: some View {
        Button(action: choose) {
            VStack(spacing: 6) {
                LookPreview(look: look)
                    .frame(width: 140, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(chosen ? Color.accentColor : Color.secondary.opacity(0.3), lineWidth: chosen ? 3 : 1))
                    .overlay(alignment: .topTrailing) {
                        if look.draft {
                            Text("Preview").font(.system(size: 10, weight: .bold))
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(.black.opacity(0.55), in: Capsule())
                                .foregroundStyle(.white)
                                .padding(6)
                        }
                    }
                HStack(spacing: 4) {
                    if chosen { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor) }
                    Text(look.label).font(.system(size: 12.5, weight: chosen ? .semibold : .regular))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(look.draft ? "\(look.label), preview" : look.label)
        .accessibilityAddTraits(chosen ? AccessibilityTraits.isSelected : AccessibilityTraits())
    }
}
