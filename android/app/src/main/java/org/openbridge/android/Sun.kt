package org.openbridge.android

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Palette {
    val night = Color(0xFF0D0B26)
    val dusk = Color(0xFF1D1546)
    val plum = Color(0xFF3A2470)
    val violet = Color(0xFF6D5DFC)
    val pink = Color(0xFFFF5C8A)
    val amber = Color(0xFFFFB347)
    val sun = Color(0xFFFFD166)
    val cream = Color(0xFFFFF6E0)
    val ok = Color(0xFF3DDC97)
    val muted = Color(0xFFB4ABD6)
    val fg = Color(0xFFF4F0FF)
    val sunset = Brush.horizontalGradient(0f to violet, 0.55f to pink, 1f to amber)

    class SunColors(val core: Color, val middle: Color, val edge: Color, val glow: Color)

    /** Grey glass when nothing is connected, gold when connected. */
    fun sunColors(k: Float): SunColors {
        fun mix(a: Int, b: Int) = Color(
            red = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * k) / 255f,
            green = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * k) / 255f,
            blue = ((a and 255) + ((b and 255) - (a and 255)) * k) / 255f)
        return SunColors(mix(0xF4F4F8, 0xFFFDF0), mix(0xC4C4D0, 0xFFE08A), mix(0x868696, 0xFFB347),
            mix(0xAAAABE, 0xFFC85A).copy(alpha = 0.55f + 0.4f * k))
    }
}

/**
 * The sun's motion around the arch. The sun chases a moving target on a spring, so the lag, overshoot
 * and wobble come from the physics. The Mac app's sun uses the same values.
 * States: idle (grey, still), scan (grey, slow orbit), spin (whip to the top), done (gold, breathing).
 */
class SunEngine {
    enum class State { IDLE, SCAN, SPIN, DONE }
    class Frame(val glow: Float = 0f, val bloom: Float = 0f, val pulse: Float = 0f)

    var state = State.IDLE; private set
    var angle = 0.0; private set          // degrees, 0 = top, clockwise
    var velocity = 0.0; private set       // degrees per second
    var jelly = 0.0; private set
    var warmth = 0.0; private set         // 0 grey, 1 gold
    val trail = ArrayDeque<Double>()
    /** Called once each time the sun reaches the top connected. */
    var onConnect: () -> Unit = {}
    private var jellyVelocity = 0.0
    private var previousVelocity = 0.0
    private var clock = 0.0
    private var start = 0.0
    private var from = 0.0
    private var to = 0.0
    private var windup = false
    private var settledAt = 0.0

    /** [active]: sharing has started. [connected]: a Mac has paired. */
    fun step(dt: Double, active: Boolean, connected: Boolean, reduceMotion: Boolean): Frame {
        clock += dt
        when {
            !active -> if (state != State.IDLE) { normalize(); state = State.IDLE }
            state == State.IDLE -> spin(windup = true)
            state == State.SCAN && connected -> spin(windup = false)
            state == State.DONE && !connected -> scan()
        }
        if (reduceMotion && state == State.SPIN) finish(connected)

        var glow = 0.0; var bloom = 0.0; var pulse = 0.0
        if (state == State.SPIN || state == State.SCAN) {
            val t = clock - start
            val h = dt / 8
            repeat(8) {
                val acceleration = 210 * (target(t) - angle) - 14.5 * velocity
                velocity += acceleration * h; angle += velocity * h
            }
            val acceleration = (velocity - previousVelocity) / max(dt, 1e-4); previousVelocity = velocity
            jellyVelocity += (-320 * jelly - 16 * jellyVelocity - acceleration * 0.00055) * dt
            jelly = (jelly + jellyVelocity * dt).coerceIn(-0.35, 0.35)
            trail.addLast(angle)
            val length = (6 + min(abs(velocity) / 18, 34.0)).toInt()
            while (trail.size > length) trail.removeFirst()
            glow = min(abs(velocity) / 700, 1.0) * (if (state == State.SCAN) 0.4 else 1.0)
            if (state == State.SPIN && t > (if (windup) 0.2 else 0.0) + 1.1 && abs(velocity) < 6 && abs(to - angle) < 0.6) finish(connected)
        } else {
            trail.removeFirstOrNull()
            jellyVelocity += (-320 * jelly - 16 * jellyVelocity) * dt; jelly += jellyVelocity * dt
            velocity *= 0.8
        }
        val goal = when (state) { State.DONE -> 1.0; State.SPIN -> 0.4; else -> 0.0 }
        warmth += (goal - warmth) * min(1.0, dt * (if (state == State.DONE) 7 else 3))
        if (state == State.DONE) {
            val since = clock - settledAt
            bloom = min(since / 1.3, 1.0)
            // Breathing: a 1.6 s glow swell every 3.2 s, after the ripple.
            val b = since - 1.3
            val phase = if (b > 0) (b % 3.2) / 1.6 else 2.0
            pulse = if (phase < 1) sin(phase * Math.PI).pow(2) else 0.0
            glow = max(0.0, 1 - since / 0.9) * 0.9 + 0.2 + pulse * 0.55
        }
        return Frame(glow.toFloat(), bloom.toFloat(), pulse.toFloat())
    }

    private fun target(t: Double): Double {
        if (state == State.SCAN) return from + 70 * t
        val w = if (windup) 0.2 else 0.0
        val f = from - (if (windup) 14 else 0)
        return when {
            t < w -> from - 14 * sin(t / w * Math.PI / 2)
            t < w + 1.05 -> f + (to - f) * ease((t - w) / 1.05)
            else -> to
        }
    }

