package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Aurora, the observatory: no sidebar. The sky is the frame: a header with the open phone's halo and the
 * constellation of phones, a horizon carrying the folder path and the comet of transfers, and one frosted sheet of
 * files floating over the sky.
 */

@Composable
fun AuroraShell(state: AppState, dropping: Boolean, searchFocus: FocusRequester) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 900.dp || maxHeight < 560.dp
        if (state.source != null) AuroraBrowser(state, dropping, searchFocus, compact) else AuroraConnect(state, compact)
    }
}

@Composable
private fun AuroraBrowser(state: AppState, dropping: Boolean, searchFocus: FocusRequester, compact: Boolean) {
    Column(Modifier.fillMaxSize()) {
        SkyHeader(state, compact)
        if (compact && (state.update != null || state.updating != null))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) { UpdatePill(state) }
        Horizon(state, compact)
        state.phoneOutdated?.let { Hint(it, Icons.Filled.SystemUpdate, Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp)) { state.dismissOutdated() } }
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp).floatingSheet()) {
            SheetToolbar(state, searchFocus, compact)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.fg.copy(alpha = 0.12f), Color.Transparent))))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Each folder arrives softly: a crossfade drifting up a few pixels.
                key(state.path.last().first, state.results != null) {
                    FileArea(state, Look.AURORA, Modifier.fillMaxSize().driftIn(), PaddingValues(horizontal = 18.dp, vertical = 16.dp))
                }
                if (dropping) {
                    Box(Modifier.fillMaxSize().padding(12.dp).auroraGlow(true, false, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("${S.dropHere} · ${state.path.last().second}", color = Palette.fg, fontSize = 16.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
                    }
                }
            }
            ErrorLine(state, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

/** The frosted sheet the files float on: translucent night, a faint mint light along its top edge. */
private fun Modifier.floatingSheet() = composed {
    val shape = RoundedCornerShape(20.dp)
    clip(shape).background(Brush.verticalGradient(listOf(Palette.dusk.copy(alpha = 0.58f), Palette.night.copy(alpha = 0.74f))))
        .border(1.dp, Brush.verticalGradient(listOf(Palette.amber.copy(alpha = 0.38f), Palette.fg.copy(alpha = 0.07f), Palette.fg.copy(alpha = 0.04f))), shape)
}

/** The open phone in the sky: its halo, its name and how it's connected; the constellation of phones; Texts, Mirror, Settings. */
@Composable
private fun SkyHeader(state: AppState, compact: Boolean) {
    val transfer = state.transfer
    val moving = transfer != null && !transfer.done && state.busy
    Row(Modifier.fillMaxWidth().height(if (compact) 88.dp else 150.dp).padding(start = 22.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderHalo(moving, Modifier.size(if (compact) 56.dp else 90.dp).semantics { contentDescription = state.deviceName })
        Spacer(Modifier.width(if (compact) 12.dp else 18.dp))
        Column(Modifier.weight(1f)) {
            Text(state.deviceName, color = Palette.fg, fontSize = if (compact) 22.sp else 32.sp, fontWeight = FontWeight.Light, letterSpacing = if (compact) 1.sp else 2.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TransportChip(state)
                    val line = when {
                        moving && transfer != null -> (if (transfer.count > 1) S.ofCount(transfer.index, transfer.count) + " · " else "") + transfer.name
                        state.busy && state.status.isNotEmpty() -> state.status
                        else -> null
                    }
                    if (line != null) Text(line, color = Palette.muted, fontSize = 13.sp, letterSpacing = 0.4.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constellation(state, compact)
                Spacer(Modifier.width(8.dp))
                if (state.bridge != null) IconButton(Icons.Filled.ChatBubbleOutline, S.texts) { state.panel = Panel.TEXTS }
                IconButton(Icons.Filled.ScreenShare, S.mirror, enabled = !state.mirroring) { state.mirror() }
                IconButton(Icons.Filled.Settings, S.settings) { state.panel = Panel.SETTINGS }
            }
            if (!compact) UpdatePill(state)
        }
    }
}

/** One small orb per phone; the open one brighter with a ring. Narrow windows get one orb that opens a menu. */
@Composable
private fun Constellation(state: AppState, compact: Boolean) {
    val stars = phoneStars(state)
    val time = animationTime()
    if (compact || stars.size > 6) {
        var open by remember { mutableStateOf(false) }
        Box {
            val here = stars.firstOrNull { it.open }
            Tip(S.phones) {
                Box(Modifier.size(34.dp).pressable(CircleShape, label = S.phones) { open = true }.semantics { contentDescription = S.phones }) {
                    Canvas(Modifier.fillMaxSize()) { drawOrb(center, size.minDimension * 0.42f, true, true, here?.cable == true, time, 1) }
                }
            }
            DropdownMenu(open, { open = false }) {
                stars.forEach { star ->
                    DropdownMenuItem({ open = false; reach(state, star) }, enabled = !star.open) {
                        Text((if (star.open) "● " else "") + star.name + if (star.status.isNotEmpty()) "  ·  ${star.status}" else "", maxLines = 1)
                    }
                }
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        stars.forEachIndexed { index, star -> Orb(state, star, time, index) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Orb(state: AppState, star: PhoneStar, time: Float, seed: Int) {
    var menu by remember { mutableStateOf(false) }
    val saved = (star.reach as? PhoneStar.Reach.Saved)?.phone
    Box {
        Tip("${star.name} · ${star.status}") {
            Box(Modifier.size(30.dp).pressable(CircleShape, enabled = !star.open, label = star.name) { reach(state, star) }
                .onPointerEvent(PointerEventType.Press) { event -> if ((event.nativeEvent as? java.awt.event.MouseEvent)?.button == java.awt.event.MouseEvent.BUTTON3 && saved != null) menu = true }
                .semantics { contentDescription = "${star.name}, ${star.status}"; selected = star.open }) {
                Canvas(Modifier.fillMaxSize()) { drawOrb(center, size.minDimension * 0.36f, star.open, star.here, star.cable, time, seed) }
            }
        }
        if (saved != null) DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ menu = false; confirmForget(state, saved) }) { Text(S.forget) }
        }
    }
}

/**
 * The horizon under the header: the folder path as a constellation (a star at each folder, fine lines between),
 * and the comet that carries the queue of transfers from left to right.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Horizon(state: AppState, compact: Boolean) {
    val transfer = state.transfer
    var popover by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val time = animationTime()
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 24.dp).onSizeChanged { width = it.width }) {
        val progress = transfer?.let(::overallProgress) ?: 0f
        val stopped = transfer != null && stalled(transfer, state.busy)
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height - 3.dp.toPx()
            drawHorizon(y)
            if (transfer != null && !transfer.done) drawComet(size.width * progress, y, time, stopped)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ConstellationPath(state, if (compact) 3 else 6, Modifier.weight(1f))
            if (transfer != null) TransferPill(state, transfer, progress) { popover = !popover }
        }
        if (transfer != null && !transfer.done) {
            // Hovering or clicking the comet opens the queue.
            val x = with(density) { (width * progress).toDp() } - 18.dp
            Box(Modifier.offset(x = x.coerceAtLeast(0.dp), y = 26.dp).size(36.dp, 20.dp)
                .onPointerEvent(PointerEventType.Enter) { popover = true }
                .pressable(CircleShape, label = S.transfers) { popover = !popover })
        }
        if (popover && transfer != null) {
            val shift = with(density) { (width * progress).coerceAtMost(width - 340.dp.toPx()).coerceAtLeast(0f).roundToInt() }
            Popup(offset = IntOffset(shift, with(density) { 48.dp.roundToPx() }), onDismissRequest = { popover = false }) {
                QueueCard(state, transfer, Modifier.onPointerEvent(PointerEventType.Exit) { popover = false })
            }
        }
    }
}

@Composable
private fun ConstellationPath(state: AppState, room: Int, modifier: Modifier) {
    val path = state.path
    val time = animationTime()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        foldPath(path.size, room).forEachIndexed { position, index ->
            if (position > 0) Box(Modifier.width(22.dp).height(1.dp).background(Palette.fg.copy(alpha = 0.28f)))
            if (index == null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Text("…", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.pressable(RoundedCornerShape(6.dp), label = S.up) { open = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
                    DropdownMenu(open, { open = false }) {
                        foldedOut(path.size, room).forEach { hidden ->
                            DropdownMenuItem({ open = false; state.go(hidden) }) { Text(path[hidden].second) }
                        }
                    }
                }
            } else {
                val last = index == path.size - 1
                Row(Modifier.then(if (last) Modifier.widthIn(max = 300.dp) else Modifier.weight(1f, fill = false))
                    .pressable(RoundedCornerShape(8.dp), enabled = !last && !state.busy, label = path[index].second) { state.go(index) }
                    .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) {
                        drawStar(center, size.minDimension * 0.24f, Palette.amber, if (last) 1f else 0.45f, if (last) 0.6f + 0.4f * sin(time * 1.3f) else 0.3f)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(path[index].second, color = if (last) Palette.fg else Palette.muted, fontSize = 13.5.sp, letterSpacing = 0.6.sp,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** At the end of the horizon while a transfer exists: how far it is, or Resume, or Saved. Opens the queue. */
@Composable
private fun TransferPill(state: AppState, transfer: Transfer, progress: Float, toggle: () -> Unit) {
    val stopped = stalled(transfer, state.busy)
    Row(Modifier.clip(CircleShape).background(Palette.night.copy(alpha = 0.6f)).border(1.dp, (if (stopped) auroraWarning else Palette.amber).copy(alpha = 0.35f), CircleShape)
        .padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.pressable(CircleShape, label = S.transfers, onClick = toggle).padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (transfer.upload) Icons.Filled.Upload else Icons.Filled.Download, null, tint = if (stopped) auroraWarning else Palette.amber, modifier = Modifier.size(15.dp))
            Text(when {
                transfer.done -> if (transfer.count > 1) S.doneCount(transfer.count) else transfer.name
                stopped -> S.paused
                else -> "${(progress * 100).toInt()}%" + if (transfer.count > 1) " · ${S.ofCount(transfer.index, transfer.count)}" else ""
            }, color = Palette.fg, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        TransferActions(state, transfer, percent = false)
    }
}

/** The queue: what's done, the file moving now with its progress, what comes next. */
@Composable
fun QueueCard(state: AppState, transfer: Transfer, modifier: Modifier = Modifier) {
    val queue = queueOf(transfer, show = 4)
    Column(modifier.width(340.dp).clip(RoundedCornerShape(16.dp)).background(Palette.dusk.copy(alpha = 0.97f)).border(1.dp, Palette.amber.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(S.transfers, color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            if (queue.finished > 0) Text(S.doneCount(queue.finished), color = Palette.muted, fontSize = 12.sp)
        }
        Text(queue.current, color = Palette.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(queue.fraction.coerceAtLeast(0.02f)).background(accentBrush(1000f)))
        }
        queue.upcoming.forEach { Text(it, color = Palette.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
        if (queue.more > 0) Text(S.andMore(queue.more), color = Palette.muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TransferActions(state, transfer)
        }
    }
}

/** The slim toolbar along the sheet's top edge. */
@Composable
private fun SheetToolbar(state: AppState, searchFocus: FocusRequester, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(Icons.Filled.ArrowUpward, S.up, enabled = state.path.size > 1 && !state.busy) { state.up() }
        SearchField(state, searchFocus, Modifier.weight(1f))
        SearchAllButton(state)
        if (!compact) {
            SortButton(state)
            ViewToggle(state)
            MediaToggle(state)
            UploadButton(state)
            NewFolderButton(state)
        }
        MoreButton(state, compact = compact, withUpload = false)
        if (state.selection.isNotEmpty()) DownloadButton(state)
    }
}

/**
 * The start: a big halo in the middle of the sky, and the phones around it as stars (saved ones brighter); click
 * one to connect or pair. The code shows large inside the halo while pairing.
 */
@Composable
private fun AuroraConnect(state: AppState, compact: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("OpenBridge", color = Palette.fg, fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp, modifier = Modifier.semantics { heading() })
                if (!compact) Text(S.tagline, color = Palette.muted, fontSize = 12.5.sp, letterSpacing = 0.4.sp)
            }
            UpdatePill(state)
            IconButton(Icons.Filled.Settings, S.settings) { state.panel = Panel.SETTINGS }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val stars = phoneStars(state)
            val time = animationTime()
            val hero = minOf(maxWidth * 0.46f, maxHeight * 0.92f)
            Hero(active = true, connected = state.pairingCode != null, modifier = Modifier.size(hero).align(Alignment.Center))
            state.pairingCode?.let { CodeDigits(it, if (compact) 28.sp else 36.sp, Modifier.align(Alignment.Center)) }
            // Stars in a loose ring: first to the sides, then above and below, never on the halo.
            val rx = minOf(maxWidth / 2 - 84.dp, hero * 0.92f)
            val ry = minOf(maxHeight / 2 - 44.dp, hero * 0.5f)
            stars.forEachIndexed { index, star ->
                val angle = Math.toRadians((ringAngles[index % ringAngles.size] + (hashOf(star.key) - 0.5f) * 10f).toDouble())
                val x = rx * cos(angle).toFloat()
                val y = ry * sin(angle).toFloat()
                StarNode(state, star, time, index, Modifier.align(Alignment.Center).offset(x, y))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.pairingCode == null) {
                Text(S.waiting, color = Palette.fg, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = 1.5.sp)
                Text(S.waitingHint, color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            ConnectNotes(state, Modifier.padding(top = 4.dp).widthIn(max = 720.dp))
        }
        AddressStrip(state, Modifier.align(Alignment.CenterHorizontally).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp).widthIn(max = 640.dp)
            .clip(CircleShape).background(Palette.night.copy(alpha = 0.55f)).border(1.dp, Palette.fg.copy(alpha = 0.10f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** Where the stars sit around the halo, in degrees (0 to the right, clockwise). */
private val ringAngles = listOf(200f, -20f, 160f, 20f, 235f, -55f, 125f, 55f, 270f, 90f)

private fun hashOf(key: String) = ((key.hashCode() and 0x7fffffff) % 1000) / 1000f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StarNode(state: AppState, star: PhoneStar, time: Float, seed: Int, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val saved = (star.reach as? PhoneStar.Reach.Saved)?.phone
    val wpd = (star.reach as? PhoneStar.Reach.Wpd)?.phone
    val bright = when { star.saved && star.here -> 1f; star.saved -> 0.75f; star.here -> 0.6f; else -> 0.3f }
    val action = starAction(star)
    Box(modifier) {
        Column(Modifier.width(150.dp).pressable(RoundedCornerShape(14.dp), enabled = !state.busy, label = action.ifEmpty { star.name }) { reach(state, star) }
            .onPointerEvent(PointerEventType.Press) { event -> if ((event.nativeEvent as? java.awt.event.MouseEvent)?.button == java.awt.event.MouseEvent.BUTTON3 && (saved != null || wpd != null)) menu = true }
            .onPreviewKeyEvent { event -> if (saved != null && event.type == KeyEventType.KeyDown && event.key == Key.Delete) { confirmForget(state, saved); true } else false }
            .semantics(mergeDescendants = true) { contentDescription = "${star.name}, ${star.status}" }
            .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.BottomEnd) {
                val color = if (star.cable) Palette.violet else Palette.amber
                Canvas(Modifier.fillMaxSize()) { drawStar(center, size.minDimension * 0.17f * (0.8f + 0.3f * bright), color, bright, 0.5f + 0.5f * sin(time * (0.9f + seed * 0.17f) + seed)) }
                if (star.cable) Icon(Icons.Filled.Usb, null, tint = Palette.muted, modifier = Modifier.size(12.dp))
            }
            Text(star.name, color = Palette.fg.copy(alpha = 0.55f + 0.45f * bright), fontSize = 13.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (action.isNotEmpty()) Text(action, color = if (star.here) Palette.amber else Palette.muted, fontSize = 11.5.sp, maxLines = 1)
        }
        DropdownMenu(menu, { menu = false }) {
            if (saved != null) DropdownMenuItem({ menu = false; confirmForget(state, saved) }) { Text(S.forget) }
            if (wpd != null && Platform.windows) DropdownMenuItem({ menu = false; Platform.openThisPc() }) { Text(S.openExplorer) }
        }
    }
}
