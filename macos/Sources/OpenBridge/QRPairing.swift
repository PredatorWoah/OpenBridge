import SwiftUI
import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import BridgeCore

// Pairing by QR code (OBP3, phone app 1.3.0): this Mac shows a code with a fresh key, the phone scans it, and the Mac
// tries each phone it can reach (announced on the Wi‑Fi, at the gateway on the phone's hotspot, over Fast USB) until
// one proves it holds the key. Phones too old for it are offered the 6-digit code instead.

/// The code on screen and the search for the phone that scans it. Lives as long as the model; runs while the sheet
/// is open.
@MainActor
final class QRPairer: ObservableObject {
    enum Phase: Equatable {
        /// Showing the code and looking for the phone.
        case waiting
        /// Paired with this phone; the success animation plays, then it opens.
        case paired(String)
        /// The code is older than 2 minutes: forgotten here and on the phone.
        case expired
        case failed(String)
    }

    /// A phone that answered but is too old for QR codes.
    struct OldPhone: Identifiable, Equatable {
        let id: String
        let name: String
        let host: String
        let port: UInt16
        let cable: Bool
    }

    /// The text in the code, and the code itself (dark modules on a clear background, one pixel each).
    @Published private(set) var link = ""
    @Published private(set) var image: CGImage?
    @Published private(set) var expires = Date()
    @Published private(set) var phase: Phase = .waiting
    @Published private(set) var oldPhones: [OldPhone] = []

    private var key: Data?
    private var task: Task<Void, Never>?
    private weak var model: BrowserModel?
    /// A tunnel this search opened to a phone on the cable, and old phones found over it.
    private var cable: FastUSB.Found?
    private var oldCable: FastUSB.Found?

    /// Shows a fresh code and starts looking.
    func start(_ model: BrowserModel) {
        self.model = model
        refresh()
    }

    /// A new code: a new key, and the search starts over.
    func refresh() {
        stopLooking()
        oldPhones = []
        guard let model else { return }
        let fresh: Data
        do { fresh = try QRPairing.newKey() } catch {
            phase = .failed(error.localizedDescription)
            return
        }
        key = fresh
        link = QRPairing.link(key: fresh, name: BridgeClient.computerName)
        image = QRImage.make(link)
        expires = Date().addingTimeInterval(QRPairing.lifetime)
        phase = .waiting
        Diagnostics.log("QR pairing: showing a new code")
        task = Task { await look(model) }
    }

    /// The sheet closed: forget the key and close what the search opened.
    func stop() {
        stopLooking()
        key = nil
        link = ""
        image = nil
        if phase == .waiting { phase = .expired }
    }

    private func stopLooking() {
        task?.cancel(); task = nil
        cable?.tunnel.stop(); cable = nil
        if let old = oldCable, model?.cableCandidate?.tunnel !== old.tunnel { old.tunnel.stop() }
        oldCable = nil
    }

    /// Sample state for screenshots only: a fixed code, about a minute and a half left.
    func showSample(name: String) {
        let sample = Data(0..<16)
        link = QRPairing.link(key: sample, name: name)
        image = QRImage.make(link)
        expires = Date().addingTimeInterval(97)
        phase = .waiting
    }

    // MARK: Looking for the phone

    private struct Target {
        let id: String
        let name: String
        let host: String
        let port: UInt16
        let cable: FastUSB.Found?
    }

    private enum Outcome {
        case paired(Target, BridgeClient)
        case tooOld(Target)
        case nothing
    }

