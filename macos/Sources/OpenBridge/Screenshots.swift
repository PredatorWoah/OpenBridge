import SwiftUI
import AppKit
import BridgeCore

/// `OpenBridge --render-screenshots <folder>`: draws the window in every look with sample content, as PNG files for
/// the README, then quits. No window, network, USB, saved phones, texts or preferences are touched.
///
/// ImageRenderer can't draw AppKit-backed views (scroll views, text fields, menus, segmented pickers, materials) or
/// run animations, so while `active` those views draw plain stand-ins and the moving pictures show one still frame.
enum Screenshots {
    /// Set before anything else is created, and only for a screenshot run.
    static var active = false

    static let size = CGSize(width: 1280, height: 800)

    /// The folder after `--render-screenshots`, if the app was asked to render screenshots.
    static func requestedFolder(_ arguments: [String] = CommandLine.arguments) -> URL? {
        guard let flag = arguments.firstIndex(of: "--render-screenshots"), flag + 1 < arguments.count else { return nil }
        return URL(fileURLWithPath: arguments[flag + 1], isDirectory: true)
    }

    /// Renders every shot into `folder`, then ends the process (status 1 when a shot couldn't be written).
    @MainActor static func renderAndQuit(to folder: URL) -> Never {
        active = true
        _ = NSApplication.shared
        var failed = false
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            FileHandle.standardError.write(Data("Can't create \(folder.path): \(error.localizedDescription)\n".utf8))
            exit(1)
        }
        for look in Look.allCases {
            Palette.look = look
            for shot in Shot.allCases {
                let file = folder.appendingPathComponent("\(look.rawValue)-\(shot.rawValue).png")
                if !render(shot, look: look, to: file) { failed = true }
            }
            let panel = folder.appendingPathComponent("\(look.rawValue)-menubar.png")
            if !renderMenuBar(to: panel) { failed = true }
            let phonePanel = folder.appendingPathComponent("\(look.rawValue)-menubar-notifications.png")
            if !renderPhoneMenuBar(to: phonePanel) { failed = true }
            let sheet = folder.appendingPathComponent("\(look.rawValue)-notifications.png")
            if !renderNotificationsSheet(to: sheet) { failed = true }
            // 1.3.0: pairing by QR code, Now playing, and the player window.
            let qr = folder.appendingPathComponent("\(look.rawValue)-qr-pairing.png")
            if !renderQRPairing(to: qr) { failed = true }
            let mediaPanel = folder.appendingPathComponent("\(look.rawValue)-menubar-media.png")
            if !renderMediaMenuBar(to: mediaPanel) { failed = true }
            let nowPlaying = folder.appendingPathComponent("\(look.rawValue)-now-playing.png")
            if !renderNowPlayingWindow(look: look, to: nowPlaying) { failed = true }
            let player = folder.appendingPathComponent("\(look.rawValue)-player.png")
            if !renderPlayer(to: player) { failed = true }
        }
        exit(failed ? 1 : 0)
    }

    enum Shot: String, CaseIterable {
        case browser, connect, transfers
    }

    @MainActor private static func render(_ shot: Shot, look: Look, to file: URL) -> Bool {
        let model = SampleData.model(for: shot, look: look)
        let scene = LookScene(model: model, look: look, wifi: .constant(false), dropping: .constant(false),
                              openAutomatically: .constant(false), mirror: {})
            .frame(width: size.width, height: size.height)
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: scene)
        renderer.scale = 2
        renderer.proposedSize = ProposedViewSize(size)
        return write(renderer.cgImage, to: file)
    }

    /// The menu bar panel while a transfer runs, with a long phone name and a long file name: neither may wrap.
    /// The status item's icon (connected, with its progress ring) is drawn above the panel, as it sits in the menu bar.
    @MainActor private static func renderMenuBar(to file: URL) -> Bool {
        let model = SampleData.menuBarModel()
        let ring: Double = MenuBarIcon.ring(model) ?? 0
        let icon: NSImage = MenuBarGlyph.image(connected: true, progress: ring)
        let shot = VStack(alignment: .trailing, spacing: 4) {
            Image(nsImage: icon).renderingMode(.template).foregroundStyle(Color.white).padding(.trailing, 24)
            MenuBarPanel(model: model)
        }
        .padding(12)
        .background(Palette.background)
        .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: shot)
        renderer.scale = 2
        return write(renderer.cgImage, to: file)
    }

    /// The menu bar panel of a phone from 1.2.0: battery in the header, its newest notifications with long app names,
    /// titles and texts (each a single line, cut with "…"), Ring phone, Send link and the drop zone.
    @MainActor private static func renderPhoneMenuBar(to file: URL) -> Bool {
        let model = SampleData.phoneFeaturesModel()
        let icon: NSImage = MenuBarGlyph.image(connected: true, progress: nil)
        let shot = VStack(alignment: .trailing, spacing: 4) {
            Image(nsImage: icon).renderingMode(.template).foregroundStyle(Color.white).padding(.trailing, 24)
            MenuBarPanel(model: model)
        }
        .padding(12)
        .background(Palette.background)
        .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: shot)
        renderer.scale = 2
        return write(renderer.cgImage, to: file)
    }

    /// The Phone notifications sheet with the same notifications, texts up to three lines.
    @MainActor private static func renderNotificationsSheet(to file: URL) -> Bool {
        let model = SampleData.phoneFeaturesModel()
        let sheet = PhoneNotificationsSheet(model: model, feed: model.feed)
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: sheet)
        renderer.scale = 2
        renderer.proposedSize = ProposedViewSize(width: 480, height: nil)
        return write(renderer.cgImage, to: file)
    }

    /// The QR pairing sheet: the code in the look's card, about a minute and a half left.
    @MainActor private static func renderQRPairing(to file: URL) -> Bool {
        let model = BrowserModel()
        model.qr.showSample(name: "Aditya's MacBook Air")
        let sheet = QRPairingSheet(model: model, qr: model.qr)
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: sheet)
        renderer.scale = 2
        renderer.proposedSize = ProposedViewSize(width: 420, height: nil)
        return write(renderer.cgImage, to: file)
    }

    /// The menu bar panel of a phone from 1.3.0 playing music, above its notifications.
    @MainActor private static func renderMediaMenuBar(to file: URL) -> Bool {
        let model = SampleData.mediaModel()
        let icon: NSImage = MenuBarGlyph.image(connected: true, progress: nil)
        let shot = VStack(alignment: .trailing, spacing: 4) {
            Image(nsImage: icon).renderingMode(.template).foregroundStyle(Color.white).padding(.trailing, 24)
            MenuBarPanel(model: model)
        }
        .padding(12)
        .background(Palette.background)
        .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: shot)
        renderer.scale = 2
        return write(renderer.cgImage, to: file)
    }

    /// The window's connect screen while connected, with the Now playing card on the phone's card.
    @MainActor private static func renderNowPlayingWindow(look: Look, to file: URL) -> Bool {
        let model = SampleData.mediaModel()
        let scene = LookScene(model: model, look: look, wifi: .constant(false), dropping: .constant(false),
                              openAutomatically: .constant(false), mirror: {})
            .frame(width: size.width, height: size.height)
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: scene)
        renderer.scale = 2
        renderer.proposedSize = ProposedViewSize(size)
        return write(renderer.cgImage, to: file)
    }

    /// The player window's chrome around a placeholder frame, buffering.
    @MainActor private static func renderPlayer(to file: URL) -> Bool {
        let model = SampleData.phoneFeaturesModel()
        let session = PlayerSession(sample: SampleData.video)
        let screen = PlayerScreen(session: session, model: model)
            .frame(width: 880, height: 540)
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: screen)
        renderer.scale = 2
        return write(renderer.cgImage, to: file)
    }

    @MainActor private static func write(_ image: CGImage?, to file: URL) -> Bool {
        guard let image,
              let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
            FileHandle.standardError.write(Data("Couldn't render \(file.lastPathComponent)\n".utf8))
            return false
        }
        do {
            try png.write(to: file)
            print("Rendered \(file.path)")
            return true
        } catch {
            FileHandle.standardError.write(Data("Couldn't write \(file.path): \(error.localizedDescription)\n".utf8))
            return false
        }
    }
}

