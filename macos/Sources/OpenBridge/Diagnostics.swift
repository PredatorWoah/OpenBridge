import Foundation
import AppKit
import UserNotifications
import BridgeCore

/// The last 500 events (connection attempts, transports, discovery, errors, update checks), kept in memory only, for
/// Settings › Copy diagnostics. Never secrets, pairing codes, texts or file names: folder names at most.
final class Diagnostics: @unchecked Sendable {
    static let shared = Diagnostics()
    private let lock = NSLock()
    private var lines: [String] = []
    private let stamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
    }()

    static func log(_ event: String) { shared.add(event) }

    private func add(_ event: String) {
        lock.lock(); defer { lock.unlock() }
        lines.append("\(stamp.string(from: Date())) \(event)")
        if lines.count > 500 { lines.removeFirst(lines.count - 500) }
    }

    var recent: [String] { lock.lock(); defer { lock.unlock() }; return lines }

    static var appVersion: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "development") (\(info?["CFBundleVersion"] as? String ?? "-"))"
    }

    static var architecture: String {
        #if arch(arm64)
        return "Apple silicon (arm64)"
        #else
        return "Intel (x86_64)"
        #endif
    }

    /// What Copy diagnostics puts on the clipboard: versions, the connection, and the recent events.
    @MainActor static func report(for model: BrowserModel) -> String {
        var text = [
            "OpenBridge \(appVersion) for macOS",
            "macOS: \(ProcessInfo.processInfo.operatingSystemVersionString), \(architecture)",
            "Connection: \(model.transportName)",
        ]
        if model.connected {
            if let info = model.phoneInfo {
                text.append("Phone: \(info.name), OpenBridge \(info.version) (revision \(info.revision))")
            } else if model.overUSB {
                text.append("Phone: \(model.deviceName) (File transfer)")
            } else {
                text.append("Phone: \(model.deviceName), OpenBridge before 0.13 (no INFO)")
            }
        }
        text.append("Saved phones: \(model.saved.count)")
        text.append("USB debugging seen: \(model.usbDebugging ? "yes" : "no")")
        text.append("Look: \(Palette.look.label)")
        text.append("Send copied text automatically: \(model.autoSendTexts ? "on" : "off")")
        text.append("")
        text.append("Recent events:")
        text += shared.recent
        return text.joined(separator: "\n")
    }
}

/// Says things to people who aren't looking: VoiceOver announcements, and a notification while the app is in the
/// background.
enum Announce {
    /// Read by VoiceOver now, whatever has focus.
    @MainActor static func spoken(_ message: String) {
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: message, .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    /// A notification, only while OpenBridge isn't the active app (the window shows its own toast otherwise).
    @MainActor static func notification(_ message: String) {
        // Notifications need a real app bundle; a development build run from the terminal has none.
        guard !NSApp.isActive, Bundle.main.bundleIdentifier != nil, Bundle.main.bundleURL.pathExtension == "app" else { return }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = "OpenBridge"
            content.body = message
            center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil), withCompletionHandler: nil)
        }
    }
}