    private func look(_ model: BrowserModel) async {
        var round = 0
        while !Task.isCancelled {
            guard let key, Date() < expires else { expire(); return }
            let targets: [Target] = await self.targets(model, round: round)
            guard !Task.isCancelled else { return }
            if let (target, client) = await attempt(targets, key: key) {
                await finish(target, client: client, model: model)
                return
            }
            round += 1
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
    }

    private func expire() {
        guard phase == .waiting else { return }
        phase = .expired
        key = nil
        cable?.tunnel.stop(); cable = nil
        Diagnostics.log("QR pairing: the code expired")
    }

    /// Every phone to try this round: announced ones, the gateway on both ports, and the cable.
    private func targets(_ model: BrowserModel, round: Int) async -> [Target] {
        let old: Set<String> = Set(oldPhones.map(\.id))
        var result: [Target] = []
        var hosts = Set<String>()
        for phone in model.discovery.phones {
            guard let port = UInt16(phone.port), Wire.privateIPv4(phone.host), !hosts.contains(phone.host) else { continue }
            hosts.insert(phone.host)
            let id: String = phone.host + ":" + phone.port
            if !old.contains(id) { result.append(Target(id: id, name: phone.name, host: phone.host, port: port, cable: nil)) }
        }
        if let gateway = await model.gatewayAddress(), !hosts.contains(gateway) {
            for port: UInt16 in [41987, 41986] {
                let id: String = gateway + ":" + String(port)
                if !old.contains(id) { result.append(Target(id: id, name: "Phone (this hotspot)", host: gateway, port: port, cable: nil)) }
            }
        }
        if let found = await cableTarget(model, round: round), !old.contains("cable") {
            result.append(Target(id: "cable", name: found.model ?? "Phone on the cable", host: "", port: 0, cable: found))
        }
        return result
    }

    /// The phone on the cable: the one the model found, or one this search finds now and then by itself.
    private func cableTarget(_ model: BrowserModel, round: Int) async -> FastUSB.Found? {
        if let found = model.cableCandidate { return found }
        if let cable { return cable }
        guard model.usbDebugging, !model.usbNeedsAllow, round % 4 == 1 else { return nil }
        let found: FastUSB.Found? = try? await FastUSB.find(allowPrompt: nil, startSharing: false, known: { _ in false },
                                                            log: { Diagnostics.log($0) })
        guard let found else { return nil }
        if Task.isCancelled { found.tunnel.stop(); return nil }
        cable = found
        return found
    }

    /// Tries every target at once; returns the first that paired. Phones too old are listed for the 6-digit code.
    private func attempt(_ targets: [Target], key: Data) async -> (Target, BridgeClient)? {
        guard !targets.isEmpty else { return nil }
        var won: (Target, BridgeClient)?
        var tooOld: [Target] = []
        await withTaskGroup(of: Outcome.self) { group in
            for target in targets {
                group.addTask { await QRPairer.tryPairing(target, key: key) }
            }
            for await outcome in group {
                switch outcome {
                case .paired(let target, let client):
                    if won == nil { won = (target, client) }
                case .tooOld(let target):
                    tooOld.append(target)
                case .nothing:
                    break
                }
            }
        }
        if won == nil { noteOld(tooOld) }
        return won
    }

    private nonisolated static func tryPairing(_ target: Target, key: Data) async -> Outcome {
        do {
            let client: BridgeClient
            if let cable = target.cable {
                client = try await BridgeClient.pairQR(tunnel: cable.tunnel.localPort, key: key)
            } else {
                client = try await BridgeClient.pairQR(host: target.host, port: target.port, key: key)
            }
            return .paired(target, client)
        } catch QRPairingError.tooOld {
            return .tooOld(target)
        } catch QRPairingError.wrongPhone {
            await MainActor.run { Diagnostics.log("QR pairing: a phone answered with another code (ignored)") }
            return .nothing
        } catch {
            return .nothing
        }
    }

    private func noteOld(_ found: [Target]) {
        for target in found where !oldPhones.contains(where: { $0.id == target.id }) {
            let isCable: Bool = target.cable != nil
            oldPhones.append(OldPhone(id: target.id, name: target.name, host: target.host, port: target.port, cable: isCable))
            if isCable { oldCable = target.cable; if cable?.tunnel === target.cable?.tunnel { cable = nil } }
            Diagnostics.log("QR pairing: a phone with OpenBridge before 1.3 answered; offering the 6-digit code")
        }
    }

    private func finish(_ target: Target, client: BridgeClient, model: BrowserModel) async {
        key = nil
        if cable?.tunnel === target.cable?.tunnel { cable = nil }   // the model keeps this tunnel now
        Diagnostics.log(target.cable == nil ? "QR pairing: paired over Wi‑Fi" : "QR pairing: paired over Fast USB")
        phase = .paired(target.name)
        // Let the success animation play, then open the phone.
        try? await Task.sleep(nanoseconds: 1_300_000_000)
        do {
            try await model.adoptPaired(client, name: target.name, cable: target.cable)
            model.showQRPairing = false
        } catch {
            target.cable?.tunnel.stop()
            phase = .failed(error.localizedDescription)
        }
    }

    // MARK: Older phones

    /// Pairs with an older phone the usual way: compare 6-digit codes.
    func pairWithCode(_ old: OldPhone, model: BrowserModel) {
        // The tunnel to an old phone on the cable goes to the model, so the search mustn't close it.
        let tunnel: FastUSB.Found? = old.cable ? oldCable : nil
        if old.cable { oldCable = nil }
        stopLooking()
        if old.cable {
            if let found = model.cableCandidate ?? tunnel { model.pairWithCode(over: found) }
        } else {
            model.host = old.host
            model.port = String(old.port)
            model.pair(name: old.name == "Phone (this hotspot)" ? nil : old.name)
        }
    }
}

/// QR codes drawn with Core Image: one pixel per module, dark on clear, scaled up without smoothing when shown.
enum QRImage {
    private static let context = CIContext()

