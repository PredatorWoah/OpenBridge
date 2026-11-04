import SwiftUI
import AppKit
import UserNotifications
import BridgeCore

/// What the phones say is happening on them (EVENTS, from phone app 1.2.0): their notifications and battery. Kept
/// per phone while OpenBridge runs, with the cursor to continue from, so a reconnect picks up where it stopped
/// instead of showing everything again.
@MainActor
final class PhoneFeed: ObservableObject {
    /// The open phone's notifications, newest first.
    @Published private(set) var notifications: [PhoneNotification] = []
    @Published private(set) var battery: BatteryState?
    /// Events are coming from the open phone.
    @Published private(set) var live = false
    @Published private(set) var phoneName = ""
    private(set) var phoneID: String?

    /// Show the phone's notifications on this Mac (banners and the lists). On by default.
    @Published var showOnMac: Bool = PhoneFeed.stored(Keys.show, default: true) {
        didSet {
            PhoneFeed.defaults?.set(showOnMac, forKey: Keys.show)
            if !showOnMac { PhoneNotifier.shared.removeAll(phonePrefix: nil) }
        }
    }
    /// Play the notification sound with each banner. On by default.
    @Published var playSound: Bool = PhoneFeed.stored(Keys.sound, default: true) {
        didSet { PhoneFeed.defaults?.set(playSound, forKey: Keys.sound) }
    }

    private enum Keys {
        static let show = "phoneNotifications"
        static let sound = "phoneNotificationSound"
    }

    private struct State {
        var cursor: Int64 = 0
        var notifications: [PhoneNotification] = []
        var battery: BatteryState?
        /// The low-battery notice was shown for this discharge; cleared when charging or above 20%.
        var lowWarned = false
    }

    private var states: [String: State] = [:]
    /// Banners posted in this session, per phone, so a reconnect's snapshot doesn't show them all again.
    private var posted: [String: Set<String>] = [:]
    /// At most this many notifications are kept per phone.
    static let keep = 100

    private static var defaults: UserDefaults? { Screenshots.active ? nil : UserDefaults.standard }
    private static func stored(_ key: String, default value: Bool) -> Bool {
        guard let defaults, defaults.object(forKey: key) != nil else { return value }
        return defaults.bool(forKey: key)
    }

    /// Starts showing a phone; returns the cursor to ask EVENTS from.
    func open(phoneID: String, name: String) -> Int64 {
        if self.phoneID != phoneID { live = false }
        self.phoneID = phoneID
        phoneName = name
        publish()
        return states[phoneID]?.cursor ?? 0
    }

    func markLive() { if phoneID != nil { live = true } }

    /// The phone is gone: keep its state (and cursor) for the next connection, show nothing meanwhile.
    func close() {
        live = false
        phoneID = nil
        notifications = []
        battery = nil
    }

    var cursor: Int64 { phoneID.flatMap { states[$0]?.cursor } ?? 0 }

    /// Applies one EVENTS reply to the open phone. After a RESET, the notifications that follow are the phone's
    /// current state: they're listed, but only ones that just arrived get a banner.
    func apply(_ batch: EventBatch) {
        guard let phone = phoneID else { return }
        var state: State = states[phone] ?? State()
        var snapshot = false
        var snapshotKeys = Set<String>()
        for event in batch.events {
            switch event {
            case .reset:
                snapshot = true
                snapshotKeys = []
                state.notifications = []
            case .notification(let note):
                let old: PhoneNotification? = state.notifications.first { $0.key == note.key }
                state.notifications.removeAll { $0.key == note.key }
                state.notifications.append(note)
                if snapshot { snapshotKeys.insert(note.key) }
                let changed: Bool = old == nil || old?.title != note.title || old?.text != note.text
                let recent: Bool = Date().timeIntervalSince(note.time) < 120
                let shown: Bool = posted[phone]?.contains(note.key) == true
                let banner: Bool = changed && (!snapshot || (recent && !shown))
                if banner && showOnMac {
                    PhoneNotifier.shared.post(note, phoneID: phone, phoneName: phoneName, sound: playSound)
                    posted[phone, default: []].insert(note.key)
                }
            case .removed(let key):
                state.notifications.removeAll { $0.key == key }
                forgetBanner(key, phone: phone)
            case .battery(let level):
                state.battery = level
                warnIfLow(level, state: &state)
            }
        }
        if snapshot {
            // Banners for notifications the phone no longer has go too.
            let gone: [String] = (posted[phone] ?? []).filter { !snapshotKeys.contains($0) }
            for key in gone { forgetBanner(key, phone: phone) }
        }
        state.notifications.sort { $0.time > $1.time }
        if state.notifications.count > PhoneFeed.keep { state.notifications.removeLast(state.notifications.count - PhoneFeed.keep) }
        state.cursor = batch.cursor
        states[phone] = state
        publish()
    }

