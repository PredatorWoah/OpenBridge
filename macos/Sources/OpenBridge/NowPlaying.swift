import SwiftUI
import AppKit
import MediaPlayer
import BridgeCore

// What's playing on the phone (phone app 1.3.0, INFO revision 24): the Now playing card in the menu bar panel and
// the window, MEDIA CONTROL from its buttons, and the Mac's media keys and Control Center while the phone plays.

extension BrowserModel {
    /// The connected phone sends what's playing and takes play, pause, next, previous and seek.
    var mediaControls: Bool {
        guard phoneFeatures, let revision = phoneInfo?.revision else { return false }
        return revision >= BridgeClient.mediaRevision
    }

    /// Asks the phone to play, pause, skip or seek, showing the result at once (the phone's next event corrects it).
    func controlMedia(_ command: MediaCommand) {
        guard mediaControls, let bridge = client as? BridgeClient else { return }
        if let playing = feed.media { feed.expect(NowPlayingMath.expected(playing, after: command, since: feed.mediaAt)) }
        Task {
            do { try await bridge.mediaControl(command) } catch { show(error.localizedDescription) }
        }
    }
}

enum NowPlayingMath {
    /// What `playing` looks like right after `command`, before the phone says.
    static func expected(_ playing: MediaState, after command: MediaCommand, since: Date) -> MediaState {
        let now: Int64? = playing.position(after: Date().timeIntervalSince(since))
        switch command {
        case .play: return playing.with(position: now, playback: .playing)
        case .pause: return playing.with(position: now, playback: .paused)
        case .playPause:
            let next: MediaState.Playback = playing.playback == .playing ? .paused : .playing
            return playing.with(position: now, playback: next)
        case .seek(let millis): return playing.with(position: millis, playback: playing.playback)
        case .next, .previous: return playing.with(position: now, playback: playing.playback)
        }
    }

    /// "1:05", or "1:02:05" past an hour.
    static func time(_ millis: Int64) -> String {
        let seconds: Int64 = max(millis, 0) / 1000
        let hours: Int64 = seconds / 3600
        let minutes: Int64 = (seconds % 3600) / 60
        let rest: Int64 = seconds % 60
        if hours > 0 { return String(format: "%lld:%02lld:%02lld", hours, minutes, rest) }
        return String(format: "%lld:%02lld", minutes, rest)
    }
}

// MARK: - Media keys and Control Center

/// Makes the phone's music this Mac's Now Playing while it's connected and something plays or is paused there, so
/// the media keys, AirPods controls and Control Center control the phone. Released when nothing plays, on
/// disconnecting, or when turned off in Settings.
@MainActor
final class NowPlayingBridge {
    static let shared = NowPlayingBridge()
    /// Where commands go. Set when the model starts.
    weak var model: BrowserModel?
    private var targets: [(MPRemoteCommand, Any)] = []
    private var holding = false

    func update(media: MediaState?, at: Date) {
        guard !Screenshots.active else { return }
        guard let media, media.active else { release(); return }
        registerIfNeeded()
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info(media, at: at)
        center.playbackState = media.playback == .playing ? .playing : .paused
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.isEnabled = media.canPlayPause
        commands.pauseCommand.isEnabled = media.canPlayPause
        commands.togglePlayPauseCommand.isEnabled = media.canPlayPause
        commands.nextTrackCommand.isEnabled = media.canNext
        commands.previousTrackCommand.isEnabled = media.canPrevious
        commands.changePlaybackPositionCommand.isEnabled = media.canSeek && media.duration != nil
        if !holding { Diagnostics.log("Media keys control the phone's music") }
        holding = true
    }

    private func info(_ media: MediaState, at: Date) -> [String: Any] {
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = media.title.isEmpty ? media.app : media.title
        info[MPMediaItemPropertyArtist] = media.artist
        info[MPMediaItemPropertyAlbumTitle] = media.app
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        if let duration = media.duration { info[MPMediaItemPropertyPlaybackDuration] = Double(duration) / 1000 }
        if let position = media.position(after: Date().timeIntervalSince(at)) {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(position) / 1000
        }
        info[MPNowPlayingInfoPropertyPlaybackRate] = media.playback == .playing ? 1.0 : 0.0
        return info
    }

    private func registerIfNeeded() {
        guard targets.isEmpty else { return }
        let commands = MPRemoteCommandCenter.shared()
        add(commands.playCommand, .play)
        add(commands.pauseCommand, .pause)
        add(commands.togglePlayPauseCommand, .playPause)
        add(commands.nextTrackCommand, .next)
        add(commands.previousTrackCommand, .previous)
        let seek: Any = commands.changePlaybackPositionCommand.addTarget { event in
            guard let change = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let millis = Int64((change.positionTime * 1000).rounded())
            Task { @MainActor in NowPlayingBridge.shared.model?.controlMedia(.seek(max(millis, 0))) }
            return .success
        }
        targets.append((commands.changePlaybackPositionCommand, seek))
    }

