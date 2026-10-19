import SwiftUI

/// How OpenBridge looks: its colours, the animated background and the hero. Chosen in Settings and remembered.
/// Dusk is the original; Aurora and Tide are drafts, shown as "Preview". The same looks as the Android and desktop
/// apps (shared/compose/org/openbridge/ui/Looks.kt), with the same colours and drawing.
enum Look: String, CaseIterable, Identifiable {
    /// Sunset glass: the sun travels the arch and turns gold.
    case dusk
    /// Northern lights: ribbons drift across a starry sky; the hero is a shimmering halo with a comet.
    case aurora
    /// Deep water: waves roll at the bottom; the hero is a glass pool that fills when a phone connects.
    case tide

    var id: String { rawValue }
    var label: String {
        switch self {
        case .dusk: return "Dusk"
        case .aurora: return "Aurora"
        case .tide: return "Tide"
        }
    }
    var draft: Bool { self != .dusk }
    var colors: LookColors {
        switch self {
        case .dusk: return LookColors.duskColors
        case .aurora: return LookColors.auroraColors
        case .tide: return LookColors.tideColors
        }
    }
}

/// A colour as red, green and blue from 0 to 1, so looks can mix colours (SwiftUI's Color can't be taken apart on macOS 13).
struct RGB: Equatable {
    let r: Double, g: Double, b: Double
    init(_ hex: UInt32) {
        r = Double(hex >> 16 & 255) / 255; g = Double(hex >> 8 & 255) / 255; b = Double(hex & 255) / 255
    }
    init(r: Double, g: Double, b: Double) { self.r = r; self.g = g; self.b = b }
    func color(_ alpha: Double = 1) -> Color { Color(.sRGB, red: r, green: g, blue: b, opacity: alpha) }
    static func mix(_ a: RGB, _ b: RGB, _ k: Double) -> RGB {
        RGB(r: a.r + (b.r - a.r) * k, g: a.g + (b.g - a.g) * k, b: a.b + (b.b - a.b) * k)
    }
}

/// A look's colours. The names come from Dusk; each look fills the same roles: night/dusk/plum the background from
/// darkest to lightest, violet/pink/amber the accents (amber is the main action), sun/cream highlights, ok success,
/// muted secondary text.
struct LookColors {
    let night, dusk, plum, violet, pink, amber, sun, cream, ok, muted, fg: RGB
    /// The accent gradient (buttons, progress, selection), left to right.
    let accent: [(Double, RGB)]
    /// The hero lit up: core, middle, edge, glow.
    let lit: [UInt32]

    /// Dusk's ring: sun, pink, violet, pink, sun around the circle.
    var ring: Gradient {
        Gradient(stops: [.init(color: sun.color(), location: 0), .init(color: pink.color(), location: 0.3), .init(color: violet.color(), location: 0.55),
                         .init(color: pink.color(), location: 0.8), .init(color: sun.color(), location: 1)])
    }

    static let duskColors = LookColors(
        night: RGB(0x0D0B26), dusk: RGB(0x1D1546), plum: RGB(0x3A2470),
        violet: RGB(0x6D5DFC), pink: RGB(0xFF5C8A), amber: RGB(0xFFB347),
        sun: RGB(0xFFD166), cream: RGB(0xFFF6E0), ok: RGB(0x3DDC97), muted: RGB(0xB4ABD6), fg: RGB(0xF4F0FF),
        accent: [(0, RGB(0x6D5DFC)), (0.55, RGB(0xFF5C8A)), (1, RGB(0xFFB347))],
        lit: [0xFFFDF0, 0xFFE08A, 0xFFB347, 0xFFC85A])

    static let auroraColors = LookColors(
        night: RGB(0x020A14), dusk: RGB(0x061A26), plum: RGB(0x0B2E3A),
        violet: RGB(0x2BB8FE), pink: RGB(0x9B7BFF), amber: RGB(0x3DF5B0),
        sun: RGB(0xB6FFE0), cream: RGB(0xE9FFF6), ok: RGB(0x5CFFB0), muted: RGB(0x94B4C0), fg: RGB(0xEEFBFF),
        accent: [(0, RGB(0x9B7BFF)), (0.5, RGB(0x2BB8FE)), (1, RGB(0x3DF5B0))],
        lit: [0xF4FFFB, 0xB6FFE0, 0x3DF5B0, 0x5CFFC0])

