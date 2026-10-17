import SwiftUI

/// The sun's motion around the arch. The sun chases a moving target on a spring, so the lag, overshoot
/// and wobble come from the physics. The same values drive the Android app's sun.
final class SunEngine {
    enum State { case idle, scan, spin, done }
    private(set) var state: State
    private(set) var angle = 0.0          // degrees, 0 = top, clockwise
    private(set) var velocity = 0.0       // degrees per second
    private(set) var jelly = 0.0
    private(set) var warmth = 0.0         // 0 grey, 1 gold
    private(set) var trail: [Double] = []
    private var jellyVelocity = 0.0, previousVelocity = 0.0
    private var clock = 0.0, start = 0.0, from = 0.0, to = 0.0, windup = false, settledAt = 0.0
    private var lastFrame: Date?

    /// Seconds since the previous frame, capped so a paused window doesn't jump.
    func elapsed(until now: Date) -> Double {
        defer { lastFrame = now }
        return min(lastFrame.map { now.timeIntervalSince($0) } ?? 0, 1.0 / 30)
    }

    init(state: State = .idle) { self.state = state }

    struct Frame { var glow = 0.0, bloom = 0.0, pulse = 0.0 }

    /// `active`: sharing, or looking for a phone. `connected`: a phone or Mac is paired or plugged in.
    func step(_ dt: Double, active: Bool, connected: Bool, reduceMotion: Bool) -> Frame {
        clock += dt
        if !active { if state != .idle { normalize(); state = .idle } }
        else if state == .idle { spin(windup: true) }
        else if state == .scan && connected { spin(windup: false) }
        else if state == .done && !connected { scan() }
        if reduceMotion && state == .spin { finish(connected) }

        var frame = Frame()
        if state == .spin || state == .scan {
            let t = clock - start, steps = 8, h = dt / Double(steps)
            for _ in 0..<steps {
                let acceleration = 210 * (target(t) - angle) - 14.5 * velocity
                velocity += acceleration * h; angle += velocity * h
            }
            let acceleration = (velocity - previousVelocity) / max(dt, 1e-4); previousVelocity = velocity
            jellyVelocity += (-320 * jelly - 16 * jellyVelocity - acceleration * 0.00055) * dt
            jelly = min(0.35, max(-0.35, jelly + jellyVelocity * dt))
            trail.append(angle)
            let length = Int(6 + min(abs(velocity) / 18, 34))
            if trail.count > length { trail.removeFirst(trail.count - length) }
            frame.glow = min(abs(velocity) / 700, 1) * (state == .scan ? 0.4 : 1)
            if state == .spin && t > (windup ? 0.2 : 0) + 1.1 && abs(velocity) < 6 && abs(to - angle) < 0.6 { finish(connected) }
        } else {
            if !trail.isEmpty { trail.removeFirst() }
            jellyVelocity += (-320 * jelly - 16 * jellyVelocity) * dt; jelly += jellyVelocity * dt
            velocity *= 0.8
        }
        let goal = state == .done ? 1.0 : state == .spin ? 0.4 : 0
        warmth += (goal - warmth) * min(1, dt * (state == .done ? 7 : 3))
        if state == .done {
            let since = clock - settledAt
            frame.bloom = min(since / 1.3, 1)
            // Breathing: a 1.6 s glow swell every 3.2 s, after the ripple.
            let b = since - 1.3, phase = b > 0 ? b.truncatingRemainder(dividingBy: 3.2) / 1.6 : 2
            frame.pulse = phase < 1 ? pow(sin(phase * .pi), 2) : 0
            frame.glow = max(0, 1 - since / 0.9) * 0.9 + 0.2 + frame.pulse * 0.55
        }
        return frame
    }

    private func target(_ t: Double) -> Double {
        if state == .scan { return from + 70 * t }
        let w = windup ? 0.2 : 0, f = from - (windup ? 14 : 0)
        if t < w { return from - 14 * sin(t / w * .pi / 2) }
        if t < w + 1.05 { return f + (to - f) * ease((t - w) / 1.05) }
        return to
    }
    private func ease(_ t: Double) -> Double { t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2 }
    private func normalize() { angle = (angle.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) }
    private func spin(windup: Bool) {
        normalize(); self.windup = windup; from = angle
        to = ((angle + (windup ? 1 : 200)) / 360).rounded(.up) * 360
        state = .spin; start = clock; trail = []
    }
    private func scan() { normalize(); state = .scan; start = clock; from = angle }
    private func finish(_ connected: Bool) {
        if connected { state = .done; settledAt = clock; angle = 0; velocity = 0 } else { angle = to; scan() }
    }
}

/// The OpenBridge arch, drawn live: a glass ring, the horizon bar, and the sun moving on `SunEngine`.
struct SunArch: View {
    var active: Bool
    var connected: Bool
    /// Where the ring's centre sits, as a fraction of the view's height.
    var centerY: Double = 0.3
    var radius: Double = 62
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var engine: SunEngine