    /// A notification dismissed or answered from this Mac: gone from the list until the phone says otherwise.
    func remove(_ key: String) {
        guard let phone = phoneID, var state = states[phone] else { return }
        state.notifications.removeAll { $0.key == key }
        states[phone] = state
        forgetBanner(key, phone: phone)
        publish()
    }

    private func forgetBanner(_ key: String, phone: String) {
        posted[phone]?.remove(key)
        PhoneNotifier.shared.remove(key: key, phoneID: phone)
    }

    /// Once per discharge: a notice at 15% or lower while not charging.
    private func warnIfLow(_ level: BatteryState, state: inout State) {
        if level.charging || level.level > 20 { state.lowWarned = false; return }
        guard level.level <= 15, !state.lowWarned else { return }
        state.lowWarned = true
        PhoneNotifier.shared.notice(title: "\(phoneName) battery is low",
                                    body: "\(level.level)% left. Plug it in to charge.", sound: playSound)
    }

    private func publish() {
        let state: State? = phoneID.flatMap { states[$0] }
        notifications = state?.notifications ?? []
        battery = state?.battery
    }

    /// Sample content for screenshots only.
    func showSample(phone: String, notifications: [PhoneNotification], battery: BatteryState) {
        phoneID = "sample"; phoneName = phone; live = true
        self.notifications = notifications
        self.battery = battery
    }
}

