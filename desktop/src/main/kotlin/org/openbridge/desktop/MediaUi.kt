package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.openbridge.ui.Look
import org.openbridge.ui.Palette

/*
 * What plays on the phone (1.3.0): a card in the tray's status window and the phone's sheet, a slim bar in the main
 * window. The position advances here between the phone's events; the bar seeks when the playing app allows it.
 * Every line of text stays on one line, ending in an ellipsis.
 */

/** The position now, ticking twice a second while it plays. */
@Composable
private fun livePosition(media: PhoneMedia): Long {
    var now by remember(media) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(media) { while (media.playing) { delay(500); now = System.currentTimeMillis() } }
    return media.positionAt(now)
}

/** The bar of how far it is, in the look's way; tap or drag to seek when `seek` is given. */
@Composable
private fun MediaProgress(media: PhoneMedia, position: Long, seek: ((Long) -> Unit)?, modifier: Modifier) {
    val fraction = if (media.duration > 0 && position >= 0) (position.toFloat() / media.duration).coerceIn(0f, 1f) else 0f
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: fraction
    val time = if (Palette.look == Look.TIDE) animationTime() else 0f
    val look = Palette.look
    var width by remember { mutableIntStateOf(1) }
    Canvas(modifier.fillMaxWidth().height(if (look == Look.TIDE) 12.dp else 14.dp)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f); if (seek == null) disabled() }
        .then(if (seek != null) Modifier.pointerInput(media.duration) {
            detectTapGestures { seek(((it.x / size.width).coerceIn(0f, 1f) * media.duration).toLong()) }
        }.pointerInput(media.duration) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { dragging?.let { seek((it * media.duration).toLong()) }; dragging = null },
                onDragCancel = { dragging = null },
            ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
        } else Modifier)) {
        width = size.width.toInt()
        val h = if (look == Look.AURORA) 2.dp.toPx() else if (look == Look.TIDE) size.height else 5.dp.toPx()
        val top = (size.height - h) / 2
        val filled = size.width * shown
        when (look) {
            Look.AURORA -> {
                drawRect(Palette.fg.copy(alpha = 0.16f), Offset(0f, top), Size(size.width, h))
                drawRect(accentBrush(size.width), Offset(0f, top), Size(filled, h))
                val tip = Offset(filled, size.height / 2)
                drawCircle(Brush.radialGradient(listOf(Palette.sun, Palette.amber.copy(alpha = 0.4f), Color.Transparent), tip, 7.dp.toPx()), 7.dp.toPx(), tip)
            }
            Look.TIDE -> {
                drawRoundRect(Palette.night.copy(alpha = 0.6f), cornerRadius = CornerRadius(h / 2))
                clipPath(androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(h / 2)))
                }) { drawWaterFill(shown, time) }
                drawRoundRect(Palette.cream.copy(alpha = 0.25f), cornerRadius = CornerRadius(h / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            Look.DUSK -> {
                drawRoundRect(Palette.fg.copy(alpha = 0.12f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(accentBrush(filled.coerceAtLeast(1f)), Offset(0f, top), Size(maxOf(h, filled), h), CornerRadius(h / 2))
                if (seek != null) drawCircle(Palette.cream, 5.dp.toPx(), Offset(filled.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), size.height / 2))
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, label: String, enabled: Boolean, size: Dp, primary: Boolean = false, onClick: () -> Unit) {
    Tip(label) {
        val shape = CircleShape
        Box(Modifier.size(size).clip(shape)
            .then(if (primary) Modifier.background(Palette.sunset, alpha = if (enabled) 1f else 0.4f) else Modifier.background(Palette.fg.copy(alpha = 0.07f)))
            .pressable(shape, enabled, label = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = when { primary -> Color.White; enabled -> Palette.fg; else -> Palette.muted.copy(alpha = 0.5f) }, modifier = Modifier.size(size * 0.58f))
        }
    }
}

/** Previous, play or pause, next: each only when the playing app offers it. */
@Composable
private fun MediaButtons(media: PhoneMedia, act: (MediaAction) -> Unit, size: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaButton(Icons.Filled.SkipPrevious, S.previousTrack, media.canPrevious, size * 0.8f) { act(MediaAction.PREVIOUS) }
        MediaButton(if (media.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (media.playing) S.pause else S.play, media.canPlayPause, size, primary = true) {
            act(MediaAction.PLAY_PAUSE)
        }
        MediaButton(Icons.Filled.SkipNext, S.nextTrack, media.canNext, size * 0.8f) { act(MediaAction.NEXT) }
    }
}

/** The card's surface in each look. */
private fun Modifier.mediaSurface(shape: androidx.compose.ui.graphics.Shape): Modifier = when (Palette.look) {
    Look.AURORA -> this.auroraGlow(false, false, shape, resting = true)
    Look.TIDE -> this.clip(shape).background(Palette.night.copy(alpha = 0.5f)).border(1.dp, Palette.cream.copy(alpha = 0.2f), shape)
    Look.DUSK -> this.glass(shape)
}

/**
 * "Now playing", as a card: the app, the title and the artist on a line each, the bar with the times, and the
 * buttons. For the tray's status window and the phone's sheet.
 */
@Composable
fun NowPlayingCard(media: PhoneMedia, act: (MediaAction, Long) -> Unit, modifier: Modifier = Modifier) {
    val position = livePosition(media)
    val shape = RoundedCornerShape(if (Palette.look == Look.TIDE) 22.dp else 14.dp)
    val described = listOf(S.nowPlaying, media.title, media.artist, media.app).filter { it.isNotBlank() }.joinToString(", ")
    Column(modifier.fillMaxWidth().mediaSurface(shape).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, null, tint = Palette.amber, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text((S.nowPlaying + if (media.app.isNotBlank()) " · ${media.app}" else "").uppercase(), color = Palette.muted, fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = described }) {
            Text(media.title.ifBlank { media.app }, color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis)
            if (media.artist.isNotBlank()) Text(media.artist, color = Palette.muted, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        if (media.duration > 0) MediaProgress(media, position, if (media.canSeek) { at -> act(MediaAction.SEEK, at) } else null, Modifier)
        // The times at the ends of the bar, the buttons between them.
        Box(Modifier.fillMaxWidth()) {
            if (media.duration > 0) {
                Text(clock(position), color = Palette.muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart))
                Text(clock(media.duration), color = Palette.muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd))
            }
            Box(Modifier.align(Alignment.Center)) { MediaButtons(media, { act(it, 0) }, 34.dp) }
        }
    }
}

/** "Now playing" in the main window: one slim line with the title, the bar and the buttons. */
@Composable
fun NowPlayingBar(state: AppState, modifier: Modifier = Modifier) = BoxWithConstraints(modifier) {
    val media = state.media ?: return@BoxWithConstraints
    // A narrow window gives the title the room and keeps the bar short.
    val bar = if (maxWidth < 760.dp) 70.dp else 150.dp
    val position = livePosition(media)
    val shape = if (Palette.look == Look.DUSK) RoundedCornerShape(12.dp) else CircleShape
    val described = listOf(S.nowPlaying, media.title, media.artist, media.app).filter { it.isNotBlank() }.joinToString(", ")
    Row(Modifier.fillMaxWidth().mediaSurface(shape).padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.MusicNote, null, tint = Palette.amber, modifier = Modifier.size(16.dp))
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = described }, verticalAlignment = Alignment.CenterVertically) {
            Text(media.title.ifBlank { media.app }, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            val rest = listOf(media.artist, media.app).filter { it.isNotBlank() }.joinToString(" · ")
            if (rest.isNotEmpty()) Text("  $rest", color = Palette.muted, fontSize = 12.5.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (media.duration > 0) {
            Text(clock(position), color = Palette.muted, fontSize = 11.5.sp, maxLines = 1, softWrap = false)
            MediaProgress(media, position, if (media.canSeek) { at -> state.mediaAction(MediaAction.SEEK, at) } else null, Modifier.width(bar))
            Text(clock(media.duration), color = Palette.muted, fontSize = 11.5.sp, maxLines = 1, softWrap = false)
        }
        MediaButtons(media, { state.mediaAction(it) }, 30.dp)
    }
}

/** The card for the open phone, when something plays on it. */
@Composable
fun NowPlayingCard(state: AppState, modifier: Modifier = Modifier) {
    val media = state.media ?: return
    NowPlayingCard(media, { action, at -> state.mediaAction(action, at) }, modifier)
}