    init(active: Bool, connected: Bool, centerY: Double = 0.3, radius: Double = 62, startScanning: Bool = false) {
        self.active = active; self.connected = connected; self.centerY = centerY; self.radius = radius
        _engine = State(initialValue: SunEngine(state: startScanning ? .scan : .idle))
    }

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { context, size in
                let dt = engine.elapsed(until: timeline.date)
                let frame = engine.step(dt, active: active, connected: connected, reduceMotion: reduceMotion)
                draw(&context, size: size, frame: frame)
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, frame: SunEngine.Frame) {
        let c = CGPoint(x: size.width / 2, y: size.height * centerY)
        let r = radius, ring = r * 0.29, sunR = r * 0.24, bar = r * 2.6
        func point(_ a: Double) -> CGPoint {
            let t = (a - 90) * .pi / 180
            return CGPoint(x: c.x + r * cos(t), y: c.y + r * sin(t))
        }
        let sun = Palette.sun(warmth: engine.warmth)

        if frame.pulse > 0 {
            let halo = Gradient(stops: [.init(color: .clear, location: 0),
                                        .init(color: Palette.pink.opacity(0.16 * frame.pulse), location: 0.45),
                                        .init(color: .clear, location: 1)])
            context.fill(Path(CGRect(origin: .zero, size: size)),
                         with: .radialGradient(halo, center: c, startRadius: r * 0.5, endRadius: r * 2.1))
        }
        // Ring, with a glow that follows speed, connection and the breath.
        let circle = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
        var glow = context
        glow.addFilter(.shadow(color: Palette.pink.opacity(0.3 + frame.glow * 0.5), radius: 5 + frame.glow * 13))
        glow.stroke(circle, with: .conicGradient(Palette.ring, center: c, angle: .degrees(-90)), lineWidth: ring)
        var highlight = Path()   // the glassy shine along the upper ring
        for i in 0...32 {
            let t = Double.pi * (1.08 + 0.84 * Double(i) / 32), hr = r + ring * 0.22
            let q = CGPoint(x: c.x + hr * cos(t), y: c.y + hr * sin(t))
            if i == 0 { highlight.move(to: q) } else { highlight.addLine(to: q) }
        }
        context.stroke(highlight, with: .linearGradient(Gradient(colors: [.white.opacity(0.55), .white.opacity(0.08), .clear]),
                                                         startPoint: CGPoint(x: c.x, y: c.y - r), endPoint: CGPoint(x: c.x, y: c.y + r)),
                       style: StrokeStyle(lineWidth: ring * 0.34, lineCap: .round))

        // Tail: one smooth stream along the arc.
        if let first = engine.trail.first, engine.trail.count > 1 {
            var tail = context
            tail.blendMode = .plusLighter
            for i in 0..<56 {
                let k = Double(i) / 56, a = first + (engine.angle - first) * k, p = point(a)
                let below = cos(a * .pi / 180) < 0 ? 0.55 : 1
                let size = sunR * (0.35 + 0.6 * k)
                tail.fill(Path(ellipseIn: CGRect(x: p.x - size, y: p.y - size, width: 2 * size, height: 2 * size)),
                          with: .color(sun.edge.opacity(0.035 + 0.16 * k * k * below)))
            }
        }
        // Connect ripples: they spread across the window and fade well before its edges.
        if frame.bloom > 0 && frame.bloom < 1 {
            let top = point(engine.angle)
            var ripple = context
            ripple.blendMode = .plusLighter
            for (delay, weight) in [(0.0, 1.0), (0.16, 0.65), (0.32, 0.4)] {
                let b = max(0, min(1, (frame.bloom - delay) / (1 - delay)))
                guard b > 0 && b < 1 else { continue }
                let fade = pow(1 - b, 2), radius = sunR + (b < 0.5 ? 4 * b * b * b : 1 - pow(-2 * b + 2, 3) / 2) * 300
                ripple.stroke(Path(ellipseIn: CGRect(x: top.x - radius, y: top.y - radius, width: 2 * radius, height: 2 * radius)),
                              with: .color(Palette.amber.opacity(fade * 0.75 * weight)), lineWidth: 6 * (1 - b) + 1)
            }
        }
        // Horizon bar.
        var horizon = context
        horizon.addFilter(.shadow(color: Palette.cream.opacity(0.45 + frame.glow * 0.5), radius: 4 + frame.glow * 8))
        let height = max(5, ring * 0.28)
        horizon.fill(Path(roundedRect: CGRect(x: c.x - bar / 2, y: c.y - height / 2, width: bar, height: height), cornerRadius: height / 2),
                     with: .color(Palette.cream))

        // Sun: a liquid drop stretched along its path by speed, with a jelly squash on stops.
        let p = point(engine.angle), speed = min(abs(engine.velocity) / 520, 1.15)
        let stretch = 1 + speed * 0.95, squash = 1 / stretch.squareRoot()
        let direction: Double = engine.velocity > 0 ? 1 : engine.velocity < 0 ? -1 : 0
        let lean = -direction * speed * sunR * 0.35
        var drop = context
        drop.translateBy(x: p.x, y: p.y)
        drop.rotate(by: .degrees(engine.angle))
        drop.opacity = cos(engine.angle * .pi / 180) < 0 ? 0.6 : 1
        var blob = drop
        blob.addFilter(.shadow(color: sun.glow, radius: 4 + engine.warmth * 7 + frame.glow * 11 + frame.pulse * 11))
        let w = sunR * stretch, h = sunR * squash * (1 + engine.jelly)
        blob.fill(Path(ellipseIn: CGRect(x: lean - w, y: -h, width: 2 * w, height: 2 * h)),
                  with: .radialGradient(Gradient(stops: [.init(color: sun.core, location: 0), .init(color: sun.middle, location: 0.45),
                                                         .init(color: sun.edge, location: 0.85), .init(color: sun.edge.opacity(0), location: 1)]),
                                        center: CGPoint(x: -3, y: -4), startRadius: 1, endRadius: sunR * 1.25))
        drop.opacity *= 0.85
        drop.fill(Path(ellipseIn: CGRect(x: lean - sunR * 0.25 * stretch - sunR * 0.32 * stretch, y: -sunR * 0.38 * squash - sunR * 0.14,
                                         width: sunR * 0.64 * stretch, height: sunR * 0.28)), with: .color(.white.opacity(0.9)))
    }
}