/// The phone, folder, selection and transfer shown in the screenshots.
@MainActor
private enum SampleData {
    static let phone = "vivo X300 Pro"
    static let screenshots = Entry(id: "s1", name: "Screenshots", directory: true, size: 0)
    static let photo = Entry(id: "s2", name: "IMG_20261019_101500.jpg", directory: false, size: 3_355_443)
    static let video = Entry(id: "s3", name: "VID_20261018_220000.mp4", directory: false, size: 811_597_824)
    static let notes = Entry(id: "s4", name: "notes.txt", directory: false, size: 4_096)
    static let report = Entry(id: "s5", name: "report.pdf", directory: false, size: 1_258_291)

    static func model(for shot: Screenshots.Shot, look: Look) -> BrowserModel {
        let model = BrowserModel()
        let connected = shot != .connect
        let path: [(id: String, name: String)] = [("", "Phone"), ("d1", "DCIM"), ("d2", "Camera")]
        let sizes: [Entry.ID: FolderSize] = [screenshots.id: FolderSize(bytes: 48_024_780, files: 31, complete: true)]
        model.showSample(phone: phone, overFastUSB: connected, path: connected ? path : [("", "Phone")],
                         entries: connected ? [screenshots, photo, video, notes, report] : [],
                         sizes: connected ? sizes : [:], saved: savedPhones)
        model.discovery.showSample(nearby)
        guard connected else { return model }
        model.selection = [photo.id, video.id]
        model.thumbnails = [photo.id: picture([RGB(0xFFB347), RGB(0xFF5C8A), RGB(0x3A2470)]),
                            video.id: picture([RGB(0x3FE0D0), RGB(0x2F7BFF), RGB(0x05214A)])]
        if shot == .transfers {
            let target = URL(fileURLWithPath: "/tmp/OpenBridge Screenshots/\(video.name)")
            let job = BrowserModel.Job.download(video, to: target, at: ["DCIM", "Camera"])
            model.transfer = BrowserModel.Transfer(name: video.name, upload: false, fraction: 0.42, remaining: [job])
            model.busy = true
        }
        return model
    }

