package org.openbridge.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * How OpenBridge looks: its colours, the animated background and the share button. Chosen in settings and
 * remembered on each device. Dusk is the original; Aurora and Tide are drafts, shown as "Preview".
 */
enum class Look(val id: String, val label: String, val labelZh: String, val draft: Boolean) {
    /** Sunset glass: the sun travels the arch and turns gold. */
    DUSK("dusk", "Dusk", "黄昏", false),
    /** Northern lights: ribbons drift across a starry sky; the button is a shimmering halo with a comet. */
    AURORA("aurora", "Aurora", "极光", true),
    /** Deep water: waves roll at the bottom; the button is a glass pool that fills when a computer connects. */
    TIDE("tide", "Tide", "潮汐", true);

    val colors: LookColors get() = when (this) { DUSK -> duskColors; AURORA -> auroraColors; TIDE -> tideColors }

    companion object {
        fun of(id: String?): Look = entries.firstOrNull { it.id == id } ?: DUSK
    }
}

/**
 * A look's colours. The names come from Dusk, the first look; each look fills the same roles:
 * night/dusk/plum are the background from darkest to lightest, violet/pink/amber the accents (amber is the main
 * action), sun/cream highlights, ok success, muted secondary text, fg text.
 */
class LookColors(
    val night: Color, val dusk: Color, val plum: Color,
    val violet: Color, val pink: Color, val amber: Color,
    val sun: Color, val cream: Color, val ok: Color, val muted: Color, val fg: Color,
    /** The accent gradient (buttons, progress, selection), left to right. */
    val accent: List<Pair<Float, Color>>,
    /** The share button lit up: core, middle, edge, glow (as 0xRRGGBB). */
    val lit: IntArray,
)

private val duskColors = LookColors(
    night = Color(0xFF0D0B26), dusk = Color(0xFF1D1546), plum = Color(0xFF3A2470),
    violet = Color(0xFF6D5DFC), pink = Color(0xFFFF5C8A), amber = Color(0xFFFFB347),
    sun = Color(0xFFFFD166), cream = Color(0xFFFFF6E0), ok = Color(0xFF3DDC97), muted = Color(0xFFB4ABD6), fg = Color(0xFFF4F0FF),
    accent = listOf(0f to Color(0xFF6D5DFC), 0.55f to Color(0xFFFF5C8A), 1f to Color(0xFFFFB347)),
    lit = intArrayOf(0xFFFDF0, 0xFFE08A, 0xFFB347, 0xFFC85A))

private val auroraColors = LookColors(
    night = Color(0xFF020A14), dusk = Color(0xFF061A26), plum = Color(0xFF0B2E3A),
    violet = Color(0xFF2BB8FE), pink = Color(0xFF9B7BFF), amber = Color(0xFF3DF5B0),
    sun = Color(0xFFB6FFE0), cream = Color(0xFFE9FFF6), ok = Color(0xFF5CFFB0), muted = Color(0xFF94B4C0), fg = Color(0xFFEEFBFF),
    accent = listOf(0f to Color(0xFF9B7BFF), 0.5f to Color(0xFF2BB8FE), 1f to Color(0xFF3DF5B0)),
    lit = intArrayOf(0xF4FFFB, 0xB6FFE0, 0x3DF5B0, 0x5CFFC0))

private val tideColors = LookColors(
    night = Color(0xFF020B1F), dusk = Color(0xFF05214A), plum = Color(0xFF0A3566),
    violet = Color(0xFF2F7BFF), pink = Color(0xFFFF7F6E), amber = Color(0xFF3FE0D0),
    sun = Color(0xFFA8F4FF), cream = Color(0xFFE8FCFF), ok = Color(0xFF4BE3A0), muted = Color(0xFF93AECF), fg = Color(0xFFEEF7FF),
    accent = listOf(0f to Color(0xFF2F7BFF), 0.6f to Color(0xFF3FE0D0), 1f to Color(0xFFA8F4FF)),
    lit = intArrayOf(0xF2FEFF, 0xB5F6FF, 0x3FE0D0, 0x5CE8F0))

/** The current look's colours. Reading them in a composable or a Canvas redraws it when the look changes. */
object Palette {
    var look by mutableStateOf(Look.DUSK)