    static func make(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let code = filter.outputImage else { return nil }
        let colors = CIFilter.falseColor()
        colors.inputImage = code
        colors.color0 = CIColor(red: 0, green: 0, blue: 0, alpha: 1)
        colors.color1 = CIColor(red: 0, green: 0, blue: 0, alpha: 0)
        guard let output = colors.outputImage else { return nil }
        return context.createCGImage(output, from: output.extent)
    }
}

// MARK: - The sheet

/// "Pair with QR code": the code in a card in the look's style, the time left, what's happening, and the 6-digit
/// code for phones too old for QR codes.
struct QRPairingSheet: View {
    @ObservedObject var model: BrowserModel
    @ObservedObject var qr: QRPairer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: 12) {
                ArchMark(size: 34)
                Text("Pair with QR code").font(.system(size: 20, weight: .bold, design: .rounded))
                Spacer(minLength: 8)
                Button { close() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted)
                    .keyboardShortcut(.cancelAction).help("Close").accessibilityLabel("Close")
            }
            Text("Open OpenBridge on your phone and scan this code. It pairs at once: no codes to compare.")
                .font(.system(size: 13)).foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            QRCard(image: qr.image, look: Palette.look, expired: qr.phase == .expired, paired: pairedName != nil,
                   refresh: qr.refresh)
            QRFooter(qr: qr)
            details
        }
        .padding(24)
        .frame(width: 420)
        .background(Palette.background)
        .preferredColorScheme(.dark)
        .onAppear { if !Screenshots.active { qr.start(model) } }
        .onDisappear { if !Screenshots.active { qr.stop() } }
    }

    private var pairedName: String? {
        if case .paired(let name) = qr.phase { return name }
        return nil
    }

    @ViewBuilder private var details: some View {
        if let code = model.pairingCode {
            PairingCode(code: code)
        } else if model.busy && !model.status.isEmpty {
            Text(model.status).font(.system(size: 12.5)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
        }
        if case .failed(let message) = qr.phase {
            Text(message).font(.system(size: 12.5)).foregroundStyle(Palette.amber).multilineTextAlignment(.center)
        } else if let error = model.error, !model.overUSB, model.pairingCode == nil {
            Text(error).font(.system(size: 12.5)).foregroundStyle(Palette.amber).multilineTextAlignment(.center)
        }
        ForEach(qr.oldPhones) { phone in
            HStack(spacing: 10) {
                Image(systemName: phone.cable ? "cable.connector" : "iphone").foregroundStyle(Palette.amber).frame(width: 20)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(phone.name).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    Text("Has an older OpenBridge, without QR codes").font(.system(size: 11.5)).foregroundStyle(Palette.muted)
                }
                Spacer(minLength: 8)
                Button("Pair with a code") { qr.pairWithCode(phone, model: model) }
                    .buttonStyle(GhostButton()).disabled(model.busy)
            }
            .padding(10)
            .glass(12)
        }
    }

    private func close() {
        if model.busy && !model.connected { model.cancel() }
        dismiss()
    }
}

/// The time left on a ring, Refresh, and what the search is doing.
private struct QRFooter: View {
    @ObservedObject var qr: QRPairer

    var body: some View {
        HStack(spacing: 10) {
            if Screenshots.active {
                CountdownRing(expires: qr.expires, now: Date())
            } else {
                TimelineView(.periodic(from: Date(), by: 1)) { timeline in
                    CountdownRing(expires: qr.expires, now: timeline.date)
                }
            }
            Text(status).font(.system(size: 12.5)).foregroundStyle(Palette.muted)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: qr.refresh) { Label("New code", systemImage: "arrow.clockwise") }
                .buttonStyle(GhostButton())
                .disabled(isPaired)
                .help("Show a new code")
        }
    }

    private var isPaired: Bool {
        if case .paired = qr.phase { return true }
        return false
    }

    private var status: String {
        switch qr.phase {
        case .waiting: return "Looking for your phone on this Wi‑Fi, its hotspot and the cable…"
        case .paired(let name): return "Paired with \(name)"
        case .expired: return "This code expired. Show a new one to pair."
        case .failed: return "Pairing didn't finish."
        }
    }
}

/// Minutes and seconds left, on a ring that empties over the code's 2 minutes.
struct CountdownRing: View {
    let expires: Date
    let now: Date

