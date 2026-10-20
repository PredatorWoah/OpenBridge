package org.openbridge.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

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

/** Waits `millis` on the frame clock (so a staggered entrance keeps step with the animations after it). */
suspend fun pauseFrames(millis: Long) {
    val start = withFrameNanos { it }
    while (withFrameNanos { it } - start < millis * 1_000_000L) { /* next frame */ }
}

/** Aurora's arrival: fades in while drifting up a few pixels, slowly, never bouncing. */
fun Modifier.driftIn(distance: Float = 8f, millis: Int = 520) = composed {
    var arrived by remember { mutableStateOf(Platform.reduceMotion) }
    LaunchedEffect(Unit) { arrived = true }
    val shown by animateFloatAsState(if (arrived) 1f else 0f, tween(if (Platform.reduceMotion) 0 else millis, easing = FastOutSlowInEasing))
    graphicsLayer { alpha = shown; translationY = (1f - shown) * distance * density }
}
