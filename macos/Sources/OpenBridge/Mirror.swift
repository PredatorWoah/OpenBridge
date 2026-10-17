import SwiftUI
import AppKit
import AVFoundation
import CoreMedia
import BridgeCore
import MTPCore

/// Screen mirroring over USB debugging: pushes the scrcpy server, shows its H.264 video with Apple's
/// hardware decoder, and sends touches, keys and the clipboard back. Blocking work runs on its own threads.
final class MirrorSession: ObservableObject, @unchecked Sendable {
    enum Phase: Equatable {
        case connecting(String)
        case allow
        case streaming
        case failed(String)
    }

    @Published private(set) var phase: Phase = .connecting("Connecting to the phone…")
    @Published private(set) var videoSize = CGSize(width: 1080, height: 2400)
    @Published private(set) var deviceName = ""
    let layer = AVSampleBufferDisplayLayer()

    private let lock = NSLock()
    private var adb: ADBConnection?
    private var control: ADBStream?
    private var running = false
    private var format: CMVideoFormatDescription?
    /// After a flush the decoder needs a keyframe; frames that depend on older ones are dropped until then.
    /// Only touched on the main thread.
    private var waitingForKeyFrame = true
    private var lastReset = Date.distantPast
    private let controlQueue = DispatchQueue(label: "org.openbridge.mirror.control")
    private var clipboardTimer: Timer?
    private var pasteboardCount = NSPasteboard.general.changeCount

    init() { layer.videoGravity = .resizeAspect }

    private func main(_ work: @escaping () -> Void) { DispatchQueue.main.async(execute: work) }

    func start() {
        lock.lock()
        guard !running else { lock.unlock(); return }
        running = true
        lock.unlock()
        phase = .connecting("Connecting to the phone…")
        Thread { self.run() }.start()
    }

    func stop() {
        lock.lock()
        running = false
        let adb = self.adb
        self.adb = nil; control = nil
        lock.unlock()
        adb?.close()
        clipboardTimer?.invalidate(); clipboardTimer = nil
    }

