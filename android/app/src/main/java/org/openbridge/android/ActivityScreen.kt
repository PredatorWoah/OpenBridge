package org.openbridge.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openbridge.ui.Palette
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Activity: what moved between the phone and computers, newest first and grouped by day. Photos and videos show a
 * thumbnail; received files and links open on tap; a row swipes away (or, with a screen reader, has Remove).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(model: AppModel, actions: Actions, padding: PaddingValues) {
    val events = model.events
    val outbox = model.outbox
    val now = model.now
    val first = rememberFirstShow()
    val groups = remember(events, now / 3_600_000) { events.groupBy { dayLabel(it.time, now) }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "title") {
            ScreenTitle(S.tabActivity, subtitle = if (events.isEmpty()) null else S.swipeToRemove) {
                if (events.isNotEmpty()) IconAction(Icons.Rounded.DeleteSweep, S.clearActivity, actions::clearEvents, tint = Palette.muted)
            }
        }
        if (outbox.items.isNotEmpty()) item(key = "outbox") {
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(S.sendingNow)
                OutboxCard(outbox, model.share.sharing, actions)
            }
        }
        if (events.isEmpty()) item(key = "empty") {
            EmptyState(S.activityEmpty, S.activityEmptyDetail, Icons.Rounded.Bolt, Modifier.animateItem())
        }
        var index = 0
        for ((day, list) in groups) {
            stickyHeader(key = "day-$day") {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(day, style = Type.label.copy(fontWeight = FontWeight.Bold), color = Palette.fg,
                        modifier = Modifier.glass(CircleShape, strong = true).padding(horizontal = 14.dp, vertical = 6.dp).semantics { heading() })
                }
            }
            val base = index
            index += list.size
            itemsIndexed(list, key = { _, event -> event.id }) { i, event ->
                SwipeRow(event, now, actions, Modifier.animateItem().entrance(base + i, first))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(event: Feed.Event, now: Long, actions: Actions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) { haptics.confirm(); actions.removeEvent(event.id); true } else false
    }, positionalThreshold = { it * 0.35f })
    SwipeToDismissBox(state, modifier = modifier.fillMaxWidth().semantics {
        customActions = listOf(CustomAccessibilityAction(S.remove) { actions.removeEvent(event.id); true })
    }, backgroundContent = {
        val active = state.targetValue != SwipeToDismissBoxValue.Settled
        val tint by animateColorAsState(if (active) Palette.pink else Palette.pink.copy(alpha = 0.35f), label = "swipe")
        val grow by animateFloatAsState(if (active) 1.2f else 0.9f, bouncy(), label = "grow")
        // Only while a row is moving: at rest the glass row shows nothing behind it.
        if (state.dismissDirection != SwipeToDismissBoxValue.Settled) Box(Modifier.fillMaxSize().clip(tileShape).background(tint.copy(alpha = 0.25f)).padding(horizontal = 24.dp),
            contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart) {
            Icon(Icons.Rounded.Delete, null, tint = tint, modifier = Modifier.graphicsLayer { scaleX = grow; scaleY = grow })
        }
    }) {
        EventRow(event, now, actions, Modifier.glass(tileShape))
    }
}

/** One event: an icon (or the photo itself), what happened, with whom, and when. */
@Composable
fun EventRow(event: Feed.Event, now: Long, actions: Actions, modifier: Modifier = Modifier) {
    val opens = (event.kind == Feed.Kind.FILE_RECEIVED && event.location != null) || event.kind == Feed.Kind.LINK
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .then(if (opens) Modifier.clickable(role = Role.Button, onClickLabel = S.open) { haptics.tap(); actions.openEvent(event) } else Modifier)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        EventIcon(event, actions)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(eventTitle(event), style = Type.bodyStrong, color = Palette.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            eventDetail(event)?.let { Text(it, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Text(ago(event.time, now), style = Type.caption, color = Palette.muted)
    }
}

private val previewable = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "mp4", "mov", "mkv", "3gp", "webm", "m4v")

@Composable
private fun EventIcon(event: Feed.Event, actions: Actions) {
    val location = event.location
    val photo = location != null && event.name.substringAfterLast('.', "").lowercase() in previewable
    var thumbnail by remember(location) { mutableStateOf<ImageBitmap?>(null) }
    if (photo) LaunchedEffect(location) { thumbnail = actions.thumbnail(location!!) }
    val shown = thumbnail
    if (shown != null) Image(shown, null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.fg.copy(alpha = 0.2f), RoundedCornerShape(12.dp)))
    else {
        val (icon, tint) = eventIcon(event)
        IconBadge(icon, tint, 44.dp)
    }
}