    static let tideColors = LookColors(
        night: RGB(0x020B1F), dusk: RGB(0x05214A), plum: RGB(0x0A3566),
        violet: RGB(0x2F7BFF), pink: RGB(0xFF7F6E), amber: RGB(0x3FE0D0),
        sun: RGB(0xA8F4FF), cream: RGB(0xE8FCFF), ok: RGB(0x4BE3A0), muted: RGB(0x93AECF), fg: RGB(0xEEF7FF),
        accent: [(0, RGB(0x2F7BFF)), (0.6, RGB(0x3FE0D0)), (1, RGB(0xA8F4FF))],
        lit: [0xF2FEFF, 0xB5F6FF, 0x3FE0D0, 0x5CE8F0])
}

/// The chosen look, remembered. Windows observe it and rebuild when it changes, so every `Palette` read follows.
final class LookStore: ObservableObject {
    static let shared = LookStore()
    @Published var look: Look {
        didSet {
            Palette.look = look
            UserDefaults.standard.set(look.rawValue, forKey: "look")
        }
    }
    private init() { look = Palette.look }
}

/// The current look's colours.
enum Palette {
    /// Read everywhere while drawing; changed only through `LookStore`, on the main thread.
    static var look: Look = Look(rawValue: UserDefaults.standard.string(forKey: "look") ?? "") ?? .dusk

    static var night: Color { look.colors.night.color() }
    static var dusk: Color { look.colors.dusk.color() }
    static var plum: Color { look.colors.plum.color() }
    static var violet: Color { look.colors.violet.color() }
    static var pink: Color { look.colors.pink.color() }
    static var amber: Color { look.colors.amber.color() }
    static var sunGold: Color { look.colors.sun.color() }
    static var cream: Color { look.colors.cream.color() }
    static var ok: Color { look.colors.ok.color() }
    static var muted: Color { look.colors.muted.color() }
    static var ring: Gradient { look.colors.ring }
    /// Buttons: the accent gradient.
    static var sunset: LinearGradient {
        LinearGradient(stops: look.colors.accent.map { .init(color: $0.1.color(), location: $0.0) }, startPoint: .leading, endPoint: .trailing)
    }
    /// The transfer bar: the accent gradient ending in the look's sun.
    static var bar: LinearGradient {
        let stops = look.colors.accent.map { Gradient.Stop(color: $0.1.color(), location: $0.0 * 0.85) }
        return LinearGradient(stops: stops + [.init(color: sunGold, location: 1)], startPoint: .leading, endPoint: .trailing)
    }
    /// The still background (Dusk's sky, and behind sheets in every look).
    static var background: RadialGradient {
        RadialGradient(stops: [.init(color: plum, location: 0), .init(color: dusk, location: 0.5), .init(color: night, location: 1)],
                       center: UnitPoint(x: 0.65, y: -0.1), startRadius: 0, endRadius: 900)
    }

    struct Sun { let core: Color, middle: Color, edge: Color, glow: Color }
    /// Grey glass when nothing is connected, the look's light when connected.
    static func sun(warmth k: Double, colors: LookColors = Palette.look.colors) -> Sun {
        let lit = colors.lit
        func mix(_ a: UInt32, _ b: UInt32) -> RGB { RGB.mix(RGB(a), RGB(b), k) }
        // Grey is moonlight: bright silver-lavender glass with a soft white glow, never a dark rim.
        return Sun(core: mix(0xFFFFFF, lit[0]).color(), middle: mix(0xE6E3F2, lit[1]).color(), edge: mix(0xB4B0CC, lit[2]).color(),
                   glow: mix(0xF0EEFF, lit[3]).color(0.3 + 0.65 * k))
    }
}

/// The current look's background: Dusk's still sky, or Aurora's and Tide's moving pictures (about 30 frames a second,
/// still with Reduce Motion). `energy` (0 to 1) makes them livelier while connected; `level` (0 to 1) raises Tide's
/// water, following transfer progress.
struct Backdrop: View {
    var energy: Double = 0
    var level: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var clock = BackdropClock()