    private var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return running }

    private func run() {
        do {
            let transport = try USBTransport.openADB()
            let key = try ADBKey.loadOrCreate()
            let name = "OpenBridge@" + (Host.current().localizedName ?? "Mac").replacingOccurrences(of: " ", with: "-")
            let adb = try ADBConnection.connect(transport: transport, key: key, name: name) { self.main { self.phase = .allow } }
            lock.lock(); self.adb = adb; let stillRunning = running; lock.unlock()
            guard stillRunning else { adb.close(); return }
            main { self.phase = .connecting("Starting mirroring on the phone…") }

            guard let url = Bundle.main.url(forResource: "scrcpy-server", withExtension: "jar"), let jar = try? Data(contentsOf: url) else {
                throw BridgeError.message("The mirroring helper is missing from this copy of OpenBridge. Download the app again.")
            }
            try adb.push(jar, to: Scrcpy.serverPath)
            let scid = UInt32.random(in: 0...0x7FFF_FFFF)
            let shell = try adb.open("shell:" + Scrcpy.command(scid: scid, maxSize: 1920))
            let log = ShellLog(shell)

            // The server listens once it's up: retry for about ten seconds.
            var video: ADBStream?
            for _ in 0..<100 where isRunning {
                if let stream = try? adb.open(Scrcpy.socket(scid), timeout: 2) { video = stream; break }
                Thread.sleep(forTimeInterval: 0.1)
            }
            guard let video else { throw BridgeError.message("The phone didn't start mirroring. \(log.text)") }
            _ = try video.read(1)                                   // dummy byte
            let control = try adb.open(Scrcpy.socket(scid))
            lock.lock(); self.control = control; lock.unlock()
            let device = String(decoding: try video.read(64).prefix { $0 != 0 }, as: UTF8.self)
            let codec = try video.read(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            guard codec == Scrcpy.h264 else {
                throw BridgeError.message(codec == 0 ? "The phone's screen can't be captured right now (it may be showing protected content)." : "The phone couldn't start its screen encoder.")
            }
            main {
                self.deviceName = device
                self.phase = .streaming
                self.startClipboardSync()
            }
            Thread { self.readDeviceMessages(control) }.start()
            try readVideo(video)
        } catch {
            if isRunning { main { self.phase = .failed(error.localizedDescription) } }
            stop()
        }
    }

    private func readVideo(_ video: ADBStream) throws {
        while isRunning {
            switch try Scrcpy.parseHeader(try video.read(12)) {
            case .session(let width, let height):
                main { self.videoSize = CGSize(width: width, height: height) }
            case .media(let size, let config, let keyFrame):
                let payload = try video.read(size)
                if config { configure(payload) } else { show(payload, keyFrame: keyFrame) }
            }
        }
    }

    /// SPS and PPS describe the stream; a new pair arrives after rotation.
    private func configure(_ payload: Data) {
        let units = H264.units(payload)
        guard let sps = units.first(where: { H264.type($0) == 7 }), let pps = units.first(where: { H264.type($0) == 8 }) else { return }
        var description: CMVideoFormatDescription?
        sps.withUnsafeBytes { s in
            pps.withUnsafeBytes { p in
                let pointers = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                _ = CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &description)
            }
        }
        format = description
        main { self.layer.flush(); self.waitingForKeyFrame = true }
    }

    private func show(_ payload: Data, keyFrame: Bool) {
        guard let format else { return }
        let data = H264.lengthPrefixed(H264.units(payload).filter { ![7, 8, 9].contains(H264.type($0)) })
        guard !data.isEmpty else { return }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: data.count,
                                                 blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                 dataLength: data.count, flags: 0, blockBufferOut: &block) == noErr, let block else { return }
        data.withUnsafeBytes { _ = CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: data.count) }
        var sample: CMSampleBuffer?
        var size = data.count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] = true
            attachments.first?[kCMSampleAttachmentKey_NotSync] = !keyFrame
            attachments.first?[kCMSampleAttachmentKey_DependsOnOthers] = !keyFrame
        }
        main {
            // macOS pauses the hardware decoder when the window is hidden, the display sleeps or the GPU
            // changes, and a decode error stops it too. Flush, and ask the phone for a keyframe right away
            // instead of waiting seconds for the next one (this is what rotating the phone used to fix).
            if self.layer.status == .failed || self.layer.requiresFlushToResumeDecoding {
                self.layer.flush()
                self.waitingForKeyFrame = true
                self.requestKeyFrame()
            }
            if self.waitingForKeyFrame {
                guard keyFrame else { self.requestKeyFrame(); return }
                self.waitingForKeyFrame = false
            }
            self.layer.enqueue(sample)
        }
    }

    /// The window is visible again (or the Mac woke up): a still screen sends no new frames, so get a fresh
    /// picture now rather than waiting for something on the phone to change.
    func refresh() {
        guard phase == .streaming else { return }
        if layer.status == .failed || layer.requiresFlushToResumeDecoding { layer.flush() }
        waitingForKeyFrame = true
        lastReset = .distantPast
        requestKeyFrame()
    }

    /// At most once a second, so a burst of dropped frames doesn't restart the encoder repeatedly.
    private func requestKeyFrame() {
        guard Date().timeIntervalSince(lastReset) > 1 else { return }
        lastReset = Date()
        send(Scrcpy.resetVideo())
    }

    // MARK: Input

    func send(_ data: Data) {
        controlQueue.async {
            self.lock.lock(); let control = self.control; self.lock.unlock()
            try? control?.write(data)
        }
    }

    func press(_ keycode: Int32) {
        send(Scrcpy.key(.down, keycode: keycode))
        send(Scrcpy.key(.up, keycode: keycode))
    }

    func type(_ text: String) { Scrcpy.text(text).forEach(send) }

    // MARK: Clipboard

    /// The phone's clipboard comes to the Mac as it changes (scrcpy's clipboard sync).
    private func readDeviceMessages(_ control: ADBStream) {
        while isRunning {
            let message: String?
            do { message = try Scrcpy.readDeviceMessage({ try control.read($0) }) } catch { return }   // stream ended
            guard let text = message else { continue }
            main {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                self.pasteboardCount = NSPasteboard.general.changeCount   // don't echo it back
            }
        }
    }

    /// Text copied on the Mac goes to the phone's clipboard.
    private func startClipboardSync() {
        clipboardTimer?.invalidate()
        pasteboardCount = NSPasteboard.general.changeCount
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            let count = NSPasteboard.general.changeCount
            guard count != self.pasteboardCount else { return }
            self.pasteboardCount = count
            if let text = NSPasteboard.general.string(forType: .string), let message = Scrcpy.setClipboard(text) { self.send(message) }
        }
    }

    /// ⌘V in the mirror window pastes the Mac's clipboard on the phone.
    func paste() {
        guard let text = NSPasteboard.general.string(forType: .string), let message = Scrcpy.setClipboard(text, paste: true) else { return }
        send(message)
    }
}

