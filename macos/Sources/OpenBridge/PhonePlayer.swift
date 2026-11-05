import SwiftUI
import AppKit
import AVFoundation
import AVKit
import UniformTypeIdentifiers
import BridgeCore

// Playing videos and music straight from the phone: AVPlayer reads the file through a custom URL scheme, and each
// byte range it asks for comes from the phone on a connection of its own (RESUME, 6), closed when the player seeks
// or the window closes. Works over Wi‑Fi, the phone's hotspot and Fast USB; not over USB File transfer.

/// Which phone files open in the player.
enum PlayableMedia {
    static let video: Set<String> = ["mp4", "mov", "m4v", "webm", "mkv"]
    static let audio: Set<String> = ["mp3", "m4a", "aac", "flac", "wav"]

    static func ext(_ entry: Entry) -> String { (entry.name as NSString).pathExtension.lowercased() }
    static func isPlayable(_ entry: Entry) -> Bool { !entry.directory && (video.contains(ext(entry)) || audio.contains(ext(entry))) }
    static func isVideo(_ entry: Entry) -> Bool { !entry.directory && video.contains(ext(entry)) }

    /// The uniform type AVFoundation is told the file has.
    static func contentType(_ entry: Entry) -> String {
        let known: [String: String] = [
            "mp4": "public.mpeg-4", "m4v": "com.apple.m4v-video", "mov": "com.apple.quicktime-movie",
            "mp3": "public.mp3", "m4a": "com.apple.m4a-audio", "aac": "public.aac-audio",
            "wav": "com.microsoft.waveform-audio", "flac": "org.xiph.flac",
            "webm": "org.webmproject.webm", "mkv": "org.matroska.mkv"
        ]
        let name: String = ext(entry)
        return known[name] ?? UTType(filenameExtension: name)?.identifier ?? "public.data"
    }
}

extension BrowserModel {
    /// The connection can stream: Wi‑Fi, the phone's hotspot or Fast USB.
    var canStream: Bool { connected && client is BridgeClient }

    /// Opens a video or song from the phone in a player window.
    func play(_ entry: Entry) {
        guard canStream, PlayableMedia.isPlayable(entry) else { download([entry]); return }
        PlayerWindows.shared.open(entry, model: self)
        Diagnostics.log("Playing a \(PlayableMedia.ext(entry)) file from the phone")
    }
}

// MARK: - Serving byte ranges

/// Answers AVFoundation's requests for the file from the phone. Each data request gets its own connection, which is
/// closed when AVFoundation cancels it (seeking) or the player closes.
final class StreamLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "openbridge-stream"

    let entry: Entry
    let queue = DispatchQueue(label: "org.openbridge.stream")
    private let client: () async -> BridgeClient?
    /// Requests being answered, on `queue`.
    private var running: [ObjectIdentifier: Task<Void, Never>] = [:]
    /// The last error reaching the phone, for the window to explain a failure.
    private(set) var lastError: String?
    /// At most this much is read from the phone per step.
    private static let chunk: Int64 = 256 * 1024

    init(entry: Entry, client: @escaping () async -> BridgeClient?) {
        self.entry = entry
        self.client = client
    }

    /// The made-up address AVFoundation is given, so it asks this loader for every byte.
    var url: URL {
        var parts = URLComponents()
        parts.scheme = StreamLoader.scheme
        parts.host = "phone"
        parts.path = "/" + (entry.name.isEmpty ? "media" : entry.name)
        return parts.url ?? URL(string: StreamLoader.scheme + "://phone/media")!
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        if let info = loadingRequest.contentInformationRequest {
            info.contentType = PlayableMedia.contentType(entry)
            info.contentLength = entry.size
            info.isByteRangeAccessSupported = true
        }
        guard let data = loadingRequest.dataRequest else {
            loadingRequest.finishLoading()
            return true
        }
        let start: Int64 = data.currentOffset != 0 ? data.currentOffset : data.requestedOffset
        let wanted: Int64 = data.requestedOffset + Int64(data.requestedLength)
        let end: Int64 = data.requestsAllDataToEndOfResource ? entry.size : min(entry.size, wanted)
        guard start < end else {
            loadingRequest.finishLoading()
            return true
        }
        let id = ObjectIdentifier(loadingRequest)
        running[id] = Task { [weak self] in await self?.serve(loadingRequest, from: start, to: end, id: id) }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        running.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    /// Stops every request: the window closed.
    func close() {
        queue.async {
            for task in self.running.values { task.cancel() }
            self.running = [:]
        }
    }

    private func serve(_ request: AVAssetResourceLoadingRequest, from start: Int64, to end: Int64, id: ObjectIdentifier) async {
        var range: FileRange?
        do {
            guard let phone = await client() else { throw BridgeError.message("The phone isn't connected any more.") }
            let opened: FileRange = try await phone.range(of: entry, from: start)
            range = opened
            var offset: Int64 = start
            while offset < end {
                try Task.checkCancellation()
                let size: Int64 = min(StreamLoader.chunk, end - offset)
                let bytes: Data = try await opened.read(upTo: Int(size))
                if bytes.isEmpty { break }
                offset += Int64(bytes.count)
                queue.sync { if !request.isCancelled { request.dataRequest?.respond(with: bytes) } }
            }
            opened.close()
            queue.sync {
                if !request.isCancelled && !request.isFinished { request.finishLoading() }
                running[id] = nil
            }
        } catch {
            range?.close()
            let cancelled: Bool = error is CancellationError || Task.isCancelled
            queue.sync {
                if !cancelled {
                    lastError = error.localizedDescription
                    if !request.isCancelled && !request.isFinished { request.finishLoading(with: error) }
                }
                running[id] = nil
            }
        }
    }
}