    private func add(_ command: MPRemoteCommand, _ media: MediaCommand) {
        let target: Any = command.addTarget { _ in
            Task { @MainActor in NowPlayingBridge.shared.model?.controlMedia(media) }
            return .success
        }
        targets.append((command, target))
    }

    /// Gives Now Playing back to the Mac's own apps.
    private func release() {
        guard holding || !targets.isEmpty else { return }
        for (command, target) in targets {
            command.removeTarget(target)
            command.isEnabled = false
        }
        targets = []
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nil
        center.playbackState = .stopped
        if holding { Diagnostics.log("Media keys released") }
        holding = false
    }
}

// MARK: - The card

/// The Now playing card where it belongs: nothing unless the phone takes media controls and something plays.
struct NowPlayingSlot: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var feed: PhoneFeed
    var compact = false
    /// A line above it, as between the menu bar panel's parts.
    var divided = false

    var body: some View {
        if model.mediaControls, let playing = feed.media {
            if divided { Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1) }
            NowPlayingCard(model: model, media: playing, since: feed.mediaAt, look: Palette.look, compact: compact)
                .transition(.opacity)
        }
    }
}

/// The app, title and artist, the position advancing while it plays (draggable when the app allows seeking), and
/// previous, play/pause and next, in the look's style: Dusk's glass and sunset bar, Aurora's glowing thread with a
/// comet, Tide's water rising with the song.
struct NowPlayingCard: View {
    @ObservedObject var model: BrowserModel
    let media: MediaState
    let since: Date
    let look: Look
    var compact = false

    var body: some View {
        Group {
            if Screenshots.active || media.playback != .playing {
                content(Date())
            } else {
                TimelineView(.periodic(from: Date(), by: 1)) { timeline in content(timeline.date) }
            }
        }
    }

