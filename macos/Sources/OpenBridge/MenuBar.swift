import SwiftUI
import AppKit
import BridgeCore

// The status item in the menu bar: the sun arch (outlined when no phone is connected, filled when one is, with a
// progress ring while files move) and a small panel with the phone, the transfer and the main actions.

/// The menu bar icon. The menu bar shows only images and text, so the glyph is drawn into a template image, which
/// macOS tints for light and dark menu bars.
struct MenuBarIcon: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        let ring: Double? = MenuBarIcon.ring(model)
        Image(nsImage: MenuBarGlyph.image(connected: model.connected, progress: ring))
            .accessibilityLabel(MenuBarIcon.label(model))
    }

    /// The ring's progress while a transfer runs, in steps of half a percent so the image isn't redrawn for nothing.
    @MainActor static func ring(_ model: BrowserModel) -> Double? {
        guard let transfer = model.transfer, !transfer.done, model.busy else { return nil }
        let overall: Double = TransferProgress.overall(transfer)
        let steps: Double = 200
        return (overall * steps).rounded() / steps
    }

    @MainActor static func label(_ model: BrowserModel) -> String {
        guard model.connected else { return "OpenBridge, no phone connected" }
        if let ring = ring(model) { return "OpenBridge, transferring, \(Int(ring * 100)) percent" }
        return "OpenBridge, connected to \(model.deviceName)"
    }
}

/// The sun arch as an 18-point template image: a half sun on the horizon, outlined or filled, and an optional ring.
enum MenuBarGlyph {
    static func image(connected: Bool, progress: Double?) -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let image = NSImage(size: size, flipped: true) { _ in
            draw(connected: connected, progress: progress)
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "OpenBridge"
        return image
    }

    private static func draw(connected: Bool, progress: Double?) {
        let ringing: Bool = progress != nil
        // With the ring around it, the arch shrinks to fit inside.
        let radius: CGFloat = ringing ? 4.2 : 6.2
        let horizonY: CGFloat = ringing ? 11.2 : 12.8
        let half: CGFloat = ringing ? 6.2 : 8.4
        let center = NSPoint(x: 9, y: horizonY)
        NSColor.black.setStroke()
        NSColor.black.setFill()

        // The half sun: in a flipped image, 180° to 360° is the upper half.
        let arch = NSBezierPath()
        arch.appendArc(withCenter: center, radius: radius, startAngle: 180, endAngle: 360, clockwise: false)
        if connected {
            arch.close()
            arch.fill()
        } else {
            arch.lineWidth = 1.5
            arch.stroke()
        }

        let horizon = NSBezierPath()
        horizon.move(to: NSPoint(x: 9 - half, y: horizonY + 0.75))
        horizon.line(to: NSPoint(x: 9 + half, y: horizonY + 0.75))
        horizon.lineWidth = 1.5
        horizon.lineCapStyle = .round
        horizon.stroke()

        guard let progress else { return }
        let ringCenter = NSPoint(x: 9, y: 9)
        let ringRadius: CGFloat = 8
        let track = NSBezierPath()
        track.appendArc(withCenter: ringCenter, radius: ringRadius, startAngle: 0, endAngle: 360)
        track.lineWidth = 1.6
        NSColor.black.withAlphaComponent(0.3).setStroke()
        track.stroke()
        let fraction: Double = min(max(progress, 0), 1)
        guard fraction > 0 else { return }
        // From the top, clockwise on screen (counterclockwise in the flipped coordinates' angles).
        let start: CGFloat = 270
        let end: CGFloat = start + CGFloat(fraction * 360)
        let arc = NSBezierPath()
        arc.appendArc(withCenter: ringCenter, radius: ringRadius, startAngle: start, endAngle: end, clockwise: false)
        arc.lineWidth = 1.8
        arc.lineCapStyle = .round
        NSColor.black.setStroke()
        arc.stroke()
    }
}

/// The panel that opens from the menu bar: the phone and how it's connected, the running transfer, and the actions.
struct MenuBarPanel: View {
    @ObservedObject var model: BrowserModel
    var open: () -> Void = {}
    /// The panel follows the look chosen in Settings.
    @ObservedObject private var looks = LookStore.shared

