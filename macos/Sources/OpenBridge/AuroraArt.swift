import SwiftUI
import BridgeCore

// Aurora's drawing for its shell: glowing orbs and stars, the constellation path, and the comet on the horizon.

/// A small glowing orb: a phone in the constellation switcher, or a star on the connect screen.
struct Orb: View {
    var size: CGFloat = 12
    /// 0 dim … 1 brightest.
    var brightness: Double = 1
    /// The open phone: a ring around it.
    var ringed = false
    var color: Color = Palette.amber

    var body: some View {
        let core: Double = 0.35 + 0.65 * brightness
        let glow: CGFloat = size * CGFloat(0.4 + 0.8 * brightness)
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [.white.opacity(core), color.opacity(core), color.opacity(0)],
                                     center: .center, startRadius: 0, endRadius: size / 2))
                .shadow(color: color.opacity(0.8 * brightness), radius: glow)
            if ringed {
                Circle().strokeBorder(color.opacity(0.9), lineWidth: 1.2)
                    .frame(width: size + 10, height: size + 10)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A slow twinkle: the opacity breathes between `low` and 1, each star at its own pace. Still with Reduce Motion.
struct Twinkle: ViewModifier {
    var seed: Int
    var low: Double = 0.55
    @State private var dim = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let period: Double = 1.6 + 1.8 * LookArt.hash(seed + 11)
        content
            .opacity(dim ? low : 1)
            .onAppear {
                guard !reduceMotion else { return }
                let delay: Double = LookArt.hash(seed + 23) * 1.5
                withAnimation(.easeInOut(duration: period).repeatForever(autoreverses: true).delay(delay)) { dim = true }
            }
    }
}

/// The folder trail as a constellation: names joined by fine lines with a small star at each joint. Click a star or
/// a name to go there. Deep paths keep the first and last two folders and fold the rest into "…".
struct ConstellationPath: View {
    @ObservedObject var model: BrowserModel

    var body: some View {
        let path = Array(model.path.enumerated())
        let hidden = path.count > 3 ? Array(path[1..<(path.count - 2)]) : []
        let shown = hidden.isEmpty ? path : [path[0]] + path.suffix(2)
        HStack(spacing: 0) {
            ForEach(shown, id: \.offset) { index, crumb in
                if index > 0 { link }
                if index == path.count - 2 && !hidden.isEmpty {
                    Menu {
                        ForEach(hidden, id: \.offset) { hiddenIndex, folder in Button(folder.name) { model.go(to: hiddenIndex) } }
                    } label: { Text("…").font(.system(size: 13, weight: .light)).foregroundStyle(Palette.muted) }
                        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help("Folders in between")
                        .accessibilityLabel("Folders in between")
                    link
                }
                StarCrumb(name: crumb.name, last: index == path.count - 1) { model.go(to: index) }
                    .layoutPriority(index == path.count - 1 ? 1 : 0)
            }
        }
    }

    /// The fine line between two stars.
    private var link: some View {
        LinearGradient(colors: [Palette.amber.opacity(0.15), Palette.amber.opacity(0.5), Palette.amber.opacity(0.15)],
                       startPoint: .leading, endPoint: .trailing)
            .frame(width: 22, height: 1)
            .padding(.horizontal, 4)
            .accessibilityHidden(true)
    }
}

private struct StarCrumb: View {
    let name: String
    let last: Bool
    let go: () -> Void

    var body: some View {
        Button(action: go) {
            HStack(spacing: 6) {
                Orb(size: last ? 7 : 5, brightness: last ? 1 : 0.5)
                Text(name)
                    .font(.system(size: 13, weight: last ? .regular : .light))
                    .tracking(0.6)
                    .foregroundStyle(last ? Color.white : Palette.muted)
                    .lineLimit(1).truncationMode(.middle)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(last ? name : "Go to \(name)")
    }
}

/// The horizon under Aurora's header: a thin line of light, and while files move, a comet travelling it from left to
/// right (its place is the overall progress) with a fading tail. Stopped by an error, it turns amber and waits.
struct CometLine: View {
    /// 0…1, or nil when nothing is moving.
    var progress: Double?
    var stopped = false
    var done = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let width: CGFloat = geometry.size.width
            let middle: CGFloat = geometry.size.height / 2
            ZStack(alignment: .topLeading) {
                line(width).offset(y: middle)
                if let progress {
                    comet(width: width, progress: progress).offset(y: middle)
                }
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.6), value: progress)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var tint: Color { stopped ? Color(red: 1, green: 0.72, blue: 0.3) : done ? Palette.ok : Palette.amber }

    private func line(_ width: CGFloat) -> some View {
        LinearGradient(colors: [Palette.amber.opacity(0), Palette.amber.opacity(0.35), Palette.violet.opacity(0.3), Palette.amber.opacity(0)],
                       startPoint: .leading, endPoint: .trailing)
            .frame(width: width, height: 1)
    }

    private func comet(width: CGFloat, progress: Double) -> some View {
        let x: CGFloat = width * CGFloat(min(max(progress, 0), 1))
        let tail: CGFloat = min(140, max(x, 1))
        return ZStack(alignment: .trailing) {
            // The tail fades out behind the head.
            LinearGradient(colors: [tint.opacity(0), tint.opacity(0.75)], startPoint: .leading, endPoint: .trailing)
                .frame(width: tail, height: 2)
                .offset(x: -5)
            Orb(size: 10, brightness: 1, color: tint)
        }
        .frame(width: tail + 5, height: 10, alignment: .trailing)
        .offset(x: x - tail - 5, y: -5)
    }
}