    var body: some View {
        let look = Palette.look
        if look == .dusk {
            Rectangle().fill(Palette.background)
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
                Canvas { context, size in
                    let now = clock.step(timeline.date, energy: energy, level: level, still: reduceMotion)
                    LookArt.backdrop(look, &context, size: size, time: now.time, energy: now.energy, level: now.level)
                }
            }
        }
    }
}

/// Time, and energy and level eased towards their targets (energy over 1.4 s, the water slowly), between frames.
final class BackdropClock {
    private var last: Date?
    private var time = 7.0
    private var energy: Double?
    private var level: Double?

    func step(_ now: Date, energy target: Double, level goal: Double, still: Bool) -> (time: Double, energy: Double, level: Double) {
        let dt = last.map { min(max(now.timeIntervalSince($0), 0), 0.1) } ?? 0
        last = now
        if !still { time += dt }
        var e = energy ?? target, l = level ?? goal
        if still { e = target; l = goal } else {
            e = e < target ? min(target, e + dt / 1.4) : max(target, e - dt / 1.4)
            l += (goal - l) * min(1, dt * 1.5)
        }
        energy = e; level = l
        return (time, e, l)
    }
}

/// A live miniature of a look, for choosing one: its background and its hero, lit.
struct LookPreview: View {
    let look: Look
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
            Canvas { context, size in
                let t = reduceMotion ? 7 : 7 + timeline.date.timeIntervalSince(start)
                LookArt.backdrop(look, &context, size: size, time: t, energy: 1, level: 0.4)
                LookArt.hero(look, &context, engine: SunEngine(restingWarmth: 1), frame: SunEngine.Frame(glow: 0.2),
                             center: CGPoint(x: size.width / 2, y: size.height * 0.45), radius: min(size.width, size.height) * 0.24,
                             reach: 0, time: t)
            }
        }
        .accessibilityHidden(true)
    }
}

func circle(_ c: CGPoint, _ r: Double) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)) }

/// The looks' drawing, ported from Looks.kt with the same math. Lengths the Kotlin gives in dp are points here; its
/// raw pixel widths (drawn at density 2 in the reference pictures) are halved.
enum LookArt {
    private static let tau = 2 * Double.pi

    /// Stable pseudo-random 0..1 for star and bubble placement (the same numbers as on Android).
    static func hash(_ i: Int) -> Double {
        let x = sin(Double(i) * 12.9898 + 78.233) * 43758.5453
        return x - x.rounded(.down)
    }

    private static func vertical(_ stops: [Gradient.Stop], from y0: Double, to y1: Double) -> GraphicsContext.Shading {
        .linearGradient(Gradient(stops: stops), startPoint: CGPoint(x: 0, y: y0), endPoint: CGPoint(x: 0, y: y1))
    }

    static func backdrop(_ look: Look, _ g: inout GraphicsContext, size: CGSize, time t: Double, energy: Double, level: Double) {
        guard size.width > 0, size.height > 0 else { return }
        switch look {
        case .dusk:
            let c = look.colors
            g.fill(Path(CGRect(origin: .zero, size: size)), with: .radialGradient(
                Gradient(stops: [.init(color: c.plum.color(), location: 0), .init(color: c.dusk.color(), location: 0.5), .init(color: c.night.color(), location: 1)]),
                center: CGPoint(x: size.width * 0.65, y: -size.height * 0.1), startRadius: 0, endRadius: max(size.width, size.height) * 0.95))
        case .aurora: aurora(&g, size, t: t, energy: energy)
        case .tide: tide(&g, size, t: t, energy: energy, level: level)
        }
    }

    /// The hero in `look`, driven by the same engine everywhere. `reach`: how far the connect ripples spread.
    static func hero(_ look: Look, _ g: inout GraphicsContext, engine: SunEngine, frame: SunEngine.Frame, center: CGPoint,
                     radius: Double, reach: Double, time: Double) {
        switch look {
        case .dusk: sunArch(&g, look.colors, engine: engine, frame: frame, center: center, radius: radius, reach: reach)
        case .aurora: halo(&g, engine: engine, frame: frame, center: center, radius: radius, reach: reach, time: time)
        case .tide: pool(&g, engine: engine, frame: frame, center: center, radius: radius, reach: reach, time: time)
        }
    }