    static let width: CGFloat = 300

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        VStack(alignment: .leading, spacing: 12) {
            PhoneLine(model: model)
            if let transfer = model.transfer, !transfer.done {
                Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1)
                if Screenshots.active {
                    TransferLines(model: model, transfer: transfer, now: Date())
                } else {
                    TimelineView(.periodic(from: Date(), by: 1)) { timeline in
                        TransferLines(model: model, transfer: transfer, now: timeline.date)
                    }
                }
            }
            Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1)
            PanelActions(model: model, open: open)
        }
        .padding(14)
        .frame(width: MenuBarPanel.width, alignment: .leading)
        .background(Palette.night.opacity(0.55), in: shape)
        .frosted(shape)
        .overlay(shape.strokeBorder(Palette.amber.opacity(0.18)))
        .padding(6)
        .foregroundStyle(Palette.look.colors.fg.color())
        .environment(\.colorScheme, .dark)
        .id(looks.look)
    }
}

/// What the menu bar item opens, with the means to open OpenBridge's window again after it was closed.
struct MenuBarContent: View {
    @ObservedObject var model: BrowserModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        MenuBarPanel(model: model, open: { MainWindow.show { openWindow(id: MainWindow.id) } })
    }
}

/// The phone's name, the connection dot and the transport.
private struct PhoneLine: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        HStack(spacing: 10) {
            ArchMark(size: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(model.connected ? model.deviceName : "No phone connected")
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .lineLimit(1).truncationMode(.tail)
                HStack(spacing: 6) {
                    Circle().fill(model.connected ? Palette.ok : Palette.muted.opacity(0.6)).frame(width: 7, height: 7)
                        .shadow(color: model.connected ? Palette.ok : .clear, radius: 3)
                        .accessibilityHidden(true)
                    Text(state).font(.system(size: 12)).foregroundStyle(Palette.muted)
                        .lineLimit(1).truncationMode(.tail)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .layoutPriority(1)
        }
        .accessibilityElement(children: .combine)
    }

    private var state: String {
        if model.connected { return "Connected · " + ConnectText.shortTransport(model) }
        return model.busy ? "Connecting…" : "Disconnected"
    }
}

/// The running file, a progress bar, percent, how much of how much, the speed and the time left.
private struct TransferLines: View {
    @ObservedObject var model: BrowserModel
    let transfer: BrowserModel.Transfer
    let now: Date

    var body: some View {
        let overall: Double = TransferProgress.overall(transfer)
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                Image(systemName: transfer.upload ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                    .foregroundStyle(Palette.amber).accessibilityHidden(true)
                Text(transfer.name).lineLimit(1).truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.system(size: 12.5, weight: .semibold))
            PanelProgressBar(fraction: overall)
                .accessibilityElement()
                .accessibilityLabel("Progress of \(transfer.name)")
                .accessibilityValue("\(Int(overall * 100)) percent")
            HStack(spacing: 8) {
                Text("\(Int(overall * 100))%").monospacedDigit().lineLimit(1)
                Spacer(minLength: 4)
                Text(amount).monospacedDigit().lineLimit(1).truncationMode(.middle)
            }
            .font(.system(size: 11.5)).foregroundStyle(Palette.muted)
            HStack(spacing: 8) {
                Text(speedText).monospacedDigit().lineLimit(1)
                Spacer(minLength: 4)
                Text(leftText).monospacedDigit().lineLimit(1).truncationMode(.tail)
            }
            .font(.system(size: 11.5)).foregroundStyle(Palette.muted)
        }
    }

    /// "312 MB of 774 MB", with "file 4 of 12" for a batch.
    private var amount: String {
        var parts: [String] = []
        if transfer.bytesInAll > 0 {
            let done: String = ByteCountFormatter.string(fromByteCount: transfer.bytesSoFar, countStyle: .file)
            let all: String = ByteCountFormatter.string(fromByteCount: transfer.bytesInAll, countStyle: .file)
            parts.append("\(done) of \(all)")
        }
        if transfer.count > 1 { parts.append("file \(transfer.index) of \(transfer.count)") }
        return parts.joined(separator: " · ")
    }

    private var speedText: String {
        if !model.busy { return "Stopped" }
        let rate: Double = model.currentSpeed(at: now)
        guard rate > 0 else { return "Measuring speed…" }
        return MenuBarFormat.speed(rate)
    }

    private var leftText: String {
        guard model.busy, let seconds = model.timeLeft(at: now) else { return "" }
        return MenuBarFormat.timeLeft(seconds)
    }
}

