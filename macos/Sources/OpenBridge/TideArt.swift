import SwiftUI

// Tide's drawing for its shell: portholes, the wave line, depth gauges, bubbles, and the gentle bob of things afloat.

/// A round glass porthole: a mini pool, full of water when its phone is connected and empty when it's saved or
/// nearby, with a bright rim on the open one and a cable badge for USB.
struct Porthole: View {
    var size: CGFloat = 48
    var filled = false
    var bright = false
    var cable = false
    /// A symbol inside instead of water (Texts, Settings, Mirror).
    var symbol: String?

    var body: some View {
        let colors = Palette.look.colors
        ZStack {
            Circle().fill(LinearGradient(colors: [RGB(0x0D3260).color(0.75), RGB(0x041530).color(0.9)],
                                         startPoint: .top, endPoint: .bottom))
            if filled {
                WaterFill(level: 0.62)
                    .fill(LinearGradient(colors: [colors.amber.color(0.85), RGB(0x1667B8).color(0.8), RGB(0x07285A).color()],
                                         startPoint: .top, endPoint: .bottom))
                    .clipShape(Circle())
                    .padding(size * 0.06)
            }
            if let symbol {
                Image(systemName: symbol).font(.system(size: size * 0.36, weight: .medium)).foregroundStyle(colors.cream.color(0.9))
            }
            Circle().strokeBorder(rim, lineWidth: bright ? size * 0.07 : size * 0.04)
            Circle().trim(from: 0.58, to: 0.78).stroke(Color.white.opacity(0.45), style: StrokeStyle(lineWidth: size * 0.04, lineCap: .round))
                .padding(size * 0.14)
        }
        .frame(width: size, height: size)
        .shadow(color: colors.amber.color(bright ? 0.55 : 0), radius: bright ? 8 : 0)
        .overlay(alignment: .bottomTrailing) {
            if cable {
                Image(systemName: "cable.connector")
                    .font(.system(size: size * 0.2, weight: .bold))
                    .foregroundStyle(colors.night.color())
                    .frame(width: size * 0.38, height: size * 0.38)
                    .background(colors.sun.color(), in: Circle())
                    .offset(x: size * 0.06, y: size * 0.06)
            }
        }
        .accessibilityHidden(true)
    }

    private var rim: LinearGradient {
        let colors = Palette.look.colors
        let top: Double = bright ? 1 : 0.45
        return LinearGradient(colors: [colors.sun.color(top), colors.amber.color(top * 0.8), colors.violet.color(top * 0.7)],
                              startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Water up to `level` (0 empty … 1 full) with a softly curved surface.
struct WaterFill: Shape {
    var level: Double

    func path(in rect: CGRect) -> Path {
        let surface: CGFloat = rect.maxY - rect.height * CGFloat(level)
        let swell: CGFloat = rect.height * 0.05
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: surface))
        path.addCurve(to: CGPoint(x: rect.maxX, y: surface),
                      control1: CGPoint(x: rect.minX + rect.width * 0.35, y: surface - swell),
                      control2: CGPoint(x: rect.minX + rect.width * 0.65, y: surface + swell))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// A gentle animated wave, the lower edge of Tide's surface bar. Still with Reduce Motion.
struct WaveLine: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let paused: Bool = reduceMotion || scenePhase == .background
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: paused)) { timeline in
            Canvas { context, size in
                let seconds: Double = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600)
                let t: Double = reduceMotion ? 0 : seconds
                WaveLine.draw(&context, size: size, time: t)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    static func draw(_ context: inout GraphicsContext, size: CGSize, time t: Double) {
        let w: Double = Double(size.width)
        let middle: Double = Double(size.height) / 2
        let amplitude: Double = Double(size.height) * 0.3
        var line = Path()
        let steps = 80
        for s in 0...steps {
            let x: Double = w * Double(s) / Double(steps)
            let long: Double = sin(x / 90 + t * 1.1)
            let short: Double = sin(x / 37 - t * 1.7)
            let y: Double = middle + amplitude * long + amplitude * 0.35 * short
            if s == 0 { line.move(to: CGPoint(x: x, y: y)) } else { line.addLine(to: CGPoint(x: x, y: y)) }
        }
        let colors = Palette.look.colors
        let shading = GraphicsContext.Shading.linearGradient(
            Gradient(colors: [colors.amber.color(0.15), colors.sun.color(0.6), colors.amber.color(0.15)]),
            startPoint: .zero, endPoint: CGPoint(x: w, y: 0))
        context.stroke(line, with: shading, style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
    }
}

/// How deep a folder is next to the biggest one here: a small bar that fills like water.
struct DepthGauge: View {
    /// 0…1, or nil while the folder isn't measured yet.
    var fraction: Double?

    var body: some View {
        let colors = Palette.look.colors
        let part: CGFloat = CGFloat(min(max(fraction ?? 0, 0), 1))
        ZStack(alignment: .leading) {
            Capsule().fill(Color.white.opacity(0.08))
            Capsule().fill(LinearGradient(colors: [colors.violet.color(), colors.amber.color()], startPoint: .leading, endPoint: .trailing))
                .frame(width: max(part * 48, fraction == nil ? 0 : 4))
        }
        .frame(width: 48, height: 5)
        .accessibilityHidden(true)
    }
}

/// Floats: a slow vertical bob of a pixel or two, each item at its own pace. Still with Reduce Motion.
struct Bob: ViewModifier {
    var seed: Int = 0
    var amplitude: CGFloat = 1.5
    @State private var up = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let period: Double = 1.8 + 1.4 * LookArt.hash(seed + 31)
        content
            .offset(y: up ? -amplitude : amplitude)
            .onAppear {
                guard !reduceMotion else { return }
                let delay: Double = LookArt.hash(seed + 47)
                withAnimation(.easeInOut(duration: period).repeatForever(autoreverses: true).delay(delay)) { up = true }
            }
    }
}

/// Rises into place with a springy overshoot when it appears (grid cards, bubbles).
struct RiseIn: ViewModifier {
    var seed: Int = 0
    var depth: CGFloat = 10
    @State private var risen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .offset(y: risen ? 0 : depth)
            .opacity(risen ? 1 : 0)
            .onAppear {
                if reduceMotion { risen = true; return }
                let delay: Double = 0.04 * Double(seed % 8)
                withAnimation(.spring(response: 0.5, dampingFraction: 0.55).delay(delay)) { risen = true }
            }
    }
}

/// A glass bubble with a phone's name inside.
struct Bubble: View {
    let name: String
    var size: CGFloat = 86
    var bright = false

    var body: some View {
        let colors = Palette.look.colors
        ZStack {
            Circle().fill(RadialGradient(colors: [colors.sun.color(bright ? 0.22 : 0.10), colors.amber.color(0.06), RGB(0x041530).color(0.35)],
                                         center: UnitPoint(x: 0.35, y: 0.3), startRadius: 0, endRadius: size * 0.6))
            Circle().strokeBorder(LinearGradient(colors: [colors.cream.color(bright ? 0.8 : 0.45), colors.amber.color(0.25)],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: bright ? 1.6 : 1)
            Circle().trim(from: 0.6, to: 0.72).stroke(Color.white.opacity(0.6), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .padding(size * 0.12)
            Text(name)
                .font(.system(size: 11.5, weight: bright ? .semibold : .regular))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
                .padding(size * 0.16)
        }
        .frame(width: size, height: size)
        .shadow(color: colors.amber.color(bright ? 0.35 : 0.1), radius: 10)
    }
}