    /// Connected over the phone's hotspot, a third of the way through a batch of 12, with names too long to fit.
    static func menuBarModel() -> BrowserModel {
        let model = BrowserModel()
        model.showSampleMenuBar(phone: "vivo X300 Pro (the family phone in the kitchen drawer)", speed: 23_400_000)
        let long = Entry(id: "m1", name: "VID_20261018_220000_birthday_party_in_the_garden_with_everyone_final_edit_v3.mp4",
                         directory: false, size: 811_597_824)
        let target = URL(fileURLWithPath: "/tmp/OpenBridge Screenshots/\(long.name)")
        let job = BrowserModel.Job.download(long, to: target, at: ["DCIM", "Camera"])
        var transfer = BrowserModel.Transfer(name: long.name, upload: false, index: 4, count: 12, fraction: 0.42,
                                             remaining: Array(repeating: job, count: 9))
        transfer.totalBytes = 4_870_000_000
        transfer.finishedBytes = 1_310_000_000
        transfer.currentBytes = 340_870_000
        transfer.currentTotal = long.size
        model.transfer = transfer
        model.busy = true
        return model
    }

    /// Connected over Wi‑Fi to a phone from 1.2.0 that is charging, with notifications whose names and texts are far
    /// too long for one line.
    static func phoneFeaturesModel() -> BrowserModel {
        let model = BrowserModel()
        let name = "vivo X300 Pro (the family phone in the kitchen drawer)"
        model.showSamplePhoneFeatures(phone: name)
        let now = Date()
        func note(_ key: String, _ app: String, _ title: String, _ text: String, minutes: Double, flags: (Bool, Bool), code: String = "") -> PhoneNotification {
            PhoneNotification(key: key, app: app, package: "com.example." + key, title: title, text: text,
                              time: now.addingTimeInterval(-minutes * 60), canReply: flags.0, canDismiss: flags.1, code: code)
        }
        let notes: [PhoneNotification] = [
            note("w1", "WhatsApp", "Mum (Family group: Sunday lunch planning and everything else)",
                 "Are you bringing the dessert on Sunday? Grandma asked twice already and says the lemon tart from last time was the best thing she has eaten all year",
                 minutes: 1, flags: (true, true)),
            note("b1", "HDFC Bank Mobile Banking and Payments", "Your one-time password",
                 "482913 is your OTP for a transaction of Rs 12,499.00 at AMAZON PAY INDIA. Valid for 10 minutes. Never share it with anyone.",
                 minutes: 3, flags: (false, true), code: "482913"),
            note("g1", "Gmail", "Quarterly report – final numbers, charts and the appendix nobody reads",
                 "Hi all,\nattached are the final numbers for the third quarter, with the charts we discussed on Tuesday.",
                 minutes: 12, flags: (true, true)),
            note("c1", "Calendar", "Dentist", "Tomorrow at 9:30 · Dr. Iyer, 2nd floor", minutes: 40, flags: (false, true)),
            note("s1", "Swiggy", "Your order is on its way", "Ravi is 4 minutes away with your order from Meghana Foods",
                 minutes: 55, flags: (false, false)),
            note("m1", "Messages", "+91 98450 12345", "Parcel delivered to the front desk", minutes: 90, flags: (true, true))
        ]
        model.feed.showSample(phone: name, notifications: notes, battery: BatteryState(level: 82, charging: true))
        return model
    }

