package org.openbridge.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import org.openbridge.ui.Look
import kotlin.math.PI
import kotlin.math.sin

/*
 * Tide's drawing: water filling a capsule as it's selected, portholes for phones, bubbles, and the wave along the
 * surface bar.
 */

private const val TAU = (2 * PI).toFloat()
private val aqua get() = Look.TIDE.colors.amber
private val deep get() = Look.TIDE.colors.violet
private val foam get() = Look.TIDE.colors.cream

/**
 * Water in a shape (the caller clips): fills from the left (or from the bottom, `vertical`) to `fraction`, with a
 * slowly moving wavy edge.
 */
fun DrawScope.drawWaterFill(fraction: Float, t: Float, vertical: Boolean = false) {
    if (fraction <= 0.001f) return
    val w = size.width; val h = size.height
    val water = Path()
    val steps = 24
    if (!vertical) {
        val edge = w * fraction
        val sway = 5f * density * minOf(1f, fraction * 3f) * (1f - fraction * 0.7f)
        water.moveTo(0f, 0f)
        for (s in 0..steps) {
            val y = h * s / steps
            water.lineTo(edge + sway * sin(y / h * TAU * 0.9f + t * 2.2f), y)
        }
        water.lineTo(0f, h); water.close()
        drawPath(water, Brush.horizontalGradient(listOf(deep.copy(alpha = 0.55f), aqua.copy(alpha = 0.42f)), endX = edge.coerceAtLeast(1f)))
    } else {
        val surface = h * (1f - fraction)
        val sway = 4f * density * (1f - fraction * 0.7f)
        water.moveTo(0f, h)
        for (s in 0..steps) {
            val x = w * s / steps
            water.lineTo(x, surface + sway * sin(x / w * TAU * 1.1f + t * 2.2f))
        }
        water.lineTo(w, h); water.close()
        drawPath(water, Brush.verticalGradient(listOf(aqua.copy(alpha = 0.40f), deep.copy(alpha = 0.55f)), startY = surface, endY = h))
    }
}

/**
 * A phone in the rail: a little glass pool, filled when connected and nearly empty when only saved or nearby; the
 * open one with a bright rim, a cable badge for USB.
 */
fun DrawScope.drawPorthole(c: Offset, r: Float, fill: Float, open: Boolean, here: Boolean, t: Float, seed: Int) {
    val inner = r * 0.86f
    if (open) drawCircle(Brush.radialGradient(listOf(aqua.copy(alpha = 0.35f), Color.Transparent), c, r * 1.45f), r * 1.45f, c)
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF0D3260).copy(alpha = 0.75f), Color(0xFF041530).copy(alpha = 0.9f)), startY = c.y - inner, endY = c.y + inner), inner, c)
    val surface = c.y + inner - 2 * inner * fill
    clipPath(Path().apply { addOval(Rect(c, inner)) }) {
        val wave = Path()
        val amplitude = inner * 0.07f
        for (s in 0..20) {
            val u = s / 20f
            val x = c.x - inner + 2 * inner * u
            val y = surface + amplitude * sin(u * TAU + t * 1.8f + seed)
            if (s == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        wave.lineTo(c.x + inner, c.y + inner); wave.lineTo(c.x - inner, c.y + inner); wave.close()
        drawPath(wave, Brush.verticalGradient(listOf(aqua.copy(alpha = if (here || open) 0.9f else 0.45f), Color(0xFF1667B8).copy(alpha = 0.8f), Color(0xFF07285A)),
            startY = surface, endY = c.y + inner))
        drawPath(wave, Color.White.copy(alpha = 0.3f), style = Stroke(1f * density))
    }
    // The rim: brass-bright on the open phone, faint glass elsewhere.
    val rim = if (open) Brush.sweepGradient(listOf(foam, aqua, deep, aqua, foam), c) else Brush.sweepGradient(listOf(foam.copy(alpha = 0.35f), foam.copy(alpha = 0.12f), foam.copy(alpha = 0.35f)), c)
    drawCircle(rim, r * 0.93f, c, style = Stroke(if (open) r * 0.13f else r * 0.07f))
    drawArc(Color.White.copy(alpha = 0.45f), 200f, 60f, false, Offset(c.x - inner * 0.75f, c.y - inner * 0.75f), Size(inner * 1.5f, inner * 1.5f),
        style = Stroke(r * 0.06f, cap = StrokeCap.Round))
}

/** A soap-glass bubble: a thin rim, a faint inside and a highlight. */
fun DrawScope.drawBubble(c: Offset, r: Float, bright: Float) {
    drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.75f to aqua.copy(alpha = 0.08f + 0.06f * bright), 1f to foam.copy(alpha = 0.22f + 0.15f * bright), center = c, radius = r), r, c)
    drawCircle(foam.copy(alpha = 0.35f + 0.35f * bright), r, c, style = Stroke(1.3f * density))
    drawArc(Color.White.copy(alpha = 0.6f), 205f, 55f, false, Offset(c.x - r * 0.72f, c.y - r * 0.72f), Size(r * 1.44f, r * 1.44f),
        style = Stroke(2f * density, cap = StrokeCap.Round))
    drawCircle(Color.White.copy(alpha = 0.55f), r * 0.07f, Offset(c.x + r * 0.42f, c.y - r * 0.5f))
}

/** The wave along the bottom of the surface bar, at `y`. */
fun DrawScope.drawWaveLine(y: Float, t: Float) {
    val w = size.width
    val crest = Path(); val body = Path()
    val amplitude = 2.6f * density
    for (s in 0..80) {
        val x = w * s / 80
        val yy = y + amplitude * sin(x / w * TAU * 2.4f + t * 1.3f) + amplitude * 0.4f * sin(x / w * TAU * 5.3f - t * 1.9f)
        if (s == 0) { crest.moveTo(x, yy); body.moveTo(x, yy) } else { crest.lineTo(x, yy); body.lineTo(x, yy) }
    }
    body.lineTo(w, 0f); body.lineTo(0f, 0f); body.close()
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFF0A3A6B).copy(alpha = 0.25f), Color(0xFF0E4C8A).copy(alpha = 0.45f)), endY = y))
    drawPath(crest, aqua.copy(alpha = 0.18f), style = Stroke(5f * density, cap = StrokeCap.Round))
    drawPath(crest, foam.copy(alpha = 0.55f), style = Stroke(1.2f * density, cap = StrokeCap.Round))
}