    // MARK: Aurora

    private static func aurora(_ g: inout GraphicsContext, _ size: CGSize, t: Double, energy: Double) {
        let w = size.width, h = size.height, unit = min(w, h)
        g.fill(Path(CGRect(origin: .zero, size: size)), with: vertical([
            .init(color: RGB(0x020A14).color(), location: 0), .init(color: RGB(0x051B26).color(), location: 0.55),
            .init(color: RGB(0x06121D).color(), location: 1)], from: 0, to: h))
        // Stars, twinkling, thinning out towards the horizon.
        for i in 0..<90 {
            let x = hash(i * 3 + 1) * w, y = hash(i * 3 + 2) * h * 0.8
            let twinkle = 0.5 + 0.5 * sin(t * (0.5 + hash(i + 300) * 1.6) + Double(i))
            let fade = 1 - y / (h * 0.8) * 0.6
            g.fill(circle(CGPoint(x: x, y: y), 0.5 + hash(i + 700) * 1.1),
                   with: .color(.white.opacity((0.06 + 0.34 * twinkle * hash(i + 500)) * fade)))
        }
        let speed = 1 + energy * 0.6, bright = 0.6 + 0.4 * energy
        var light = g
        light.blendMode = .plusLighter
        // Three curtains high in the sky: a bright lower edge, and light rising from it in soft folds.
        let ribbons: [(RGB, Double, Double)] = [(RGB(0x3DF5B0), 0.17, 0), (RGB(0x2BD9FE), 0.25, 2.1), (RGB(0xB07BFF), 0.11, 4.2)]
        for (i, (color, base, phase)) in ribbons.enumerated() {
            let amplitude = h * 0.035 + unit * 0.02
            func edge(_ x: Double) -> Double {
                let u: Double = x / w
                let slow: Double = sin(u * tau * 0.8 + t * 0.22 * speed + phase)
                let quick: Double = sin(u * tau * 2.3 - t * 0.37 * speed + phase * 1.7)
                let lift: Double = amplitude * slow + amplitude * 0.45 * quick
                return h * base + lift
            }
            func height(_ x: Double) -> Double {
                let wave: Double = sin(x / w * tau * 1.4 + t * 0.3 + phase)
                let scale: Double = 0.8 + 0.2 * energy
                return h * (0.13 + 0.06 * wave) * scale
            }
            // Rays side by side, each fading upwards on its own, so the curtain has no hard top.
            let rays = 96
            for s in 0..<rays {
                let x = w * (Double(s) + 0.5) / Double(rays)
                let angle: Double = x / w * tau * 4.5 + t * 0.55 * speed + phase + Double(i)
                let wave: Double = 0.5 + 0.5 * sin(angle)
                let fold: Double = 0.35 + 0.65 * wave
                let y = edge(x), top = y - height(x) * (0.55 + 0.45 * fold)
                var ray = Path()
                ray.move(to: CGPoint(x: x, y: top)); ray.addLine(to: CGPoint(x: x, y: y))
                light.stroke(ray, with: vertical([.init(color: color.color(0), location: 0), .init(color: color.color(0.10 * fold * bright), location: 0.7),
                                                  .init(color: color.color(0.26 * fold * bright), location: 1)], from: top, to: y),
                             lineWidth: w / Double(rays) + 0.25)
            }
            var line = Path()
            for s in 0...72 {
                let x = w * Double(s) / 72
                if s == 0 { line.move(to: CGPoint(x: x, y: edge(x))) } else { line.addLine(to: CGPoint(x: x, y: edge(x))) }
            }
            light.stroke(line, with: .color(color.color(0.08 * bright)), style: StrokeStyle(lineWidth: unit * 0.035, lineCap: .round))
            light.stroke(line, with: .color(color.color(0.45 * bright)), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
        }
        // A low glow on the horizon.
        g.fill(Path(CGRect(origin: .zero, size: size)), with: vertical([
            .init(color: .clear, location: 0.7), .init(color: RGB(0x3DF5B0).color(0.06 + 0.05 * energy), location: 1)], from: 0, to: h))
    }

    /// Aurora's hero: three shimmering ribbons in a ring, and a comet that runs the ring like Dusk's sun.
    private static func halo(_ g: inout GraphicsContext, engine e: SunEngine, frame f: SunEngine.Frame, center c: CGPoint,
                             radius r: Double, reach: Double, time t: Double) {
        let colors = LookColors.auroraColors
        let k = e.warmth
        let head = Palette.sun(warmth: k, colors: colors)
        func point(_ a: Double) -> CGPoint {
            let angle = (a - 90) * .pi / 180
            return CGPoint(x: c.x + r * cos(angle), y: c.y + r * sin(angle))
        }
        let silver = RGB(0xC9D6E3), amber = colors.amber
        g.fill(circle(c, r * 1.7), with: .radialGradient(Gradient(stops: [.init(color: amber.color(0.05 + 0.16 * k), location: 0),
                                                                         .init(color: .clear, location: 1)]),
                                                        center: c, startRadius: 0, endRadius: r * 1.7))
        if f.pulse > 0 {
            g.fill(circle(c, r * 2.1), with: .radialGradient(Gradient(stops: [
                .init(color: .clear, location: 0), .init(color: .clear, location: 0.45),
                .init(color: amber.color(0.16 * f.pulse), location: 0.7), .init(color: .clear, location: 1)]),
                center: c, startRadius: 0, endRadius: r * 2.1))
        }
        var light = g
        light.blendMode = .plusLighter
        for (i, base) in [amber, RGB(0x2BD9FE), RGB(0xB07BFF)].enumerated() {
            let color = RGB.mix(silver, base, 0.35 + 0.65 * k)
            let n = Double(i)
            var ring = Path()
            let steps = 160
            for s in 0...steps {
                let theta = Double(s) / Double(steps) * tau
                let strength: Double = r * 0.03 * (0.3 + f.glow + 0.4 * k)
                let big: Double = sin(5 * theta + t * (1.1 + 0.35 * n) + n * 2.1)
                let small: Double = sin(9 * theta - t * 1.7 + n)
                let wobble: Double = strength * big + r * 0.012 * small
                let radius: Double = r + wobble + (n - 1) * r * 0.03
                let q = CGPoint(x: c.x + radius * cos(theta), y: c.y + radius * sin(theta))
                if s == 0 { ring.move(to: q) } else { ring.addLine(to: q) }
            }
            ring.closeSubpath()
            light.stroke(ring, with: .color(color.color(0.07 + 0.05 * k)), lineWidth: r * 0.24)
            light.stroke(ring, with: .color(color.color(0.45 + 0.4 * k)), lineWidth: r * (0.075 - 0.015 * n))
        }
        // The comet's tail along the ring.
        if let first = e.trail.first, e.trail.count > 1 {
            for i in 0..<48 {
                let q = Double(i) / 48
                light.fill(circle(point(first + (e.angle - first) * q), r * 0.13 * (0.3 + 0.7 * q)), with: .color(head.edge.opacity(0.03 + 0.2 * q * q)))
            }
        }
        if f.bloom > 0 && f.bloom < 1 {
            let top = point(e.angle)
            for (delay, weight) in [(0.0, 1.0), (0.16, 0.65), (0.32, 0.4)] {
                let b = max(0, min(1, (f.bloom - delay) / (1 - delay)))
                guard b > 0 && b < 1 else { continue }
                light.stroke(circle(top, r * 0.16 + easeInOut(b) * reach), with: .color(amber.color((1 - b) * (1 - b) * 0.75 * weight)),
                             lineWidth: 3 * (1 - b) + 0.75)
            }
        }
        // The comet: a bright star with a slow four-point flare.
        let p = point(e.angle)
        let size = r * 0.17 * (1 + e.jelly * 0.6)
        let glow = size * (2.4 + f.glow + f.pulse)
        g.fill(circle(p, glow), with: .radialGradient(Gradient(stops: [.init(color: head.glow, location: 0), .init(color: .clear, location: 1)]),
                                                     center: p, startRadius: 0, endRadius: glow))
        g.fill(circle(p, size), with: .radialGradient(Gradient(stops: [
            .init(color: head.core, location: 0), .init(color: head.middle, location: 0.5), .init(color: head.edge, location: 0.9),
            .init(color: head.edge.opacity(0), location: 1)]), center: p, startRadius: 0, endRadius: size))
        for a in 0..<2 {
            var flare = g
            flare.translateBy(x: p.x, y: p.y)
            flare.rotate(by: .degrees(t * 12 + Double(a) * 90))
            var beam = Path()
            beam.move(to: CGPoint(x: -size * 2.6, y: 0)); beam.addLine(to: CGPoint(x: size * 2.6, y: 0))
            flare.stroke(beam, with: .linearGradient(Gradient(stops: [.init(color: .clear, location: 0), .init(color: .white.opacity(0.55 + 0.3 * k), location: 0.5),
                                                                      .init(color: .clear, location: 1)]),
                                                     startPoint: CGPoint(x: -size * 2.6, y: 0), endPoint: CGPoint(x: size * 2.6, y: 0)),
                         lineWidth: size * 0.12)
        }
    }

    // MARK: Tide

    private static func tide(_ g: inout GraphicsContext, _ size: CGSize, t: Double, energy: Double, level: Double) {
        let w = size.width, h = size.height
        g.fill(Path(CGRect(origin: .zero, size: size)), with: vertical([
            .init(color: RGB(0x0A3A6B).color(), location: 0), .init(color: RGB(0x05214A).color(), location: 0.45),
            .init(color: RGB(0x020B1F).color(), location: 1)], from: 0, to: h))
        // Light from the surface: slow, swaying beams.
        var light = g
        light.blendMode = .plusLighter
        for i in 0..<4 {
            let n = Double(i)
            let sway: Double = sin(t * 0.17 + n * 1.3) * w * 0.05
            let x0: Double = w * (0.12 + 0.26 * n) + sway
            let spread = w * 0.09
            var beam = Path()
            beam.move(to: CGPoint(x: x0 - spread * 0.25, y: 0)); beam.addLine(to: CGPoint(x: x0 + spread * 0.25, y: 0))
            let right: Double = x0 + spread + w * 0.08, left: Double = x0 - spread * 0.4 + w * 0.08
            beam.addLine(to: CGPoint(x: right, y: h * 0.75)); beam.addLine(to: CGPoint(x: left, y: h * 0.75))
            beam.closeSubpath()
            let shimmer = 0.6 + 0.4 * sin(t * 0.45 + n * 2)
            light.fill(beam, with: vertical([.init(color: .white.opacity(0.07 * shimmer), location: 0), .init(color: .clear, location: 1)], from: 0, to: h * 0.75))
        }
        let speed = 1 + energy * 0.5
        let surface = h * (0.80 - 0.24 * min(max(level, 0), 1))
        // Bubbles rising, behind the waves.
        for i in 0..<24 {
            let rise = 0.035 + hash(i + 40) * 0.05
            let p = (t * rise * speed + hash(i + 80)).truncatingRemainder(dividingBy: 1)
            let y = h * (1.05 - p * 1.1)
            let x = hash(i + 120) * w + sin(t * 1.1 + Double(i)) * 6
            g.stroke(circle(CGPoint(x: x, y: y), 1.5 + hash(i + 160) * 3.5),
                     with: .color(.white.opacity(0.28 * (1 - p) * (0.4 + 0.6 * hash(i + 200)))), lineWidth: 0.9)
        }
        for i in 0..<3 {
            let n = Double(i)
            let amplitude = 5 + 3.5 * n + 4 * energy
            let base = surface + n * h * 0.05
            func y(_ x: Double) -> Double {
                let u: Double = x / w
                let long: Double = sin(u * tau * (1.05 + 0.35 * n) + t * (0.65 + 0.22 * n) * speed + n * 1.7)
                let short: Double = sin(u * tau * (3.1 + n) - t * 1.05 * speed + n)
                return base + amplitude * long + amplitude * 0.35 * short
            }
            var wave = Path(), crest = Path()
            let steps = 60
            for s in 0...steps {
                let x = w * Double(s) / Double(steps), q = CGPoint(x: x, y: y(x))
                if s == 0 { wave.move(to: q); crest.move(to: q) } else { wave.addLine(to: q); crest.addLine(to: q) }
            }
            wave.addLine(to: CGPoint(x: w, y: h)); wave.addLine(to: CGPoint(x: 0, y: h)); wave.closeSubpath()
            g.fill(wave, with: vertical([.init(color: RGB(0x3FE0D0).color(0.13 + 0.05 * n), location: 0),
                                         .init(color: RGB(0x0E4C8A).color(0.45), location: 0.35),
                                         .init(color: RGB(0x031634).color(0.9), location: 1)], from: base - amplitude, to: h))
            g.stroke(crest, with: .color(RGB(0xE8FCFF).color(0.10 + 0.07 * n)), style: StrokeStyle(lineWidth: 1 + 0.4 * n, lineCap: .round))
        }
    }

    /// Tide's hero: a glass pool that fills with water as a phone connects, and a droplet that runs its rim.
    private static func pool(_ g: inout GraphicsContext, engine e: SunEngine, frame f: SunEngine.Frame, center c: CGPoint,
                             radius r: Double, reach: Double, time t: Double) {
        let colors = LookColors.tideColors
        let k = e.warmth
        let drop = Palette.sun(warmth: k, colors: colors)
        func point(_ a: Double) -> CGPoint {
            let angle = (a - 90) * .pi / 180
            return CGPoint(x: c.x + r * cos(angle), y: c.y + r * sin(angle))
        }
        let amber = colors.amber
        g.fill(circle(c, r * 1.75), with: .radialGradient(Gradient(stops: [.init(color: amber.color(0.06 + 0.18 * k + 0.1 * f.glow), location: 0),
                                                                          .init(color: .clear, location: 1)]),
                                                         center: c, startRadius: 0, endRadius: r * 1.75))
        if f.pulse > 0 {
            g.fill(circle(c, r * 2.1), with: .radialGradient(Gradient(stops: [
                .init(color: .clear, location: 0), .init(color: .clear, location: 0.45),
                .init(color: amber.color(0.15 * f.pulse), location: 0.7), .init(color: .clear, location: 1)]),
                center: c, startRadius: 0, endRadius: r * 2.1))
        }
        let inner = r * 0.94
        g.fill(circle(c, inner), with: vertical([.init(color: RGB(0x0D3260).color(0.7), location: 0),
                                                 .init(color: RGB(0x041530).color(0.85), location: 1)], from: c.y - inner, to: c.y + inner))
        // Water: rises from a puddle to nearly full when connected, and sloshes with the droplet's speed.
        let fill = 0.16 + 0.64 * k
        let surface = c.y + inner - 2 * inner * fill
        let tilt = min(max(e.velocity / 700, -1), 1) * inner * 0.18 + e.jelly * inner * 0.4
        let amplitude = inner * (0.03 + 0.05 * f.glow + 0.02 * k)
        var water = g
        water.clip(to: circle(c, inner))
        for layer in [1, 0] {
            let l = Double(layer)
            var wave = Path()
            let steps = 48
            for s in 0...steps {
                let u = Double(s) / Double(steps)
                let swell: Double = sin(u * tau * 1.2 + t * (1.7 + 0.6 * l) + l * 2.2)
                let ripple: Double = sin(u * tau * 2.7 - t * 2.3)
                let level: Double = surface + l * inner * 0.07 + tilt * (u * 2 - 1)
                let q = CGPoint(x: c.x - inner + 2 * inner * u, y: level + amplitude * swell + amplitude * 0.4 * ripple)
                if s == 0 { wave.move(to: q) } else { wave.addLine(to: q) }
            }
            wave.addLine(to: CGPoint(x: c.x + inner, y: c.y + inner)); wave.addLine(to: CGPoint(x: c.x - inner, y: c.y + inner)); wave.closeSubpath()
            let top = RGB.mix(RGB(0x8FA6C4), amber, 0.3 + 0.7 * k)
            water.fill(wave, with: vertical([.init(color: top.color(layer == 0 ? 0.85 : 0.4), location: 0),
                                             .init(color: RGB(0x1667B8).color(0.75), location: 0.5),
                                             .init(color: RGB(0x07285A).color(), location: 1)], from: surface - amplitude, to: c.y + inner))
            if layer == 0 { water.stroke(wave, with: .color(.white.opacity(0.22 + 0.2 * k)), lineWidth: 1.4) }
        }
        for i in 0..<7 {
            let p = (t * (0.12 + hash(i + 9) * 0.1) + hash(i + 19)).truncatingRemainder(dividingBy: 1)
            let y = c.y + inner - p * (c.y + inner - surface)
            let drift: Double = sin(t * 2 + Double(i)) * inner * 0.03
            let x: Double = c.x - inner * 0.7 + hash(i + 29) * inner * 1.4 + drift
            water.stroke(circle(CGPoint(x: x, y: y), inner * (0.02 + hash(i + 39) * 0.025)), with: .color(.white.opacity(0.45 * (1 - p))), lineWidth: 1)
        }
        // The glass rim and its shine.
        let rim = Gradient(stops: [.init(color: colors.sun.color(), location: 0), .init(color: amber.color(), location: 0.3),
                                   .init(color: colors.violet.color(), location: 0.55), .init(color: amber.color(), location: 0.8),
                                   .init(color: colors.sun.color(), location: 1)])
        g.stroke(circle(c, r), with: .conicGradient(rim, center: c, angle: .degrees(t * 8)), lineWidth: r * (0.07 + 0.03 * k))
        var shine = Path()
        for s in 0...24 {
            let a = (200 + 70 * Double(s) / 24) * .pi / 180
            let q = CGPoint(x: c.x + r * 0.82 * cos(a), y: c.y + r * 0.82 * sin(a))
            if s == 0 { shine.move(to: q) } else { shine.addLine(to: q) }
        }
        g.stroke(shine, with: vertical([.init(color: .white.opacity(0.5), location: 0), .init(color: .clear, location: 1)], from: c.y - r, to: c.y),
                 style: StrokeStyle(lineWidth: r * 0.05, lineCap: .round))
        // The droplet's tail and the droplet.
        var light = g
        light.blendMode = .plusLighter
        if let first = e.trail.first, e.trail.count > 1 {
            for i in 0..<40 {
                let q = Double(i) / 40
                light.fill(circle(point(first + (e.angle - first) * q), r * 0.14 * (0.3 + 0.7 * q)), with: .color(drop.edge.opacity(0.04 + 0.18 * q * q)))
            }
        }
        if f.bloom > 0 && f.bloom < 1 {
            let top = point(e.angle)
            for (delay, weight) in [(0.0, 1.0), (0.16, 0.65), (0.32, 0.4)] {
                let b = max(0, min(1, (f.bloom - delay) / (1 - delay)))
                guard b > 0 && b < 1 else { continue }
                light.stroke(circle(top, r * 0.16 + easeInOut(b) * reach), with: .color(colors.sun.color((1 - b) * (1 - b) * 0.7 * weight)),
                             lineWidth: 2.5 * (1 - b) + 0.75)
            }
        }
        let p = point(e.angle)
        let size = r * 0.16
        let speed = min(abs(e.velocity) / 520, 1)
        let glow = size * (2.2 + f.glow + f.pulse)
        g.fill(circle(p, glow), with: .radialGradient(Gradient(stops: [.init(color: drop.glow, location: 0), .init(color: .clear, location: 1)]),
                                                     center: p, startRadius: 0, endRadius: glow))
        var body = g
        body.translateBy(x: p.x, y: p.y)
        body.rotate(by: .degrees(e.angle))
        let stretch = 1 + speed * 0.8
        let half = size * (1 + e.jelly) / stretch.squareRoot()
        body.fill(Path(ellipseIn: CGRect(x: -size * stretch, y: -half, width: 2 * size * stretch, height: 2 * half)),
                  with: .radialGradient(Gradient(stops: [.init(color: drop.core, location: 0), .init(color: drop.middle, location: 0.5),
                                                         .init(color: drop.edge, location: 0.9), .init(color: drop.edge.opacity(0), location: 1)]),
                                        center: CGPoint(x: -size * 0.2, y: -size * 0.25), startRadius: 0, endRadius: size * 1.3))
        body.fill(circle(CGPoint(x: -size * 0.35, y: -size * 0.35), size * 0.25), with: .color(.white.opacity(0.85)))
    }
}