/// A small still arch for lists and buttons.
struct ArchMark: View {
    var size: CGFloat = 26
    var body: some View {
        Canvas { context, canvas in
            let s = canvas.width / 120, c = CGPoint(x: 60 * s, y: 62 * s)
            context.stroke(Path(ellipseIn: CGRect(x: c.x - 40 * s, y: c.y - 40 * s, width: 80 * s, height: 80 * s)),
                           with: .conicGradient(Palette.ring, center: c, angle: .degrees(-90)), lineWidth: 14 * s)
            context.fill(Path(roundedRect: CGRect(x: 10 * s, y: 59 * s, width: 100 * s, height: 7 * s), cornerRadius: 3.5 * s), with: .color(Palette.cream))
            context.fill(Path(ellipseIn: CGRect(x: 47 * s, y: 9 * s, width: 26 * s, height: 26 * s)), with: .color(Palette.sunGold))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

enum Palette {
    static let night = Color(red: 13 / 255, green: 11 / 255, blue: 38 / 255)
    static let dusk = Color(red: 29 / 255, green: 21 / 255, blue: 70 / 255)
    static let plum = Color(red: 58 / 255, green: 36 / 255, blue: 112 / 255)
    static let violet = Color(red: 109 / 255, green: 93 / 255, blue: 252 / 255)
    static let pink = Color(red: 1, green: 92 / 255, blue: 138 / 255)
    static let amber = Color(red: 1, green: 179 / 255, blue: 71 / 255)
    static let sunGold = Color(red: 1, green: 209 / 255, blue: 102 / 255)
    static let cream = Color(red: 1, green: 246 / 255, blue: 224 / 255)
    static let ok = Color(red: 61 / 255, green: 220 / 255, blue: 151 / 255)
    static let muted = Color(red: 180 / 255, green: 171 / 255, blue: 214 / 255)
    static let ring = Gradient(stops: [.init(color: sunGold, location: 0), .init(color: pink, location: 0.3), .init(color: violet, location: 0.55),
                                       .init(color: pink, location: 0.8), .init(color: sunGold, location: 1)])
    /// Buttons and the transfer bar: the sunset from violet to gold.
    static let sunset = LinearGradient(stops: [.init(color: violet, location: 0), .init(color: pink, location: 0.55), .init(color: amber, location: 1)],
                                       startPoint: .leading, endPoint: .trailing)
    static let bar = LinearGradient(stops: [.init(color: violet, location: 0), .init(color: pink, location: 0.55), .init(color: amber, location: 0.85),
                                            .init(color: sunGold, location: 1)], startPoint: .leading, endPoint: .trailing)
    static let background = RadialGradient(stops: [.init(color: plum, location: 0), .init(color: dusk, location: 0.5), .init(color: night, location: 1)],
                                           center: UnitPoint(x: 0.65, y: -0.1), startRadius: 0, endRadius: 900)

    struct Sun { let core: Color, middle: Color, edge: Color, glow: Color }
    /// Grey glass when nothing is connected, gold when connected.
    static func sun(warmth k: Double) -> Sun {
        func mix(_ a: (Double, Double, Double), _ b: (Double, Double, Double)) -> Color {
            Color(red: (a.0 + (b.0 - a.0) * k) / 255, green: (a.1 + (b.1 - a.1) * k) / 255, blue: (a.2 + (b.2 - a.2) * k) / 255)
        }
        return Sun(core: mix((244, 244, 248), (255, 253, 240)), middle: mix((196, 196, 208), (255, 224, 138)),
                   edge: mix((134, 134, 150), (255, 179, 71)), glow: mix((170, 170, 190), (255, 200, 90)).opacity(0.55 + 0.4 * k))
    }
}
