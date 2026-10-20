package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawHero
import kotlin.math.sin

/*
 * Aurora's drawing: stars for phones and folders, the comet that carries transfers along the horizon, the soft mint
 * glow of selection, and the halo in the sky header.
 */

/** Aurora's warm warning colour: the comet turns this when a transfer stops part way. */
val auroraWarning = Color(0xFFFFC46B)

/**
 * Selection and keyboard focus as light rather than paint: a faint mint wash with a glow around the edge when
 * selected, a thin bright ring at the keyboard's place. `resting` gives tiles a quiet frame even when not chosen.
 */
fun Modifier.auroraGlow(selected: Boolean, cursor: Boolean, shape: Shape, resting: Boolean = false) = composed {
    val mint = Palette.amber
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val radius = when (outline) {
            is androidx.compose.ui.graphics.Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            else -> 0f
        }
        if (selected) {
            // The glow: rings growing outwards, each fainter.
            for (i in 5 downTo 1) {
                val grow = i * 1.6f * density
                drawRoundRect(mint.copy(alpha = 0.16f / i), Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow),
                    CornerRadius(radius + grow), style = Stroke(1.8f * density), blendMode = BlendMode.Plus)
            }
            drawRoundRect(mint.copy(alpha = 0.10f), cornerRadius = CornerRadius(radius))
            drawRoundRect(mint.copy(alpha = 0.55f), cornerRadius = CornerRadius(radius), style = Stroke(1.2f * density))
        } else if (resting) {
            drawRoundRect(Brush.verticalGradient(listOf(Palette.fg.copy(alpha = 0.07f), Palette.fg.copy(alpha = 0.03f))), cornerRadius = CornerRadius(radius))
            drawRoundRect(Palette.fg.copy(alpha = 0.10f), cornerRadius = CornerRadius(radius), style = Stroke(1f * density))
        }
        if (cursor) drawRoundRect(Palette.sun.copy(alpha = 0.9f), cornerRadius = CornerRadius(radius), style = Stroke(1.6f * density))
    }
}

/** A four-pointed star with a soft glow; `bright` 0..1, `twinkle` 0..1 breathing. */
fun DrawScope.drawStar(c: Offset, r: Float, color: Color, bright: Float, twinkle: Float = 1f) {
    val glow = r * (2.2f + 0.8f * twinkle)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * bright), Color.Transparent), c, glow), glow, c, blendMode = BlendMode.Plus)
    val arm = r * (1.5f + 0.5f * twinkle)
    val thin = r * 0.22f
    val star = Path().apply {
        moveTo(c.x, c.y - arm); quadraticTo(c.x + thin, c.y - thin, c.x + arm, c.y)
        quadraticTo(c.x + thin, c.y + thin, c.x, c.y + arm); quadraticTo(c.x - thin, c.y + thin, c.x - arm, c.y)
        quadraticTo(c.x - thin, c.y - thin, c.x, c.y - arm); close()
    }
    drawPath(star, Color.White.copy(alpha = 0.55f + 0.45f * bright))
    drawCircle(Color.White.copy(alpha = 0.6f + 0.4f * bright), r * 0.42f, c)
}

/** A phone in the switcher: a glowing orb, brighter with a ring when open, dim when out of reach. */
fun DrawScope.drawOrb(c: Offset, r: Float, open: Boolean, here: Boolean, cable: Boolean, t: Float, seed: Int) {
    val mint = Look.AURORA.colors.amber
    val sky = Look.AURORA.colors.violet
    val color = if (cable) sky else mint
    val bright = when { open -> 1f; here -> 0.7f; else -> 0.35f }
    val breathe = 0.5f + 0.5f * sin(t * (0.8f + (seed % 5) * 0.13f) + seed)
    val glow = r * (1.9f + 0.4f * breathe)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * bright), Color.Transparent), c, glow), glow, c, blendMode = BlendMode.Plus)
    drawCircle(Brush.radialGradient(listOf(Color.White, color.copy(alpha = 0.9f), color.copy(alpha = 0.3f)), c, r * 0.62f), r * 0.62f * (0.75f + 0.25f * bright), c,
        alpha = 0.45f + 0.55f * bright)
    if (open) drawCircle(Color.White.copy(alpha = 0.75f), r * 0.95f, c, style = Stroke(1.3f * density))
}

/** The horizon under the header: a fine line fading at both ends. */
fun DrawScope.drawHorizon(y: Float) {
    drawLine(Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Palette.fg.copy(alpha = 0.22f), 0.92f to Palette.fg.copy(alpha = 0.22f), 1f to Color.Transparent),
        Offset(0f, y), Offset(size.width, y), strokeWidth = 1f * density)
}

/** The comet at `x` on the horizon at `y`, its tail fading behind it; amber and still when the transfer stopped. */
fun DrawScope.drawComet(x: Float, y: Float, t: Float, stopped: Boolean) {
    val color = if (stopped) auroraWarning else Look.AURORA.colors.amber
    val tail = (140f * density).coerceAtMost(x)
    if (tail > 1f) {
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.18f)), startX = x - tail, endX = x), Offset(x - tail, y), Offset(x, y),
            strokeWidth = 7f * density, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.9f)), startX = x - tail, endX = x), Offset(x - tail, y), Offset(x, y),
            strokeWidth = 1.8f * density, cap = StrokeCap.Round)
    }
    val flicker = if (stopped) 1f else 0.85f + 0.15f * sin(t * 6f)
    val head = 4.5f * density
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f * flicker), Color.Transparent), Offset(x, y), head * 4.5f), head * 4.5f, Offset(x, y), blendMode = BlendMode.Plus)
    drawCircle(Brush.radialGradient(listOf(Color.White, color), Offset(x, y), head), head, Offset(x, y))
    rotate(if (stopped) 45f else t * 20f, Offset(x, y)) {
        for (a in 0 until 2) rotate(a * 90f, Offset(x, y)) {
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent), startX = x - head * 3, endX = x + head * 3),
                Offset(x - head * 3, y), Offset(x + head * 3, y), strokeWidth = 1f * density)
        }
    }
}

/**
 * The open phone's halo in the sky header: lit, its comet resting at the top, orbiting slowly while a transfer runs
 * (the whole halo turns, which the rings don't show and the comet does).
 */
@Composable
fun HeaderHalo(orbiting: Boolean, modifier: Modifier) {
    val engine = remember { SunEngine() }
    var frame by remember { mutableStateOf(SunEngine.Frame()) }
    var turn by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableLongStateOf(0L) }
    val orbitingNow by rememberUpdatedState(orbiting)
    LaunchedEffect(engine) {
        repeat(240) { frame = engine.step(1.0 / 60, true, true, true) }
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
            last = now
            if (!Platform.reduceMotion) {
                frame = engine.step(dt, true, true, false)
                // Orbit at 40° a second; when it stops, glide on to the top and rest there.
                turn = if (orbitingNow) (turn + 40f * dt.toFloat()) % 360f
                    else if (turn > 0.5f) (turn + maxOf(1f, (360f - turn) * 3f * dt.toFloat())).let { if (it >= 360f) 0f else it } else 0f
            }
            tick = now
        }
    }
    Canvas(modifier) {
        tick
        val c = Offset(size.width / 2, size.height / 2)
        rotate(turn, c) { drawHero(engine, frame, c, minOf(size.width, size.height) * 0.36f, size.minDimension * 0.6f, Look.AURORA) }
    }
}