    private fun normalize() { angle = ((angle % 360) + 360) % 360 }
    private fun spin(windup: Boolean) {
        normalize(); this.windup = windup; from = angle
        to = kotlin.math.ceil((angle + (if (windup) 1 else 200)) / 360) * 360
        state = State.SPIN; start = clock; trail.clear()
    }
    private fun scan() { normalize(); state = State.SCAN; start = clock; from = angle }
    private fun finish(connected: Boolean) {
        if (connected) { state = State.DONE; settledAt = clock; angle = 0.0; velocity = 0.0; onConnect() }
        else { angle = to; scan() }
    }
}

private fun ease(t: Double) = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

/** Draws the arch around [c]: a glass ring of radius [r], the horizon bar and the moving sun. */
fun DrawScope.drawSunArch(e: SunEngine, f: SunEngine.Frame, c: Offset, r: Float, rippleReach: Float) {
    val ring = r * 0.29f
    val sunR = r * 0.24f
    val bar = r * 2.6f
    fun point(a: Double): Offset {
        val t = Math.toRadians(a - 90)
        return Offset(c.x + r * cos(t).toFloat(), c.y + r * sin(t).toFloat())
    }
    val sun = Palette.sunColors(e.warmth.toFloat())

    if (f.pulse > 0) {
        val reach = r * 2.1f
        drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.24f to Color.Transparent,
            0.62f to Palette.pink.copy(alpha = 0.16f * f.pulse), 1f to Color.Transparent, center = c, radius = reach), reach, c)
    }
    // Ring glow, then the ring.
    for (i in 1..3) drawCircle(Palette.pink.copy(alpha = 0.05f + f.glow * 0.06f), r, c, style = Stroke(ring + i * 7f * (1 + f.glow)))
    rotate(-90f, c) {
        drawCircle(Brush.sweepGradient(0f to Palette.sun, 0.3f to Palette.pink, 0.55f to Palette.violet, 0.8f to Palette.pink,
            1f to Palette.sun, center = c), r, c, style = Stroke(ring))
    }
    val hr = r + ring * 0.22f
    drawArc(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f), Color.Transparent),
        startY = c.y - r, endY = c.y + r), startAngle = 194.4f, sweepAngle = 151.2f, useCenter = false,
        topLeft = Offset(c.x - hr, c.y - hr), size = Size(2 * hr, 2 * hr), style = Stroke(ring * 0.34f, cap = StrokeCap.Round))

    // Tail: one smooth stream along the arc.
    val first = e.trail.firstOrNull()
    if (first != null && e.trail.size > 1) {
        for (i in 0 until 56) {
            val k = i / 56f
            val a = first + (e.angle - first) * k
            val below = if (cos(Math.toRadians(a)) < 0) 0.55f else 1f
            drawCircle(sun.edge.copy(alpha = 0.035f + 0.16f * k * k * below), sunR * (0.35f + 0.6f * k), point(a), blendMode = BlendMode.Plus)
        }
    }
    // Connect ripples spread out and fade well before the screen edges.
    if (f.bloom > 0f && f.bloom < 1f) {
        val top = point(e.angle)
        for ((delay, weight) in listOf(0f to 1f, 0.16f to 0.65f, 0.32f to 0.4f)) {
            val b = ((f.bloom - delay) / (1 - delay)).coerceIn(0f, 1f)
            if (b <= 0f || b >= 1f) continue
            val fade = (1 - b) * (1 - b)
            drawCircle(Palette.amber.copy(alpha = fade * 0.75f * weight), sunR + ease(b.toDouble()).toFloat() * rippleReach, top,
                style = Stroke(6f * (1 - b) + 1.5f), blendMode = BlendMode.Plus)
        }
    }
    // Horizon bar with its glow.
    val height = max(5f, ring * 0.28f)
    drawRoundRect(Palette.cream.copy(alpha = 0.18f + f.glow * 0.2f), Offset(c.x - bar / 2 - 4, c.y - height / 2 - 4),
        Size(bar + 8, height + 8), CornerRadius(height))
    drawRoundRect(Palette.cream, Offset(c.x - bar / 2, c.y - height / 2), Size(bar, height), CornerRadius(height / 2))

    // Sun: a liquid drop stretched along its path by speed, with a jelly squash on stops.
    val p = point(e.angle)
    val speed = min(abs(e.velocity) / 520, 1.15).toFloat()
    val stretch = 1 + speed * 0.95f
    val squash = 1 / sqrt(stretch)
    val direction = if (e.velocity > 0) 1f else if (e.velocity < 0) -1f else 0f
    val lean = -direction * speed * sunR * 0.35f
    val below = if (cos(Math.toRadians(e.angle)) < 0) 0.6f else 1f
    val reach = sunR * (2.2f + f.glow + f.pulse)
    drawCircle(Brush.radialGradient(0f to sun.glow.copy(alpha = sun.glow.alpha * below), 1f to Color.Transparent, center = p, radius = reach), reach, p)
    withTransform({ translate(p.x, p.y); rotate(e.angle.toFloat(), Offset.Zero) }) {
        val w = sunR * stretch
        val h = sunR * squash * (1 + e.jelly.toFloat())
        drawOval(Brush.radialGradient(0f to sun.core, 0.45f to sun.middle, 0.85f to sun.edge, 1f to sun.edge.copy(alpha = 0f),
            center = Offset(-sunR * 0.15f, -sunR * 0.2f), radius = sunR * 1.25f), Offset(lean - w, -h), Size(2 * w, 2 * h), alpha = below)
        drawOval(Color.White.copy(alpha = 0.9f), Offset(lean - sunR * 0.57f * stretch, -sunR * 0.38f * squash - sunR * 0.14f),
            Size(sunR * 0.64f * stretch, sunR * 0.28f), alpha = below * 0.85f)
    }
}