// MARK: - A playing file

/// One file playing: the player, its loader, and whether it's buffering or failed.
@MainActor
final class PlayerSession: ObservableObject {
    let entry: Entry
    let player: AVPlayer?
    private let loader: StreamLoader?
    @Published private(set) var buffering = true
    @Published private(set) var failure: String?
    private var observations: [NSKeyValueObservation] = []

    init(entry: Entry, model: BrowserModel) {
        self.entry = entry
        weak var owner: BrowserModel? = model
        let loader = StreamLoader(entry: entry) { await MainActor.run { owner?.client as? BridgeClient } }
        let asset = AVURLAsset(url: loader.url)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        self.loader = loader
        self.player = player
        watch(item, player)
        checkPlayable(asset)
        player.play()
    }

    /// For screenshots: a window with no player in it.
    init(sample entry: Entry) {
        self.entry = entry
        player = nil
        loader = nil
        buffering = true
    }

    var isVideo: Bool { PlayableMedia.isVideo(entry) }

    private func watch(_ item: AVPlayerItem, _ player: AVPlayer) {
        let status: NSKeyValueObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let failed: Bool = item.status == .failed
            guard failed, let self else { return }
            Task { @MainActor in self.fail() }
        }
        let waiting: NSKeyValueObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let waiting: Bool = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            guard let self else { return }
            Task { @MainActor in self.buffering = waiting }
        }
        let ready: NSKeyValueObservation = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] item, _ in
            let keepsUp: Bool = item.isPlaybackLikelyToKeepUp
            guard keepsUp, let self else { return }
            Task { @MainActor in self.buffering = false }
        }
        observations = [status, waiting, ready]
    }

    /// Formats AVFoundation can't read (MKV, most WebM) fail here, before any time is wasted buffering.
    private func checkPlayable(_ asset: AVURLAsset) {
        Task { [weak self] in
            let playable: Bool = (try? await asset.load(.isPlayable)) ?? false
            if !playable { self?.fail() }
        }
    }

    private func fail() {
        guard failure == nil else { return }
        buffering = false
        player?.pause()
        if let reason = loader?.lastError {
            failure = "Couldn't read “\(entry.name)” from the phone: \(reason)"
        } else {
            let kind: String = PlayableMedia.ext(entry).uppercased()
            failure = "This Mac can't play \(kind) files. Download “\(entry.name)” and open it with an app that plays \(kind), like VLC or IINA."
        }
        Diagnostics.log("Player: \(PlayableMedia.ext(entry)) file didn't play")
    }

    /// The window closed: stop playing and close every connection.
    func close() {
        observations = []
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        loader?.close()
    }
}

// MARK: - Windows

/// The player windows, one per file; opening a file that's already playing brings its window forward.
@MainActor
final class PlayerWindows: NSObject, NSWindowDelegate {
    static let shared = PlayerWindows()
    private var windows: [NSWindow: PlayerSession] = [:]

