package org.openbridge.android

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import org.openbridge.ui.Backdrop
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawHero
import kotlin.math.cos
import kotlin.math.sin

/**
 * The look's hero (Dusk's sun arch, Aurora's halo, Tide's pool), driven by the shared engine. One for the whole app, so
 * the sun keeps its place when the home screen changes layout. Each frame only redraws the canvas; nothing recomposes.
 */
@Stable
class HeroState {
    val engine = SunEngine()
    var frame by mutableStateOf(SunEngine.Frame())
    var tick by mutableLongStateOf(0L)
}

/** Runs the hero while [visible]: every frame normally, a settled still picture when motion is off. */
@Composable
fun rememberHero(active: Boolean, connected: Boolean, visible: Boolean): HeroState {
    val hero = remember { HeroState() }
    val motion = LocalMotion.current
    val haptics = rememberHaptics()
    hero.engine.onConnect = { if (!motion.still) haptics.confirm() }
    val activeNow by rememberUpdatedState(active)
    val connectedNow by rememberUpdatedState(connected)
    when {
        motion.frozen -> LaunchedEffect(active, connected) {
            // A moment a few seconds after the change: mid-orbit while waiting, breathing once connected.
            var frame = SunEngine.Frame()
            repeat(if (connected) 230 else 170) { frame = hero.engine.step(1.0 / 60, active, connected, false) }
            hero.frame = frame
            hero.tick++
        }
        motion.reduced -> LaunchedEffect(active, connected) {
            repeat(90) { hero.frame = hero.engine.step(if (hero.engine.state == SunEngine.State.SCAN) 0.0 else 1.0 / 60, active, connected, true) }
            hero.frame = SunEngine.Frame(glow = if (connected) 0.5f else 0f)
            hero.tick++
        }
        visible -> LaunchedEffect(hero) {
            var last = 0L
            while (true) withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
                last = now
                hero.frame = hero.engine.step(dt, activeNow, connectedNow, false)
                hero.tick = now
            }
        }
    }
    return hero
}

/** The hero as a round button of [size]; glows and ripples spread past its edge. */
@Composable
fun HeroButton(hero: HeroState, size: Dp, label: String, status: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    Box(modifier.size(size).pressScale(press, 0.94f)) {
        Canvas(Modifier.fillMaxSize()) {
            hero.tick   // redraw every frame
            drawHero(hero.engine, hero.frame, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.33f, this.size.minDimension * 1.1f)
        }
        Box(Modifier.fillMaxSize().clip(CircleShape)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = label) { haptics.heavy(); onTap() }
            .semantics { contentDescription = label; stateDescription = status })
    }
}

/**
 * The look's backdrop with soft drifting light on top, brighter while sharing ([energy]); Tide's water rises with
 * [level]. The light moves in the draw phase only.
 */
@Composable
fun AppBackdrop(energy: Float, level: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val motion = LocalMotion.current
    val drift = if (motion.still) null else {
        val transition = rememberInfiniteTransition(label = "drift")
        transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    }
    Backdrop(modifier, energy = energy, level = level, still = motion.still) {
        Canvas(Modifier.fillMaxSize()) {
            val t = drift?.value ?: 1.3f
            val w = size.width; val h = size.height
            val boost = 0.7f + 0.3f * energy
            val a = Offset(w * (0.85f + 0.06f * cos(t)), h * (0.12f + 0.04f * sin(t * 2)))
            drawCircle(Brush.radialGradient(listOf(Palette.pink.copy(alpha = 0.22f * boost), Color.Transparent), center = a, radius = w * 0.75f), w * 0.75f, a)
            val b = Offset(w * (0.1f + 0.08f * sin(t)), h * (0.45f + 0.05f * cos(t)))
            drawCircle(Brush.radialGradient(listOf(Palette.violet.copy(alpha = 0.20f * boost), Color.Transparent), center = b, radius = w * 0.8f), w * 0.8f, b)
            val c = Offset(w * (0.7f + 0.05f * sin(t * 1.5f)), h * (0.92f))
            drawCircle(Brush.radialGradient(listOf(Palette.amber.copy(alpha = 0.10f * boost), Color.Transparent), center = c, radius = w * 0.7f), w * 0.7f, c)
        }
        content()
    }
}