    val night get() = look.colors.night
    val dusk get() = look.colors.dusk
    val plum get() = look.colors.plum
    val violet get() = look.colors.violet
    val pink get() = look.colors.pink
    val amber get() = look.colors.amber
    val sun get() = look.colors.sun
    val cream get() = look.colors.cream
    val ok get() = look.colors.ok
    val muted get() = look.colors.muted
    val fg get() = look.colors.fg
    val sunset: Brush get() = Brush.horizontalGradient(*look.colors.accent.toTypedArray())
    /** The still part of the background: lighter at the top. */
    val sky: Brush get() = Brush.verticalGradient(0f to plum, 0.42f to dusk, 1f to night)

    class SunColors(val core: Color, val middle: Color, val edge: Color, val glow: Color)

    /** Grey glass when nothing is connected, the look's light when connected. */
    fun sunColors(k: Float, look: Look = this.look): SunColors {
        val lit = look.colors.lit
        fun mix(a: Int, b: Int) = Color(
            red = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * k) / 255f,
            green = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * k) / 255f,
            blue = ((a and 255) + ((b and 255) - (a and 255)) * k) / 255f)
        // Grey is moonlight: bright silver-lavender glass with a soft white glow, never a dark rim.
        return SunColors(mix(0xFFFFFF, lit[0]), mix(0xE6E3F2, lit[1]), mix(0xB4B0CC, lit[2]),
            mix(0xF0EEFF, lit[3]).copy(alpha = 0.3f + 0.65f * k))
    }
}

/**
 * The current look's background behind [content]. Aurora and Tide keep moving (about 30 frames a second, still when
 * [still]); [energy] (0 to 1) makes them brighter and livelier while sharing or connected, and [level] (0 to 1)
 * raises Tide's water, for example with transfer progress.
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier, energy: Float = 0f, level: Float = 0f, still: Boolean = false,
             content: @Composable BoxScope.() -> Unit) {
    val look = Palette.look
    Box(modifier) {
        if (look == Look.DUSK) Box(Modifier.matchParentSize().background(Palette.sky))
        else {
            var time by remember { mutableFloatStateOf(7f) }
            if (!still) LaunchedEffect(look) {
                var last = 0L
                while (true) withFrameNanos { now ->
                    if (last == 0L) last = now
                    if (now - last >= 33_000_000L) { time += ((now - last) / 1e9f).coerceAtMost(0.1f); last = now }
                }
            }
            val shownEnergy by animateFloatAsState(energy, tween(1400))
            val shownLevel by animateFloatAsState(level, spring(stiffness = Spring.StiffnessVeryLow))
            Canvas(Modifier.matchParentSize()) { drawBackdrop(look, time, shownEnergy, shownLevel) }
        }
        content()
    }
}

/** A look's background at time [t] seconds (see [Backdrop]). Also used for the small previews in settings. */
fun DrawScope.drawBackdrop(look: Look, t: Float, energy: Float = 0f, level: Float = 0f) = when (look) {
    Look.DUSK -> drawRect(Brush.verticalGradient(0f to look.colors.plum, 0.42f to look.colors.dusk, 1f to look.colors.night))
    Look.AURORA -> drawAurora(t, energy)
    Look.TIDE -> drawTide(t, energy, level)
}

/** The share button in the current look, driven by the same engine everywhere. */
fun DrawScope.drawHero(e: SunEngine, f: SunEngine.Frame, c: Offset, r: Float, rippleReach: Float, look: Look = Palette.look) = when (look) {
    Look.DUSK -> drawSunArch(e, f, c, r, rippleReach)
    Look.AURORA -> drawHalo(e, f, c, r, rippleReach)
    Look.TIDE -> drawPool(e, f, c, r, rippleReach)
}

/** Stable pseudo-random 0..1 for star and bubble placement. */
private fun hash(i: Int): Float {
    val x = sin(i * 12.9898 + 78.233) * 43758.5453
    return (x - kotlin.math.floor(x)).toFloat()
}

private const val TAU = (2 * PI).toFloat()