/// A slim bar in the look's gradient, gliding between progress reports.
private struct PanelProgressBar: View {
    let fraction: Double

    var body: some View {
        GeometryReader { geometry in
            let width: CGFloat = geometry.size.width * CGFloat(min(max(fraction, 0), 1))
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.1))
                Capsule().fill(Palette.bar).frame(width: max(width, 6))
                    .shadow(color: Palette.amber.opacity(0.5), radius: 4)
            }
        }
        .frame(height: 6)
        .animation(.linear(duration: 0.35), value: fraction)
    }
}

/// Open OpenBridge, Disconnect while connected, Cancel transfer while one runs.
private struct PanelActions: View {
    @ObservedObject var model: BrowserModel
    var open: () -> Void

    var body: some View {
        let running: Bool = model.busy && model.transfer.map { !$0.done } == true
        HStack(spacing: 8) {
            Button(action: open) { Text("Open OpenBridge").lineLimit(1) }
                .buttonStyle(PanelButton(prominent: true))
            Spacer(minLength: 0)
            if running {
                Button(action: model.cancel) { Text("Cancel transfer").lineLimit(1) }
                    .buttonStyle(PanelButton())
            } else if model.connected {
                Button(action: model.disconnect) { Text("Disconnect").lineLimit(1) }
                    .buttonStyle(PanelButton())
            }
        }
        if running && model.connected {
            HStack {
                Spacer(minLength: 0)
                Button(action: model.disconnect) { Text("Disconnect").lineLimit(1) }
                    .buttonStyle(.plain).font(.system(size: 11.5)).foregroundStyle(Palette.muted)
            }
        }
    }
}

/// A compact button in the look's colours: the accent gradient for the main one, glass for the others.
private struct PanelButton: ButtonStyle {
    var prominent = false
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        let pressed: Bool = configuration.isPressed
        return configuration.label
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(prominent ? Color.white : Palette.look.colors.fg.color())
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background { background }
            .overlay(Capsule().strokeBorder(Color.white.opacity(prominent ? 0.3 : 0.14)))
            .opacity(enabled ? (pressed ? 0.8 : 1) : 0.4)
            .fixedSize()
    }

    @ViewBuilder private var background: some View {
        if prominent { Capsule().fill(Palette.sunset) } else { Capsule().fill(Color.white.opacity(0.08)) }
    }
}

enum MenuBarFormat {
    /// "18.4 MB/s".
    static func speed(_ bytesPerSecond: Double) -> String {
        let bytes: Int64 = Int64(bytesPerSecond.rounded())
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) + "/s"
    }

    /// "About 3 min left", "12 s left", "Less than a second left".
    static func timeLeft(_ seconds: Double) -> String {
        if seconds < 1 { return "Less than a second left" }
        if seconds < 60 { return "\(Int(seconds.rounded())) s left" }
        let minutes: Int = Int((seconds / 60).rounded())
        if minutes < 60 { return "About \(minutes) min left" }
        let hours: Int = minutes / 60
        let rest: Int = minutes % 60
        return rest == 0 ? "About \(hours) h left" : "About \(hours) h \(rest) min left"
    }
}

/// Brings OpenBridge's window to the front, opening a new one when it was closed.
enum MainWindow {
    static let id = "main"

    @MainActor static func show(open: () -> Void) {
        NSApp.unhide(nil)
        NSApp.activate(ignoringOtherApps: true)
        var windows: [NSWindow] = NSApp.windows.filter { window in
            window.identifier?.rawValue.hasPrefix(MainWindow.id) == true
        }
        if windows.isEmpty {
            // Window identifiers are SwiftUI's own: fall back to any open browser window, never the mirror or Settings.
            windows = NSApp.windows.filter { window in
                let name: String = window.identifier?.rawValue ?? ""
                let shown: Bool = window.isVisible || window.isMiniaturized
                return shown && window.canBecomeMain && !name.hasPrefix("mirror") && !name.contains("Settings")
            }
        }
        if windows.isEmpty { open(); return }
        for window in windows {
            if window.isMiniaturized { window.deminiaturize(nil) }
            window.makeKeyAndOrderFront(nil)
        }
    }
}