/// macOS notifications for what the phone shows, with Reply, Dismiss on phone and Copy code; and for links sent from
/// the phone, with Open. Actions come back to the model.
final class PhoneNotifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = PhoneNotifier()

    /// Where actions go. Set when the model starts.
    @MainActor weak var model: BrowserModel?

    enum Action {
        static let reply = "reply"
        static let dismiss = "dismiss"
        static let copy = "copy"
        static let open = "open"
    }

    static let linkCategory = "link"
    private static let prefix = "phone|"

    /// Notifications need a real app bundle; a development build run from the terminal has none.
    static var available: Bool {
        !Screenshots.active && Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app"
    }

    /// Becomes the notification center's delegate and registers the action sets. Once, at launch.
    @MainActor func setUp() {
        guard PhoneNotifier.available else { return }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories(PhoneNotifier.categories())
    }

    /// One category per combination of Reply, Dismiss on phone and Copy code, and one for links.
    private static func categories() -> Set<UNNotificationCategory> {
        let reply = UNTextInputNotificationAction(identifier: Action.reply, title: "Reply", options: [],
                                                 textInputButtonTitle: "Send", textInputPlaceholder: "Reply from your Mac")
        let dismiss = UNNotificationAction(identifier: Action.dismiss, title: "Dismiss on phone", options: [])
        let copy = UNNotificationAction(identifier: Action.copy, title: "Copy code", options: [])
        let open = UNNotificationAction(identifier: Action.open, title: "Open", options: [])
        var result = Set<UNNotificationCategory>()
        for mask in 0..<8 {
            var actions: [UNNotificationAction] = []
            if mask & 1 != 0 { actions.append(reply) }
            if mask & 2 != 0 { actions.append(dismiss) }
            if mask & 4 != 0 { actions.append(copy) }
            result.insert(UNNotificationCategory(identifier: category(mask), actions: actions, intentIdentifiers: [], options: []))
        }
        result.insert(UNNotificationCategory(identifier: linkCategory, actions: [open], intentIdentifiers: [], options: []))
        return result
    }

    private static func category(_ mask: Int) -> String { "phone.\(mask)" }

    private static func identifier(_ key: String, phoneID: String) -> String { prefix + phoneID + "|" + key }

    /// Shows a phone notification: the app as subtitle, its title and text.
    @MainActor func post(_ note: PhoneNotification, phoneID: String, phoneName: String, sound: Bool) {
        guard PhoneNotifier.available else { return }
        let content = UNMutableNotificationContent()
        content.title = note.title.isEmpty ? note.app : note.title
        content.subtitle = note.app.isEmpty ? phoneName : note.app
        content.body = note.text
        content.threadIdentifier = phoneID + "|" + note.package
        content.userInfo = ["phone": phoneID, "key": note.key, "code": note.code]
        if sound { content.sound = .default }
        var mask = 0
        if note.canReply { mask |= 1 }
        if note.canDismiss { mask |= 2 }
        if !note.code.isEmpty { mask |= 4 }
        content.categoryIdentifier = PhoneNotifier.category(mask)
        let id: String = PhoneNotifier.identifier(note.key, phoneID: phoneID)
        deliver(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    /// A notice from OpenBridge itself (battery low, photos backed up, files sent), with an Open action for `link`.
    @MainActor func notice(title: String, body: String, sound: Bool = false, link: String? = nil) {
        guard PhoneNotifier.available else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if sound { content.sound = .default }
        if let link {
            content.categoryIdentifier = PhoneNotifier.linkCategory
            content.userInfo = ["url": link]
        }
        deliver(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    /// Asks for permission the first time, then shows the notification.
    @MainActor private func deliver(_ request: UNNotificationRequest) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            center.add(request, withCompletionHandler: nil)
        }
    }

    @MainActor func remove(key: String, phoneID: String) {
        guard PhoneNotifier.available else { return }
        let id: String = PhoneNotifier.identifier(key, phoneID: phoneID)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }

    /// Removes the delivered banners of one phone, or of every phone.
    @MainActor func removeAll(phonePrefix phoneID: String?) {
        guard PhoneNotifier.available else { return }
        let start: String = phoneID.map { PhoneNotifier.prefix + $0 + "|" } ?? PhoneNotifier.prefix
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let ids: [String] = delivered.map { $0.request.identifier }.filter { $0.hasPrefix(start) }
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    /// Banners show even while OpenBridge is the active app.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info: [AnyHashable: Any] = response.notification.request.content.userInfo
        let action: String = response.actionIdentifier
        let phone: String = info["phone"] as? String ?? ""
        let key: String = info["key"] as? String ?? ""
        let code: String = info["code"] as? String ?? ""
        let link: String = info["url"] as? String ?? ""
        let reply: String = (response as? UNTextInputNotificationResponse)?.userText ?? ""
        completionHandler()
        Task { @MainActor in
            PhoneNotifier.shared.handle(action: action, phone: phone, key: key, code: code, link: link, reply: reply)
        }
    }

    @MainActor private func handle(action: String, phone: String, key: String, code: String, link: String, reply: String) {
        switch action {
        case Action.open:
            if let url = URL(string: link), EventCodec.isWebLink(link) { NSWorkspace.shared.open(url) }
        case Action.copy:
            model?.copyText(code)
        case Action.dismiss:
            model?.act(on: key, phoneID: phone, .dismiss)
        case Action.reply:
            let text: String = reply.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { model?.act(on: key, phoneID: phone, .reply(text)) }
        case UNNotificationDefaultActionIdentifier:
            if !link.isEmpty, let url = URL(string: link), EventCodec.isWebLink(link) { NSWorkspace.shared.open(url); return }
            if !phone.isEmpty { model?.openNotificationsWindow() }
        default:
            break
        }
    }
}

/// Bytes per second, smoothed over about 2 seconds, from progress reports.
struct SpeedMeter {
    private(set) var speed: Double = 0
    private(set) var measured: Date?
    private var sample: (bytes: Int64, at: Date)?

    mutating func reset() { speed = 0; measured = nil; sample = nil }
    /// A new file starts from its own first report.
    mutating func restartSample() { sample = nil }

    mutating func record(_ done: Int64, at now: Date = Date()) {
        guard let last = sample else { sample = (done, now); return }
        let seconds: Double = now.timeIntervalSince(last.at)
        guard seconds >= 0.15 else { return }
        let moved: Int64 = done >= last.bytes ? done - last.bytes : done
        sample = (done, now)
        let rate: Double = Double(moved) / seconds
        let weight: Double = 1 - exp(-seconds / 2)
        speed = measured == nil ? rate : speed + (rate - speed) * weight
        measured = now
    }

    /// The speed, or 0 when nothing has moved for a few seconds.
    func current(at now: Date = Date()) -> Double {
        guard let measured, now.timeIntervalSince(measured) < 4 else { return 0 }
        return speed
    }
}

/// The photo backup running in the background: which file, how far, and how many.
struct BackupProgress: Equatable {
    var phone: String
    var name: String
    var index: Int
    var count: Int
    var fraction: Double
    /// Waiting for another transfer to finish.
    var paused: Bool

    var overall: Double {
        let total: Double = Double(max(count, 1))
        return min(1, (Double(max(index - 1, 0)) + fraction) / total)
    }
}