private fun DrawScope.drawAurora(t: Float, energy: Float) {
    val w = size.width; val h = size.height
    val unit = min(w, h)
    drawRect(Brush.verticalGradient(0f to Color(0xFF020A14), 0.55f to Color(0xFF051B26), 1f to Color(0xFF06121D)))
    // Stars, twinkling, thinning out towards the horizon.
    for (i in 0 until 90) {
        val x = hash(i * 3 + 1) * w
        val y = hash(i * 3 + 2) * h * 0.8f
        val twinkle = 0.5f + 0.5f * sin(t * (0.5f + hash(i + 300) * 1.6f) + i)
        val fade = 1f - y / (h * 0.8f) * 0.6f
        drawCircle(Color.White.copy(alpha = (0.06f + 0.34f * twinkle * hash(i + 500)) * fade), (0.5f + hash(i + 700) * 1.1f) * density, Offset(x, y))
    }
    val speed = 1f + energy * 0.6f
    val bright = 0.6f + 0.4f * energy
    // Three curtains high in the sky: a bright lower edge, and light rising from it in soft folds.
    val ribbons = listOf(Triple(Color(0xFF3DF5B0), 0.17f, 0f), Triple(Color(0xFF2BD9FE), 0.25f, 2.1f), Triple(Color(0xFFB07BFF), 0.11f, 4.2f))
    for ((i, ribbon) in ribbons.withIndex()) {
        val (color, base, phase) = ribbon
        val amplitude = h * 0.035f + unit * 0.02f
        fun edge(x: Float): Float {
            val u = x / w
            return h * base + amplitude * sin(u * TAU * 0.8f + t * 0.22f * speed + phase) +
                amplitude * 0.45f * sin(u * TAU * 2.3f - t * 0.37f * speed + phase * 1.7f)
        }
        fun height(x: Float) = h * (0.13f + 0.06f * sin(x / w * TAU * 1.4f + t * 0.3f + phase)) * (0.8f + 0.2f * energy)
        // Rays side by side, each fading upwards on its own, so the curtain has no hard top.
        val rays = 96
        for (s in 0 until rays) {
            val x = w * (s + 0.5f) / rays
            val fold = 0.35f + 0.65f * (0.5f + 0.5f * sin(x / w * TAU * 4.5f + t * 0.55f * speed + phase + i))
            val y = edge(x)
            val top = y - height(x) * (0.55f + 0.45f * fold)
            drawLine(Brush.verticalGradient(0f to Color.Transparent, 0.7f to color.copy(alpha = 0.10f * fold * bright), 1f to color.copy(alpha = 0.26f * fold * bright),
                startY = top, endY = y), Offset(x, top), Offset(x, y), strokeWidth = w / rays + 0.5f, blendMode = BlendMode.Plus)
        }
        val line = Path()
        for (s in 0..72) { val x = w * s / 72; if (s == 0) line.moveTo(x, edge(x)) else line.lineTo(x, edge(x)) }
        drawPath(line, color.copy(alpha = 0.08f * bright), style = Stroke(unit * 0.035f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        drawPath(line, color.copy(alpha = 0.45f * bright), style = Stroke(1.4f * density, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    }
    // A low glow on the horizon.
    drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color(0xFF3DF5B0).copy(alpha = 0.06f + 0.05f * energy)))
}

private fun DrawScope.drawTide(t: Float, energy: Float, level: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(0f to Color(0xFF0A3A6B), 0.45f to Color(0xFF05214A), 1f to Color(0xFF020B1F)))
    // Light from the surface: slow, swaying beams.
    for (i in 0 until 4) {
        val x0 = w * (0.12f + 0.26f * i) + sin(t * 0.17f + i * 1.3f) * w * 0.05f
        val spread = w * 0.09f
        val beam = Path().apply {
            moveTo(x0 - spread * 0.25f, 0f); lineTo(x0 + spread * 0.25f, 0f)
            lineTo(x0 + spread + w * 0.08f, h * 0.75f); lineTo(x0 - spread * 0.4f + w * 0.08f, h * 0.75f); close()
        }
        val shimmer = 0.6f + 0.4f * sin(t * 0.45f + i * 2f)
        drawPath(beam, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f * shimmer), 1f to Color.Transparent, endY = h * 0.75f), blendMode = BlendMode.Plus)
    }
    val speed = 1f + energy * 0.5f
    val surface = h * (0.80f - 0.24f * level.coerceIn(0f, 1f))
    // Bubbles rising, behind the waves.
    for (i in 0 until 24) {
        val rise = 0.035f + hash(i + 40) * 0.05f
        val p = ((t * rise * speed + hash(i + 80)) % 1f)
        val y = h * (1.05f - p * 1.1f)
        val x = hash(i + 120) * w + sin(t * 1.1f + i) * 6f * density
        val radius = (1.5f + hash(i + 160) * 3.5f) * density
        drawCircle(Color.White.copy(alpha = 0.28f * (1 - p) * (0.4f + 0.6f * hash(i + 200))), radius, Offset(x, y), style = Stroke(0.9f * density))
    }
    for (i in 0 until 3) {
        val amplitude = (5f + 3.5f * i + 4f * energy) * density
        val base = surface + i * h * 0.05f
        fun y(x: Float): Float {
            val u = x / w
            return base + amplitude * sin(u * TAU * (1.05f + 0.35f * i) + t * (0.65f + 0.22f * i) * speed + i * 1.7f) +
                amplitude * 0.35f * sin(u * TAU * (3.1f + i) - t * 1.05f * speed + i)
        }
        val wave = Path(); val crest = Path()
        val steps = 60
        for (s in 0..steps) {
            val x = w * s / steps
            if (s == 0) { wave.moveTo(x, y(x)); crest.moveTo(x, y(x)) } else { wave.lineTo(x, y(x)); crest.lineTo(x, y(x)) }
        }
        wave.lineTo(w, h); wave.lineTo(0f, h); wave.close()
        drawPath(wave, Brush.verticalGradient(0f to Color(0xFF3FE0D0).copy(alpha = 0.13f + 0.05f * i), 0.35f to Color(0xFF0E4C8A).copy(alpha = 0.45f),
            1f to Color(0xFF031634).copy(alpha = 0.9f), startY = base - amplitude, endY = h))
        drawPath(crest, Color(0xFFE8FCFF).copy(alpha = 0.10f + 0.07f * i), style = Stroke((1f + 0.4f * i) * density, cap = StrokeCap.Round))
    }
}