/// Collects what the server prints, to explain a failure to start.
private final class ShellLog: @unchecked Sendable {
    private let lock = NSLock()
    private var output = ""
    init(_ stream: ADBStream) {
        Thread {
            while let chunk = stream.readAvailable() {
                self.lock.lock(); self.output += String(decoding: chunk, as: UTF8.self); self.lock.unlock()
            }
        }.start()
    }
    var text: String {
        lock.lock(); defer { lock.unlock() }
        let lines = output.split(separator: "\n").filter { $0.contains("ERROR") || $0.contains("Exception") }
        return lines.suffix(2).joined(separator: " ")
    }
}

/// The video, with mouse, trackpad and keyboard input mapped to the phone.
private final class MirrorNSView: NSView {
    var session: MirrorSession?
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }

    override init(frame: NSRect) { super.init(frame: frame); wantsLayer = true }
    required init?(coder: NSCoder) { fatalError("not used") }

    func attach(_ session: MirrorSession) {
        guard self.session !== session else { return }
        self.session = session
        layer?.addSublayer(session.layer)
        needsLayout = true
    }

    override func layout() {
        super.layout()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        session?.layer.frame = bounds
        CATransaction.commit()
    }

    private var observers: [NSObjectProtocol] = []

    override func viewDidMoveToWindow() {
        window?.makeFirstResponder(self)
        observers.forEach(NotificationCenter.default.removeObserver)
        NSWorkspace.shared.notificationCenter.removeObserver(self)
        guard let window else { observers = []; return }
        observers = [
            NotificationCenter.default.addObserver(forName: NSWindow.didChangeOcclusionStateNotification, object: window, queue: .main) { [weak self] _ in
                if window.occlusionState.contains(.visible) { self?.session?.refresh() }
            },
            NotificationCenter.default.addObserver(forName: NSWindow.didDeminiaturizeNotification, object: window, queue: .main) { [weak self] _ in
                self?.session?.refresh()
            },
        ]
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(woke), name: NSWorkspace.didWakeNotification, object: nil)
    }

    @objc private func woke() { session?.refresh() }

    /// Mac point to phone pixels, inside the letterboxed video.
    private func phonePoint(_ event: NSEvent) -> (x: Int, y: Int, w: Int, h: Int)? {
        guard let session else { return nil }
        let size = session.videoSize
        let rect = AVMakeRect(aspectRatio: size, insideRect: bounds)
        guard rect.width > 0, rect.height > 0 else { return nil }
        let p = convert(event.locationInWindow, from: nil)
        let x = min(max((p.x - rect.minX) / rect.width, 0), 1) * size.width
        let y = min(max((p.y - rect.minY) / rect.height, 0), 1) * size.height
        return (Int(x), Int(y), Int(size.width), Int(size.height))
    }

    private func touch(_ action: Scrcpy.Action, _ event: NSEvent, buttons: Int32, actionButton: Int32) {
        guard let p = phonePoint(event) else { return }
        session?.send(Scrcpy.touch(action, x: p.x, y: p.y, width: p.w, height: p.h, pressure: action == .up ? 0 : 1,
                                   actionButton: actionButton, buttons: buttons))
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        touch(.down, event, buttons: Scrcpy.primaryButton, actionButton: Scrcpy.primaryButton)
    }
    override func mouseDragged(with event: NSEvent) { touch(.move, event, buttons: Scrcpy.primaryButton, actionButton: 0) }
    override func mouseUp(with event: NSEvent) { touch(.up, event, buttons: 0, actionButton: Scrcpy.primaryButton) }
    /// Right click is Back (and wakes the screen), like scrcpy.
    override func rightMouseDown(with event: NSEvent) { session?.send(Scrcpy.backOrScreenOn(.down)) }
    override func rightMouseUp(with event: NSEvent) { session?.send(Scrcpy.backOrScreenOn(.up)) }
    override func otherMouseDown(with event: NSEvent) { session?.press(Scrcpy.Key.home) }

    override func scrollWheel(with event: NSEvent) {
        guard let p = phonePoint(event) else { return }
        let scale = event.hasPreciseScrollingDeltas ? 0.1 : 1
        session?.send(Scrcpy.scroll(x: p.x, y: p.y, width: p.w, height: p.h,
                                    dx: Double(event.scrollingDeltaX) * scale, dy: Double(event.scrollingDeltaY) * scale))
    }

    override func keyDown(with event: NSEvent) {
        guard let session else { return }
        if event.modifierFlags.contains(.command) {
            if event.charactersIgnoringModifiers == "v" { session.paste() } else { super.keyDown(with: event) }
            return
        }
        switch event.keyCode {
        case 36, 76: session.press(Scrcpy.Key.enter)
        case 51: session.press(Scrcpy.Key.delete)
        case 117: session.press(Scrcpy.Key.forwardDelete)
        case 53: session.press(Scrcpy.Key.back)
        case 48: session.press(Scrcpy.Key.tab)
        case 123: session.press(Scrcpy.Key.left)
        case 124: session.press(Scrcpy.Key.right)
        case 125: session.press(Scrcpy.Key.down)
        case 126: session.press(Scrcpy.Key.up)
        default:
            if let text = event.characters, !text.isEmpty, !text.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) {
                session.type(text)
            }
        }
    }
}

