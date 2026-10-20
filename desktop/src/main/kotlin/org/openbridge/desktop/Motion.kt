package org.openbridge.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * Seconds since this first drew, about 30 times a second, for the looks' small motions (twinkling stars, bobbing
 * capsules, waves). Stands still with less motion: everything is drawn at one moment instead.
 */
@Composable
fun animationTime(): Float {
    var time by remember { mutableFloatStateOf(3f) }
    if (!Platform.reduceMotion) LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last == 0L) last = now
            if (now - last >= 33_000_000L) { time += ((now - last) / 1e9f).coerceAtMost(0.1f); last = now }
        }
    }
    return time
}

/** Aurora's arrival: fades in while drifting up a few pixels, slowly, never bouncing. */
fun Modifier.driftIn(distance: Float = 8f, millis: Int = 520) = composed {
    val still = Platform.reduceMotion
    val shown = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { launch { shown.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) } }
    graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * distance * density }
}