/** Aurora's button: three shimmering ribbons in a ring, and a comet that runs the ring like Dusk's sun. */
private fun DrawScope.drawHalo(e: SunEngine, f: SunEngine.Frame, c: Offset, r: Float, rippleReach: Float) {
    val colors = Look.AURORA.colors
    val k = e.warmth.toFloat()
    val t = e.time.toFloat()
    val head = Palette.sunColors(k, Look.AURORA)
    fun point(a: Double, radius: Float = r): Offset {
        val angle = Math.toRadians(a - 90)
        return Offset(c.x + radius * cos(angle).toFloat(), c.y + radius * sin(angle).toFloat())
    }
    val silver = Color(0xFFC9D6E3)
    drawCircle(Brush.radialGradient(0f to colors.amber.copy(alpha = 0.05f + 0.16f * k), 1f to Color.Transparent, center = c, radius = r * 1.7f), r * 1.7f, c)
    if (f.pulse > 0) drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.45f to Color.Transparent,
        0.7f to colors.amber.copy(alpha = 0.16f * f.pulse), 1f to Color.Transparent, center = c, radius = r * 2.1f), r * 2.1f, c)
    val ribbons = listOf(colors.amber, Color(0xFF2BD9FE), Color(0xFFB07BFF))
    for ((i, base) in ribbons.withIndex()) {
        val color = lerp(silver, base, 0.35f + 0.65f * k)
        val ring = Path()
        val steps = 160
        for (s in 0..steps) {
            val theta = s.toFloat() / steps * TAU
            val wobble = r * 0.03f * (0.3f + f.glow + 0.4f * k) * sin(5 * theta + t * (1.1f + 0.35f * i) + i * 2.1f) +
                r * 0.012f * sin(9 * theta - t * 1.7f + i)
            val radius = r + wobble + (i - 1) * r * 0.03f
            val x = c.x + radius * cos(theta); val y = c.y + radius * sin(theta)
            if (s == 0) ring.moveTo(x, y) else ring.lineTo(x, y)
        }
        ring.close()
        drawPath(ring, color.copy(alpha = 0.07f + 0.05f * k), style = Stroke(r * 0.24f), blendMode = BlendMode.Plus)
        drawPath(ring, color.copy(alpha = 0.45f + 0.4f * k), style = Stroke(r * (0.075f - 0.015f * i)), blendMode = BlendMode.Plus)
    }
    // The comet's tail along the ring.
    val first = e.trail.firstOrNull()
    if (first != null && e.trail.size > 1) {
        for (i in 0 until 48) {
            val q = i / 48f
            drawCircle(head.edge.copy(alpha = 0.03f + 0.2f * q * q), r * 0.13f * (0.3f + 0.7f * q), point(first + (e.angle - first) * q), blendMode = BlendMode.Plus)
        }
    }
    if (f.bloom > 0f && f.bloom < 1f) {
        val top = point(e.angle)
        for ((delay, weight) in listOf(0f to 1f, 0.16f to 0.65f, 0.32f to 0.4f)) {
            val b = ((f.bloom - delay) / (1 - delay)).coerceIn(0f, 1f)
            if (b <= 0f || b >= 1f) continue
            drawCircle(colors.amber.copy(alpha = (1 - b) * (1 - b) * 0.75f * weight), r * 0.16f + ease(b.toDouble()).toFloat() * rippleReach, top,
                style = Stroke(6f * (1 - b) + 1.5f), blendMode = BlendMode.Plus)
        }
    }
    // The comet: a bright star with a slow four-point flare.
    val p = point(e.angle)
    val size = r * 0.17f * (1 + e.jelly.toFloat() * 0.6f)
    val reach = size * (2.4f + f.glow + f.pulse)
    drawCircle(Brush.radialGradient(0f to head.glow, 1f to Color.Transparent, center = p, radius = reach), reach, p)
    drawCircle(Brush.radialGradient(0f to head.core, 0.5f to head.middle, 0.9f to head.edge, 1f to head.edge.copy(alpha = 0f), center = p, radius = size), size, p)
    rotate(t * 12f, p) {
        for (a in 0 until 2) rotate(a * 90f, p) {
            drawLine(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.55f + 0.3f * k), 1f to Color.Transparent,
                startX = p.x - size * 2.6f, endX = p.x + size * 2.6f), Offset(p.x - size * 2.6f, p.y), Offset(p.x + size * 2.6f, p.y), strokeWidth = size * 0.12f)
        }
    }
}