private struct MirrorScreen: NSViewRepresentable {
    let session: MirrorSession
    func makeNSView(context: Context) -> MirrorNSView { let view = MirrorNSView(); view.attach(session); return view }
    func updateNSView(_ view: MirrorNSView, context: Context) { view.attach(session) }
}

/// The phone's screen, mirrored and controllable over USB.
struct MirrorView: View {
    @StateObject private var session = MirrorSession()

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            switch session.phase {
            case .streaming:
                VStack(spacing: 10) {
                    MirrorScreen(session: session)
                        .aspectRatio(session.videoSize, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .shadow(color: .black.opacity(0.4), radius: 18, y: 8)
                    HStack(spacing: 6) {
                        key("chevron.backward", "Back", Scrcpy.Key.back)
                        key("circle", "Home", Scrcpy.Key.home)
                        key("square.on.square", "Recent apps", Scrcpy.Key.recents)
                        Divider().frame(height: 18)
                        key("speaker.wave.1", "Volume down", Scrcpy.Key.volumeDown)
                        key("speaker.wave.3", "Volume up", Scrcpy.Key.volumeUp)
                        key("power", "Power", Scrcpy.Key.power)
                        Button { session.send(Scrcpy.rotate()) } label: { Image(systemName: "rotate.right") }
                            .buttonStyle(GhostButton()).help("Rotate")
                    }
                }
                .padding(.top, 30).padding([.horizontal, .bottom], 14)
            case .connecting(let text):
                status(title: "Mirroring your phone", text: text, spinner: true)
            case .allow:
                status(title: "Tap Allow on your phone",
                        text: "Your phone asks “Allow USB debugging?”. Tick “Always allow from this computer” and tap Allow. You only do this once.",
                        spinner: true)
            case .failed(let message):
                VStack(spacing: 14) {
                    status(title: "Mirroring stopped", text: message, spinner: false)
                    Button("Try again") { session.start() }.buttonStyle(SunsetButton())
                    Text("Mirroring needs USB debugging: Settings › About phone › tap Build number 7 times, then Settings › Developer options › USB debugging.")
                        .font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center).frame(maxWidth: 300)
                }
            }
        }
        .frame(minWidth: 300, minHeight: 520)
        .preferredColorScheme(.dark)
        .navigationTitle(session.deviceName.isEmpty ? "Phone Screen" : session.deviceName)
        .onAppear { session.start() }
        .onDisappear { session.stop() }
    }

    private func key(_ symbol: String, _ help: String, _ code: Int32) -> some View {
        Button { session.press(code) } label: { Image(systemName: symbol) }.buttonStyle(GhostButton()).help(help)
    }

    private func status(title: String, text: String, spinner: Bool) -> some View {
        VStack(spacing: 12) {
            SunArch(active: spinner, connected: false, centerY: 0.5, radius: 42, startScanning: spinner)
                .frame(width: 160, height: 140)
            Text(title).font(.system(size: 18, weight: .bold, design: .rounded))
            Text(text).font(.system(size: 13)).foregroundStyle(Palette.muted).multilineTextAlignment(.center).frame(maxWidth: 300)
                .textSelection(.enabled)
        }
        .padding(24)
    }
}