    private func content(_ now: Date) -> some View {
        let position: Int64? = media.position(after: now.timeIntervalSince(since))
        let fraction: Double = NowPlayingCard.fraction(position, media.duration)
        let shape = RoundedRectangle(cornerRadius: compact ? 12 : 16, style: .continuous)
        return VStack(alignment: .leading, spacing: compact ? 8 : 10) {
            heading
            MediaScrubber(look: look, fraction: fraction, position: position, duration: media.duration,
                          canSeek: media.canSeek) { millis in model.controlMedia(.seek(millis)) }
            buttons
        }
        .padding(compact ? 10 : 14)
        .background { NowPlayingBackground(look: look, fraction: fraction, shape: shape) }
        .clipShape(shape)
        .overlay(shape.strokeBorder(border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Now playing on the phone")
    }

    static func fraction(_ position: Int64?, _ duration: Int64?) -> Double {
        guard let position, let duration, duration > 0 else { return 0 }
        return min(max(Double(position) / Double(duration), 0), 1)
    }

    private var border: Color {
        switch look {
        case .dusk: return Palette.amber.opacity(0.35)
        case .aurora: return Palette.amber.opacity(0.4)
        case .tide: return Palette.sunGold.opacity(0.35)
        }
    }

    private var heading: some View {
        HStack(spacing: 10) {
            Image(systemName: "music.note")
                .font(.system(size: compact ? 14 : 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: compact ? 32 : 40, height: compact ? 32 : 40)
                .background(Palette.sunset, in: RoundedRectangle(cornerRadius: compact ? 8 : 10, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(compact ? "NOW PLAYING" : "NOW PLAYING ON THE PHONE")
                    .font(.system(size: 9.5, weight: .semibold)).tracking(1.1).foregroundStyle(Palette.muted)
                Text(media.title.isEmpty ? media.app : media.title)
                    .font(.system(size: compact ? 13 : 14.5, weight: .bold, design: .rounded))
                    .lineLimit(1).truncationMode(.tail)
                Text(subtitle).font(.system(size: compact ? 11.5 : 12)).foregroundStyle(Palette.muted)
                    .lineLimit(1).truncationMode(.tail)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// "Claude Debussy · Spotify".
    private var subtitle: String {
        let parts: [String] = [media.artist, media.app].filter { !$0.isEmpty }
        return parts.joined(separator: " · ")
    }

    private var buttons: some View {
        let playing: Bool = media.playback == .playing
        return HStack(spacing: compact ? 18 : 24) {
            Spacer(minLength: 0)
            MediaButton(symbol: "backward.fill", label: "Previous", size: compact ? 13 : 15, enabled: media.canPrevious) {
                model.controlMedia(.previous)
            }
            MediaButton(symbol: playing ? "pause.fill" : "play.fill", label: playing ? "Pause" : "Play",
                        size: compact ? 15 : 18, prominent: true, enabled: media.canPlayPause) {
                model.controlMedia(.playPause)
            }
            MediaButton(symbol: "forward.fill", label: "Next", size: compact ? 13 : 15, enabled: media.canNext) {
                model.controlMedia(.next)
            }
            Spacer(minLength: 0)
        }
    }
}

private struct MediaButton: View {
    let symbol: String
    let label: String
    let size: CGFloat
    var prominent = false
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: prominent ? size * 2.4 : size * 1.8, height: prominent ? size * 2.4 : size * 1.8)
                .background { if prominent { Circle().fill(Palette.sunset) } }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .help(label)
        .accessibilityLabel(label)
    }
}

/// The card's background in the look's style.
private struct NowPlayingBackground: View {
    let look: Look
    let fraction: Double
    let shape: RoundedRectangle

    var body: some View {
        let colors: LookColors = look.colors
        switch look {
        case .dusk:
            shape.fill(Color.white.opacity(0.06))
        case .aurora:
            ZStack {
                shape.fill(colors.night.color(0.55))
                RadialGradient(colors: [colors.amber.color(0.22), .clear], center: .topLeading, startRadius: 0, endRadius: 220)
                RadialGradient(colors: [colors.pink.color(0.18), .clear], center: .bottomTrailing, startRadius: 0, endRadius: 200)
            }
        case .tide:
            // Water rises with the song.
            GeometryReader { geometry in
                ZStack(alignment: .bottom) {
                    shape.fill(colors.dusk.color(0.7))
                    LinearGradient(colors: [colors.amber.color(0.28), colors.violet.color(0.4)], startPoint: .top, endPoint: .bottom)
                        .frame(height: geometry.size.height * CGFloat(0.12 + 0.88 * fraction))
                        .animation(.easeInOut(duration: 0.9), value: fraction)
                }
            }
        }
    }
}

/// The position on a bar in the look's style, with the time on either side. Drag (or click) to seek when the
/// playing app allows it.
struct MediaScrubber: View {
    let look: Look
    let fraction: Double
    let position: Int64?
    let duration: Int64?
    let canSeek: Bool
    let seek: (Int64) -> Void
    @State private var dragging: Double?

    var body: some View {
        let shown: Double = dragging ?? fraction
        let seekable: Bool = canSeek && (duration ?? 0) > 0
        VStack(spacing: 3) {
            GeometryReader { geometry in
                track(shown, width: geometry.size.width)
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(drag(width: geometry.size.width), including: seekable ? .all : .none)
            }
            .frame(height: 14)
            HStack {
                Text(position.map(NowPlayingMath.time) ?? "–:––")
                Spacer(minLength: 4)
                Text(remaining(shown))
            }
            .font(.system(size: 10.5, weight: .medium)).monospacedDigit().foregroundStyle(Palette.muted)
        }
        .accessibilityElement()
        .accessibilityLabel("Position")
        .accessibilityValue(position.map(NowPlayingMath.time) ?? "Unknown")
        .accessibilityAdjustableAction { direction in
            guard seekable, let position else { return }
            let step: Int64 = direction == .increment ? 10_000 : -10_000
            seek(max(0, min(position + step, duration ?? position)))
        }
    }

    private func remaining(_ shown: Double) -> String {
        guard let duration, duration > 0 else { return "" }
        let left: Int64 = duration - Int64(Double(duration) * shown)
        return "-" + NowPlayingMath.time(left)
    }

    private func drag(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in dragging = clamp(value.location.x / max(width, 1)) }
            .onEnded { value in
                let target: Double = clamp(value.location.x / max(width, 1))
                dragging = nil
                if let duration { seek(Int64(Double(duration) * target)) }
            }
    }

    private func clamp(_ value: CGFloat) -> Double { min(max(Double(value), 0), 1) }

    @ViewBuilder private func track(_ shown: Double, width: CGFloat) -> some View {
        let filled: CGFloat = max(width * CGFloat(shown), 4)
        switch look {
        case .dusk:
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.12)).frame(height: 5)
                Capsule().fill(Palette.bar).frame(width: filled, height: 5)
                    .shadow(color: Palette.amber.opacity(0.5), radius: 4)
                Circle().fill(Palette.sunGold).frame(width: 11, height: 11)
                    .shadow(color: Palette.amber, radius: 4)
                    .offset(x: filled - 5.5)
            }
        case .aurora:
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.1)).frame(height: 2)
                Capsule().fill(Palette.sunset).frame(width: filled, height: 2)
                    .shadow(color: Palette.amber.opacity(0.8), radius: 3)
                Circle().fill(Palette.cream).frame(width: 7, height: 7)
                    .shadow(color: Palette.amber, radius: 6).shadow(color: Palette.violet, radius: 10)
                    .offset(x: filled - 3.5)
            }
        case .tide:
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.night.opacity(0.5)).frame(height: 8)
                Capsule().fill(LinearGradient(colors: [Palette.violet, Palette.amber], startPoint: .leading, endPoint: .trailing))
                    .frame(width: filled, height: 8)
                Capsule().strokeBorder(Palette.sunGold.opacity(0.4), lineWidth: 1).frame(height: 8)
            }
        }
    }
}