/** Tide's button: a glass pool that fills with water as a computer connects, and a droplet that runs its rim. */
private fun DrawScope.drawPool(e: SunEngine, f: SunEngine.Frame, c: Offset, r: Float, rippleReach: Float) {
    val colors = Look.TIDE.colors
    val k = e.warmth.toFloat()
    val t = e.time.toFloat()
    val drop = Palette.sunColors(k, Look.TIDE)
    fun point(a: Double, radius: Float = r): Offset {
        val angle = Math.toRadians(a - 90)
        return Offset(c.x + radius * cos(angle).toFloat(), c.y + radius * sin(angle).toFloat())
    }
    drawCircle(Brush.radialGradient(0f to colors.amber.copy(alpha = 0.06f + 0.18f * k + 0.1f * f.glow), 1f to Color.Transparent, center = c, radius = r * 1.75f), r * 1.75f, c)
    if (f.pulse > 0) drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.45f to Color.Transparent,
        0.7f to colors.amber.copy(alpha = 0.15f * f.pulse), 1f to Color.Transparent, center = c, radius = r * 2.1f), r * 2.1f, c)
    val inner = r * 0.94f
    drawCircle(Brush.verticalGradient(0f to Color(0xFF0D3260).copy(alpha = 0.7f), 1f to Color(0xFF041530).copy(alpha = 0.85f),
        startY = c.y - inner, endY = c.y + inner), inner, c)
    // Water: rises from a puddle to nearly full when connected, and sloshes with the droplet's speed.
    val fill = 0.16f + 0.64f * k
    val surface = c.y + inner - 2 * inner * fill
    val tilt = (e.velocity / 700).coerceIn(-1.0, 1.0).toFloat() * inner * 0.18f + e.jelly.toFloat() * inner * 0.4f
    val amplitude = inner * (0.03f + 0.05f * f.glow + 0.02f * k)
    clipPath(Path().apply { addOval(Rect(c, inner)) }) {
        for (layer in 1 downTo 0) {
            val wave = Path()
            val steps = 48
            for (s in 0..steps) {
                val u = s.toFloat() / steps
                val x = c.x - inner + 2 * inner * u
                val y = surface + layer * inner * 0.07f + tilt * (u * 2 - 1) +
                    amplitude * sin(u * TAU * 1.2f + t * (1.7f + 0.6f * layer) + layer * 2.2f) +
                    amplitude * 0.4f * sin(u * TAU * 2.7f - t * 2.3f)
                if (s == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            wave.lineTo(c.x + inner, c.y + inner); wave.lineTo(c.x - inner, c.y + inner); wave.close()
            val top = lerp(Color(0xFF8FA6C4), colors.amber, 0.3f + 0.7f * k)
            drawPath(wave, Brush.verticalGradient(0f to top.copy(alpha = if (layer == 0) 0.85f else 0.4f), 0.5f to Color(0xFF1667B8).copy(alpha = 0.75f),
                1f to Color(0xFF07285A), startY = surface - amplitude, endY = c.y + inner))
            if (layer == 0) drawPath(wave, Color.White.copy(alpha = 0.22f + 0.2f * k), style = Stroke(1.4f * density))
        }
        for (i in 0 until 7) {
            val p = ((t * (0.12f + hash(i + 9) * 0.1f) + hash(i + 19)) % 1f)
            val y = c.y + inner - p * (c.y + inner - surface)
            val x = c.x - inner * 0.7f + hash(i + 29) * inner * 1.4f + sin(t * 2f + i) * inner * 0.03f
            drawCircle(Color.White.copy(alpha = 0.45f * (1 - p)), inner * (0.02f + hash(i + 39) * 0.025f), Offset(x, y), style = Stroke(1f * density))
        }
    }
    // The glass rim and its shine.
    rotate(t * 8f, c) {
        drawCircle(Brush.sweepGradient(0f to colors.sun, 0.3f to colors.amber, 0.55f to colors.violet, 0.8f to colors.amber, 1f to colors.sun, center = c),
            r, c, style = Stroke(r * (0.07f + 0.03f * k)))
    }
    drawArc(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent), startY = c.y - r, endY = c.y),
        startAngle = 200f, sweepAngle = 70f, useCenter = false, topLeft = Offset(c.x - r * 0.82f, c.y - r * 0.82f),
        size = androidx.compose.ui.geometry.Size(r * 1.64f, r * 1.64f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
    // The droplet's tail and the droplet.
    val first = e.trail.firstOrNull()
    if (first != null && e.trail.size > 1) {
        for (i in 0 until 40) {
            val q = i / 40f
            drawCircle(drop.edge.copy(alpha = 0.04f + 0.18f * q * q), r * 0.14f * (0.3f + 0.7f * q), point(first + (e.angle - first) * q), blendMode = BlendMode.Plus)
        }
    }
    if (f.bloom > 0f && f.bloom < 1f) {
        val top = point(e.angle)
        for ((delay, weight) in listOf(0f to 1f, 0.16f to 0.65f, 0.32f to 0.4f)) {
            val b = ((f.bloom - delay) / (1 - delay)).coerceIn(0f, 1f)
            if (b <= 0f || b >= 1f) continue
            drawCircle(colors.sun.copy(alpha = (1 - b) * (1 - b) * 0.7f * weight), r * 0.16f + ease(b.toDouble()).toFloat() * rippleReach, top,
                style = Stroke(5f * (1 - b) + 1.5f), blendMode = BlendMode.Plus)
        }
    }
    val p = point(e.angle)
    val size = r * 0.16f
    val speed = min(abs(e.velocity) / 520, 1.0).toFloat()
    val reach = size * (2.2f + f.glow + f.pulse)
    drawCircle(Brush.radialGradient(0f to drop.glow, 1f to Color.Transparent, center = p, radius = reach), reach, p)
    rotate(e.angle.toFloat(), p) {
        val stretch = 1 + speed * 0.8f
        drawOval(Brush.radialGradient(0f to drop.core, 0.5f to drop.middle, 0.9f to drop.edge, 1f to drop.edge.copy(alpha = 0f),
            center = Offset(p.x - size * 0.2f, p.y - size * 0.25f), radius = size * 1.3f),
            Offset(p.x - size * stretch, p.y - size * (1 + e.jelly.toFloat()) / kotlin.math.sqrt(stretch)),
            androidx.compose.ui.geometry.Size(2 * size * stretch, 2 * size * (1 + e.jelly.toFloat()) / kotlin.math.sqrt(stretch)))
        drawCircle(Color.White.copy(alpha = 0.85f), size * 0.25f, Offset(p.x - size * 0.35f, p.y - size * 0.35f))
    }
}
