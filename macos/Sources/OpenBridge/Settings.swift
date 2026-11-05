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
            }
            Section("Texts") {
                Toggle("Send copied text to the phone automatically", isOn: $model.autoSendTexts)
                Text("While connected over Wi‑Fi, the phone's hotspot or Fast USB. Passwords copied from password managers are never sent.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            PhoneNotificationSettings(feed: model.feed)
            Section {
                if model.saved.isEmpty {
                    Text("Pair a phone first.").foregroundStyle(.secondary)
                }
                ForEach(model.saved) { phone in BackupRow(model: model, phone: phone) }
            } header: {
                Text("Photo backup")
            } footer: {
                Text("New photos and videos from the camera are copied when the phone connects over Wi‑Fi, its hotspot or Fast USB, then every 10 minutes while it stays connected. Photos imported before are skipped, and nothing is ever replaced.")
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
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
        // The grouped form scrolls, and the window can be resized: nothing ends up below the bottom of the screen.
        .frame(minWidth: 520, idealWidth: 560, maxWidth: 760, minHeight: 420, idealHeight: 680, maxHeight: .infinity)
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

/// Phone notifications: shown on this Mac or not, and with sound.
private struct PhoneNotificationSettings: View {
    @ObservedObject var feed: PhoneFeed

    var body: some View {
        Section("Phone notifications") {
            Toggle("Show phone notifications on this Mac", isOn: $feed.showOnMac)
            Toggle("Play sound", isOn: $feed.playSound).disabled(!feed.showOnMac)
            Text("From phones with OpenBridge 1.2 or later, while connected over Wi‑Fi, the phone's hotspot or Fast USB, once Notifications on computers is on in OpenBridge on the phone.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        Section("Phone media") {
            Toggle("Control the phone's music with the media keys", isOn: $feed.mediaKeys)
            Text("While the phone plays or pauses music, the play, next and previous keys, AirPods and Control Center control it instead of this Mac. From phones with OpenBridge 1.3 or later, once Media controls on computers is on there.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// One saved phone's photo backup: on or off, and the folder.
private struct BackupRow: View {
    @ObservedObject var model: BrowserModel
    let phone: SavedPhone
    @State private var enabled = false
    @State private var folder: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: $enabled) {
                Text("Back up new photos from \(phone.name) automatically").lineLimit(1).truncationMode(.middle)
            }
            .onChange(of: enabled) { BackupSettings.setEnabled($0, for: phone.id) }
            HStack(spacing: 8) {
                Image(systemName: "folder").foregroundStyle(.secondary).accessibilityHidden(true)
                Text(shownPath).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                Spacer(minLength: 8)
                Button("Change…") { if let chosen = BackupSettings.choose(for: phone) { folder = chosen } }
                if model.currentID == phone.id {
                    Button("Back Up Now", action: model.backUpNow).disabled(model.backup != nil)
                }
            }
        }
        .onAppear {
            enabled = BackupSettings.enabled(phone.id)
            folder = BackupSettings.folder(for: phone)
        }
    }

    private var shownPath: String {
        let path: String = (folder ?? BackupSettings.folder(for: phone)).path
        return path.replacingOccurrences(of: NSHomeDirectory(), with: "~")
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