private fun eventIcon(event: Feed.Event): Pair<ImageVector, Color> = when (event.kind) {
    Feed.Kind.FILE_SENT -> Icons.Rounded.Upload to Palette.amber
    Feed.Kind.FILE_RECEIVED -> fileIcon(event.name).takeIf { it != Icons.AutoMirrored.Rounded.InsertDriveFile }?.let { it to Palette.ok }
        ?: (Icons.Rounded.Download to Palette.ok)
    Feed.Kind.TEXT_SENT -> Icons.AutoMirrored.Rounded.Notes to Palette.violet
    Feed.Kind.TEXT_RECEIVED -> Icons.AutoMirrored.Rounded.Notes to Palette.pink
    Feed.Kind.LINK -> Icons.Rounded.Link to Palette.violet
    Feed.Kind.PAIRED -> Icons.Rounded.VerifiedUser to Palette.ok
    Feed.Kind.CONNECTED -> Icons.Rounded.Laptop to Palette.ok
    Feed.Kind.RING -> Icons.Rounded.NotificationsActive to Palette.pink
}

/** An icon for a file by its extension. */
fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng" -> Icons.Rounded.Image
    "mp4", "mov", "mkv", "3gp", "webm", "m4v", "avi" -> Icons.Rounded.Movie
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> Icons.Rounded.Audiotrack
    "pdf" -> Icons.Rounded.PictureAsPdf
    "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

fun eventTitle(event: Feed.Event): String = when (event.kind) {
    Feed.Kind.FILE_SENT -> S.eventFileSent(event.name)
    Feed.Kind.FILE_RECEIVED -> S.eventFileReceived(event.name)
    Feed.Kind.TEXT_SENT -> S.eventTextSent
    Feed.Kind.TEXT_RECEIVED -> S.eventTextReceived(event.computer)
    Feed.Kind.LINK -> S.eventLink(event.computer)
    Feed.Kind.PAIRED -> S.eventPaired(event.computer)
    Feed.Kind.CONNECTED -> S.eventConnected(event.computer)
    Feed.Kind.RING -> S.eventRing(event.computer)
}

fun eventDetail(event: Feed.Event): String? = when (event.kind) {
    Feed.Kind.FILE_SENT -> S.eventTo(event.computer)
    Feed.Kind.FILE_RECEIVED -> S.eventFrom(event.computer)
    Feed.Kind.TEXT_SENT, Feed.Kind.TEXT_RECEIVED -> event.name
    Feed.Kind.LINK -> event.name.removePrefix("https://").removePrefix("http://")
    Feed.Kind.PAIRED -> S.eventPairedDetail
    Feed.Kind.CONNECTED -> runCatching { S.via(Via.valueOf(event.name)) }.getOrNull()
    Feed.Kind.RING -> S.eventRingDetail
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Today, Yesterday, or the date in the phone's language. */
fun dayLabel(time: Long, now: Long): String {
    val today = startOfDay(now)
    val day = startOfDay(time)
    return when {
        day >= today -> S.today
        day >= startOfDay(today - 1) -> S.yesterday
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(time))
    }
}

/** Just now, minutes or hours ago, then the time of day (the day is in the group's heading). */
fun ago(time: Long, now: Long): String {
    val seconds = (now - time) / 1000
    return when {
        seconds < 60 -> S.justNow
        seconds < 3600 -> S.minutesAgo(seconds / 60)
        seconds < 6 * 3600 && startOfDay(time) == startOfDay(now) -> S.hoursAgo(seconds / 3600)
        else -> DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(time))
    }
}