    var body: some View {
        let left: Double = max(0, expires.timeIntervalSince(now))
        let fraction: Double = left / QRPairing.lifetime
        let seconds: Int = Int(left.rounded(.up))
        let label: String = String(format: "%d:%02d", seconds / 60, seconds % 60)
        ZStack {
            Circle().stroke(Color.white.opacity(0.12), lineWidth: 3)
            Circle().trim(from: 0, to: CGFloat(fraction))
                .stroke(Palette.sunset, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.linear(duration: 1), value: fraction)
            Text(label).font(.system(size: 10.5, weight: .semibold, design: .rounded)).monospacedDigit()
        }
        .frame(width: 40, height: 40)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(seconds) seconds left")
    }
}

/// The QR code on a light plate (scanners need contrast), in a card in the look's style: Dusk's glass with the sun's
/// ring, Aurora's glowing halo, Tide's porthole with water at its foot.
struct QRCard: View {
    let image: CGImage?
    let look: Look
    var expired = false
    var paired = false
    var refresh: () -> Void = {}

    static let side: CGFloat = 236

    var body: some View {
        let colors: LookColors = look.colors
        plate(colors)
            .padding(18)
            .background { frame(colors) }
            .overlay { if expired { expiredCover } }
            .overlay { if paired { PairedCheck() } }
            .animation(.easeInOut(duration: 0.3), value: expired)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(paired ? "Paired" : expired ? "The code expired" : "QR code to scan with OpenBridge on the phone")
    }

    private func plate(_ colors: LookColors) -> some View {
        let shape = RoundedRectangle(cornerRadius: look == .tide ? 26 : 18, style: .continuous)
        return ZStack {
            shape.fill(colors.cream.color())
            if let image {
                Image(decorative: image, scale: 1)
                    .renderingMode(.template)
                    .interpolation(.none)
                    .resizable()
                    .foregroundStyle(colors.night.color())
                    .padding(14)
            }
        }
        .frame(width: QRCard.side, height: QRCard.side)
        .blur(radius: expired ? 6 : 0)
        .opacity(paired ? 0.35 : 1)
    }

    @ViewBuilder private func frame(_ colors: LookColors) -> some View {
        switch look {
        case .dusk:
            let shape = RoundedRectangle(cornerRadius: 28, style: .continuous)
            shape.fill(Color.white.opacity(0.07))
                .overlay(shape.strokeBorder(AngularGradient(gradient: colors.ring, center: .center), lineWidth: 3))
                .shadow(color: colors.pink.color(0.35), radius: 18, y: 8)
        case .aurora:
            let shape = RoundedRectangle(cornerRadius: 34, style: .continuous)
            ZStack {
                shape.fill(colors.night.color(0.6))
                shape.strokeBorder(AngularGradient(colors: [colors.pink.color(), colors.violet.color(), colors.amber.color(), colors.pink.color()],
                                                   center: .center), lineWidth: 1.5)
            }
            .shadow(color: colors.amber.color(0.45), radius: 26)
            .shadow(color: colors.violet.color(0.3), radius: 40)
        case .tide:
            let shape = RoundedRectangle(cornerRadius: 40, style: .continuous)
            ZStack(alignment: .bottom) {
                shape.fill(colors.dusk.color(0.75))
                LinearGradient(colors: [colors.amber.color(0.0), colors.amber.color(0.35), colors.violet.color(0.55)],
                               startPoint: .top, endPoint: .bottom)
                    .frame(height: 70)
                    .clipShape(shape)
                shape.strokeBorder(colors.sun.color(0.55), lineWidth: 4)
                shape.inset(by: 6).strokeBorder(Color.white.opacity(0.14), lineWidth: 1)
            }
            .shadow(color: colors.violet.color(0.4), radius: 20, y: 10)
        }
    }

    private var expiredCover: some View {
        VStack(spacing: 10) {
            Text("Code expired").font(.system(size: 15, weight: .bold, design: .rounded)).foregroundStyle(Palette.night)
            Button("Show a new code", action: refresh).buttonStyle(SunsetButton())
        }
    }
}

/// A tick drawn into a circle that springs up: pairing worked.
private struct PairedCheck: View {
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().fill(Palette.ok)
                .frame(width: 110, height: 110)
                .shadow(color: Palette.ok.opacity(0.6), radius: shown ? 24 : 4)
                .scaleEffect(shown ? 1 : 0.3)
            Tick().trim(from: 0, to: shown ? 1 : 0)
                .stroke(Color.white, style: StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round))
                .frame(width: 52, height: 40)
        }
        .onAppear {
            if reduceMotion || Screenshots.active { shown = true; return }
            withAnimation(.spring(response: 0.45, dampingFraction: 0.6)) { shown = true }
        }
    }
}

private struct Tick: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.38, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        return path
    }
}