    /// The phone from 1.3.0 playing a song that can be skipped and scrubbed, a minute and a bit in.
    static func mediaModel() -> BrowserModel {
        let model = phoneFeaturesModel()
        let song = MediaState(app: "Spotify", title: "Clair de lune (Suite bergamasque, L. 75: III)",
                              artist: "Claude Debussy · Alexis Weissenberg", duration: 302_000, position: 81_000,
                              playback: .playing, canPlayPause: true, canNext: true, canPrevious: true, canSeek: true)
        model.feed.showSampleMedia(song)
        return model
    }

    /// Two saved phones: the one in the pictures (on this Wi‑Fi now) and another.
    static var savedPhones: [SavedPhone] {
        [SavedPhone(name: phone, host: "192.168.1.23", port: 41987, fingerprint: Data(repeating: 1, count: 32), secret: Data(count: 32)),
         SavedPhone(name: "Pixel 8 Pro", host: "192.168.1.40", port: 41987, fingerprint: Data(repeating: 2, count: 32), secret: Data(count: 32))]
    }

    /// The saved vivo announcing itself, and a phone not paired yet.
    static var nearby: [PhoneDiscovery.Phone] {
        let vivo = savedPhones[0]
        return [PhoneDiscovery.Phone(name: phone, host: vivo.host, port: "41987", phoneID: vivo.id),
                PhoneDiscovery.Phone(name: "Galaxy S25", host: "192.168.1.57", port: "41987", phoneID: nil)]
    }

    /// A stand-in photo: a soft gradient.
    static func picture(_ colors: [RGB]) -> NSImage {
        let gradient = LinearGradient(colors: colors.map { $0.color() }, startPoint: .topLeading, endPoint: .bottomTrailing)
            .frame(width: 240, height: 240)
        let renderer = ImageRenderer(content: gradient)
        renderer.scale = 1
        return renderer.nsImage ?? NSImage(size: NSSize(width: 240, height: 240))
    }
}

// MARK: - Stand-ins for what ImageRenderer can't draw

/// A vertical scroll view, or (in screenshots) its content from the top, clipped.
struct FileScroll<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        if Screenshots.active {
            content()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .clipped()
        } else {
            ScrollView(content: content)
        }
    }
}

/// Lets people select the text, except in screenshots.
struct Selectable: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if Screenshots.active { content } else { content.textSelection(.enabled) }
    }
}

extension View {
    /// Frosted glass behind the view; a plain translucent fill in screenshots.
    @ViewBuilder func frosted<S: Shape>(_ shape: S) -> some View {
        if Screenshots.active {
            background(Color.white.opacity(0.07), in: shape)
        } else {
            background(.ultraThinMaterial, in: shape)
        }
    }
}

/// A menu's or picker's icon as it looks closed, for screenshots.
struct ClosedMenu: View {
    let symbol: String
    var body: some View {
        Image(systemName: symbol).font(.system(size: 13)).foregroundStyle(.white).frame(width: 34)
    }
}

/// The list/grid picker as it looks, for screenshots.
struct ClosedSegments: View {
    let grid: Bool

    var body: some View {
        HStack(spacing: 2) {
            segment("list.bullet", on: !grid)
            segment("square.grid.2x2", on: grid)
        }
        .padding(2)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        .frame(width: 76)
    }

    private func segment(_ symbol: String, on: Bool) -> some View {
        Image(systemName: symbol).font(.system(size: 11, weight: .medium))
            .frame(width: 34, height: 18)
            .background(Color.white.opacity(on ? 0.22 : 0), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}