    func open(_ entry: Entry, model: BrowserModel) {
        if let playing = windows.first(where: { $0.value.entry.id == entry.id }) {
            playing.key.makeKeyAndOrderFront(nil)
            return
        }
        let session = PlayerSession(entry: entry, model: model)
        let screen = PlayerScreen(session: session, model: model)
        let video: Bool = session.isVideo
        let size = NSSize(width: video ? 880 : 460, height: video ? 540 : 300)
        let style: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
        let window = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: style, backing: .buffered, defer: false)
        window.title = entry.name
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isReleasedWhenClosed = false
        window.minSize = NSSize(width: 380, height: video ? 260 : 220)
        window.contentView = NSHostingView(rootView: screen)
        window.delegate = self
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        windows[window] = session
    }

    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow, let session = windows.removeValue(forKey: window) else { return }
        session.close()
    }
}

// MARK: - The window's content

/// The video (or a picture for music), the title bar with the name, buffering and Download, and a clear message
/// with Download when this Mac can't play the format.
struct PlayerScreen: View {
    @ObservedObject var session: PlayerSession
    @ObservedObject var model: BrowserModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if session.isVideo { picture } else { music }
            if let failure = session.failure { failed(failure) }
        }
        .overlay(alignment: .top) { titleBar }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var picture: some View {
        if let player = session.player, !Screenshots.active {
            PlayerSurface(player: player, inline: false)
        } else {
            PlayerPlaceholder()
        }
    }

    /// Music: the look's colours with a note, and the controls under it.
    private var music: some View {
        VStack(spacing: 0) {
            ZStack {
                Palette.background
                Image(systemName: "music.note")
                    .font(.system(size: 64, weight: .semibold))
                    .foregroundStyle(Palette.sunset)
                    .shadow(color: Palette.amber.opacity(0.5), radius: 18)
                    .accessibilityHidden(true)
            }
            if let player = session.player, !Screenshots.active {
                PlayerSurface(player: player, inline: true).frame(height: 56)
            }
        }
    }

    private var titleBar: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(session.entry.name).font(.system(size: 13, weight: .semibold)).lineLimit(1).truncationMode(.middle)
                Text("\(ByteCountFormatter.string(fromByteCount: session.entry.size, countStyle: .file)) · from \(model.deviceName)")
                    .font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if session.buffering && session.failure == nil {
                HStack(spacing: 6) {
                    if !Screenshots.active { ProgressView().controlSize(.small) }
                    Text("Buffering…").font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                .accessibilityElement(children: .combine)
            }
            Button { model.download([session.entry]) } label: { Label("Download", systemImage: "arrow.down.circle") }
                .buttonStyle(GhostButton())
                .disabled(!model.connected || model.busy)
                .help("Save “\(session.entry.name)” on this Mac")
        }
        .padding(.leading, 80).padding(.trailing, 12).padding(.vertical, 8)
        .background(LinearGradient(colors: [Color.black.opacity(0.75), Color.black.opacity(0)], startPoint: .top, endPoint: .bottom))
    }

    private func failed(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "film.stack").font(.system(size: 34)).foregroundStyle(Palette.amber).accessibilityHidden(true)
            Text(message).font(.system(size: 13.5)).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true).frame(maxWidth: 420)
            Button("Download") { model.download([session.entry]) }
                .buttonStyle(SunsetButton())
                .disabled(!model.connected || model.busy)
        }
        .padding(24)
        .glass(18, border: Palette.amber.opacity(0.4))
        .padding(24)
    }
}

/// A still frame for screenshots and while there's no player: the look's colours with a play mark.
struct PlayerPlaceholder: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Palette.plum, Palette.violet.opacity(0.7), Palette.pink.opacity(0.6), Palette.amber.opacity(0.5)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: "play.circle.fill").font(.system(size: 72)).foregroundStyle(Color.white.opacity(0.85))
                .shadow(color: .black.opacity(0.3), radius: 12)
                .accessibilityHidden(true)
        }
    }
}

/// AVKit's player view with its own controls: floating over a video, inline under music.
struct PlayerSurface: NSViewRepresentable {
    let player: AVPlayer
    let inline: Bool

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = player
        view.controlsStyle = inline ? .inline : .floating
        view.showsFullScreenToggleButton = !inline
        view.allowsPictureInPicturePlayback = !inline
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}
