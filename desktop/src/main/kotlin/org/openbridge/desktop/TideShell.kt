package org.openbridge.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import kotlin.math.sin

/*
 * Tide, the harbour: a rail of portholes (one per phone), a surface bar with the depth path and the actions over a
 * gentle wave, capsules for files, and the water at the bottom of the window rising with the queue of transfers,
 * the files moving now floating in it.
 */

/** Springy and buoyant, with a little overshoot; settles at once with less motion. */
private fun <T> buoyant() = if (Platform.reduceMotion) tween<T>(0) else spring<T>(0.5f, Spring.StiffnessLow)

@Composable
fun TideShell(state: AppState, dropping: Boolean, searchFocus: FocusRequester) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 900.dp || maxHeight < 560.dp
        if (state.source != null) {
            val windowHeight = maxHeight
            Row(Modifier.fillMaxSize()) {
                PortholeRail(state, compact)
                TideBrowser(state, dropping, searchFocus, compact, windowHeight, Modifier.weight(1f))
            }
        } else TideConnect(state, compact)
    }
}

@Composable
private fun TideBrowser(state: AppState, dropping: Boolean, searchFocus: FocusRequester, compact: Boolean, windowHeight: Dp, modifier: Modifier) {
    val transfer = state.transfer
    val docked = transfer != null
    Box(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            SurfaceBar(state, searchFocus, compact)
            if (state.update != null || state.updating != null) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) { UpdatePill(state) }
            state.phoneOutdated?.let { Hint(it, Icons.Filled.SystemUpdate, Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) { state.dismissOutdated() } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                FileArea(state, Look.TIDE, Modifier.fillMaxSize(), PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = if (docked) 112.dp else 24.dp))
            }
            ErrorLine(state, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        if (dropping) {
            // The water rose to meet the files: the words float on its surface (the backdrop's surface at this level).
            val surface = windowHeight * (0.80f - 0.24f * 0.16f)
            Text("${S.dropHere} · ${state.path.last().second}", color = Palette.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = surface - 52.dp).clip(CircleShape).background(Palette.night.copy(alpha = 0.6f))
                    .border(1.5.dp, Palette.amber.copy(alpha = 0.7f), CircleShape).padding(horizontal = 20.dp, vertical = 10.dp))
        }
        if (transfer != null) TransferDock(state, transfer, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 16.dp, end = 16.dp))
    }
}

/** One porthole per phone (filled when connected, a cable badge for USB), and Texts and Settings at the bottom. */
@Composable
private fun PortholeRail(state: AppState, compact: Boolean) {
    val width = if (compact) 56.dp else 76.dp
    val port = if (compact) 40.dp else 54.dp
    val time = animationTime()
    Column(Modifier.width(width).fillMaxHeight().background(Palette.night.copy(alpha = 0.38f)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            phoneStars(state).forEachIndexed { index, star -> Porthole(state, star, port, time, index) }
        }
        Spacer(Modifier.height(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.bridge != null) RailButton(Icons.Filled.ChatBubbleOutline, S.texts, port) { state.panel = Panel.TEXTS }
            RailButton(Icons.Filled.Settings, S.settings, port) { state.panel = Panel.SETTINGS }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Porthole(state: AppState, star: PhoneStar, size: Dp, time: Float, seed: Int) {
    var menu by remember { mutableStateOf(false) }
    val saved = (star.reach as? PhoneStar.Reach.Saved)?.phone
    val fill = remember { Animatable(0.08f) }
    LaunchedEffect(star.open, star.here) { fill.animateTo(when { star.open -> 0.68f; star.here -> 0.3f; else -> 0.1f }, buoyant()) }
    Box {
        Tip("${star.name} · ${star.status}") {
            Box(Modifier.size(size).pressable(CircleShape, enabled = !star.open, label = star.name) { reach(state, star) }
                .onPointerEvent(PointerEventType.Press) { event -> if ((event.nativeEvent as? java.awt.event.MouseEvent)?.button == java.awt.event.MouseEvent.BUTTON3 && saved != null) menu = true }
                .semantics { contentDescription = "${star.name}, ${star.status}"; selected = star.open }) {
                Canvas(Modifier.fillMaxSize()) { drawPorthole(center, this.size.minDimension / 2, fill.value, star.open, star.here, time, seed) }
                if (star.cable) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.36f).clip(CircleShape).background(Palette.dusk).border(1.dp, Palette.amber.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(if (star.open && state.transport == Transport.FAST_USB) Icons.Filled.Bolt else Icons.Filled.Usb, null, tint = Palette.amber, modifier = Modifier.fillMaxSize(0.7f))
                }
            }
        }
        if (saved != null) DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ menu = false; confirmForget(state, saved) }) { Text(S.forget) }
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Tip(label) {
        Box(Modifier.size(size * 0.82f).clip(CircleShape).background(Palette.fg.copy(alpha = 0.08f)).border(1.dp, Palette.cream.copy(alpha = 0.22f), CircleShape)
            .pressable(CircleShape, label = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.fg, modifier = Modifier.size(20.dp))
        }
    }
}

/** The surface: the depth path, search and actions, with a gentle wave along its lower edge. */
@Composable
private fun SurfaceBar(state: AppState, searchFocus: FocusRequester, compact: Boolean) {
    val time = animationTime()
    Column(Modifier.fillMaxWidth().drawBehind { drawWaveLine(size.height - 8.dp.toPx(), time) }.padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(Icons.Filled.ArrowUpward, S.up, enabled = state.path.size > 1 && !state.busy) { state.up() }
            DepthPath(state, if (compact) 3 else 4, Modifier.weight(1f))
            TransportChip(state)
            if (!compact) {
                SearchField(state, searchFocus, Modifier.width(230.dp))
                SortButton(state)
                ViewToggle(state)
                MediaToggle(state)
                UploadButton(state)
            }
            MoreButton(state, compact = compact)
            if (state.selection.isNotEmpty()) DownloadButton(state)
        }
        if (compact || (state.query.isNotBlank() && state.results == null)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compact) SearchField(state, searchFocus, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            SearchAllButton(state)
        }
    }
}

/** The path as a dive: each folder a little deeper in tone than the one above it. */
@Composable
private fun DepthPath(state: AppState, room: Int, modifier: Modifier) {
    val path = state.path
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        foldPath(path.size, room).forEachIndexed { position, index ->
            if (position > 0) Text("›", color = Palette.muted.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 2.dp))
            if (index == null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Text("…", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.pressable(CircleShape, label = S.up) { open = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                    DropdownMenu(open, { open = false }) {
                        foldedOut(path.size, room).forEach { hidden -> DropdownMenuItem({ open = false; state.go(hidden) }) { Text(path[hidden].second) } }
                    }
                }
            } else {
                val last = index == path.size - 1
                val depth = if (path.size <= 1) 1f else index.toFloat() / (path.size - 1)
                val tone = if (last) Palette.fg else lerp(Palette.sun, Palette.violet, depth * 0.8f)
                Text(path[index].second, color = tone, fontSize = 14.5.sp, fontWeight = if (last) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = (if (last) Modifier.widthIn(max = 260.dp) else Modifier.weight(1f, fill = false))
                        .clip(CircleShape).background(Palette.amber.copy(alpha = 0.05f + 0.10f * depth))
                        .pressable(CircleShape, enabled = !last && !state.busy, label = path[index].second) { state.go(index) }
                        .padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

/**
 * The files moving now, floating in the water: the current one fills with its progress, the next ones bob behind
 * it, finished ones sink away. Shows only while there's a transfer.
 */
@Composable
private fun TransferDock(state: AppState, transfer: Transfer, modifier: Modifier) {
    val time = animationTime()
    val queue = queueOf(transfer, show = 2)
    val rise = remember { Animatable(if (Platform.reduceMotion) 0f else 80f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, buoyant()) }
    // A file that just finished sinks out of the dock.
    val sinking = remember { mutableStateListOf<Pair<Int, String>>() }
    var previous by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(transfer.index, transfer.name) {
        previous?.let { if (it.first != transfer.index && it.second != "…") sinking += it }
        previous = transfer.index to transfer.name
    }
    Row(modifier.graphicsLayer { translationY = rise.value * density }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center) {
            sinking.toList().forEach { item -> key(item) { SinkingCapsule(item.second) { sinking.remove(item) } } }
        }
        val stopped = stalled(transfer, state.busy)
        val shape = CircleShape
        Row(Modifier.widthIn(max = 560.dp).graphicsLayer { translationY = 1.5f * density * sin(time * 1.4f) }.clip(shape).background(Palette.night.copy(alpha = 0.7f))
            .drawBehind { drawWaterFill(if (transfer.done) 1f else transfer.fraction, time) }
            .border(1.dp, (if (stopped) Palette.pink else Palette.amber).copy(alpha = 0.6f), shape)
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(overallProgress(transfer), 0f..1f) }
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (transfer.upload) Icons.Filled.Upload else Icons.Filled.Download, null, tint = Palette.fg, modifier = Modifier.size(17.dp))
            Text(transfer.name, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (transfer.count > 1 && !transfer.done) Text(S.ofCount(transfer.index, transfer.count), color = Palette.fg.copy(alpha = 0.8f), fontSize = 12.5.sp, maxLines = 1)
            if (stopped) Text(S.paused, color = Palette.pink, fontSize = 12.5.sp, maxLines = 1)
            TransferActions(state, transfer)
        }
        queue.upcoming.forEachIndexed { index, name ->
            Text(name, color = Palette.fg.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.widthIn(max = 150.dp).graphicsLayer { translationY = 2f * density * sin(time * 1.2f + 1.7f * (index + 1)) }
                    .clip(CircleShape).background(Palette.night.copy(alpha = 0.5f)).border(1.dp, Palette.cream.copy(alpha = 0.18f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
        if (queue.more > 0) Text("+${queue.more}", color = Palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun SinkingCapsule(name: String, gone: () -> Unit) {
    val sink = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sink.animateTo(1f, tween(if (Platform.reduceMotion) 0 else 1400)); gone() }
    Text(name, color = Palette.fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.widthIn(max = 150.dp).graphicsLayer { translationY = sink.value * 70f * density; alpha = 1f - sink.value }
            .clip(CircleShape).background(Palette.ok.copy(alpha = 0.25f)).padding(horizontal = 12.dp, vertical = 7.dp))
}

/**
 * The start: the pool in the middle, and the phones rising as bubbles from the water (name inside, slowly bobbing);
 * click one to connect or pair. The code floats above the pool while pairing.
 */
@Composable
private fun TideConnect(state: AppState, compact: Boolean) {
    val time = animationTime()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("OpenBridge", color = Palette.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                if (!compact) Text(S.tagline, color = Palette.muted, fontSize = 13.sp)
            }
            UpdatePill(state)
            IconButton(Icons.Filled.Settings, S.settings) { state.panel = Panel.SETTINGS }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val hero = minOf(maxWidth * 0.42f, maxHeight * 0.95f)
            Hero(active = true, connected = state.pairingCode != null, modifier = Modifier.size(hero).align(Alignment.Center))
            state.pairingCode?.let { code ->
                val bob = 3f * sin(time * 1.3f)
                CodeDigits(code, if (compact) 26.sp else 34.sp, Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = bob * density }
                    .clip(CircleShape).background(Palette.night.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 4.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.pairingCode == null) {
                Text(S.waiting, color = Palette.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(S.waitingHint, color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            ConnectNotes(state, Modifier.padding(top = 2.dp).widthIn(max = 720.dp))
        }
        val stars = phoneStars(state)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
            stars.forEachIndexed { index, star -> Bubble(state, star, if (compact) 96.dp else 112.dp, time, index) }
        }
        AddressStrip(state, Modifier.align(Alignment.CenterHorizontally).padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 18.dp).widthIn(max = 640.dp)
            .clip(CircleShape).background(Palette.night.copy(alpha = 0.62f)).border(1.dp, Palette.cream.copy(alpha = 0.16f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Bubble(state: AppState, star: PhoneStar, size: Dp, time: Float, seed: Int) {
    var menu by remember { mutableStateOf(false) }
    val saved = (star.reach as? PhoneStar.Reach.Saved)?.phone
    val wpd = (star.reach as? PhoneStar.Reach.Wpd)?.phone
    // Bubbles rise into place from the water, one after another.
    val rise = remember { Animatable(if (Platform.reduceMotion) 0f else 160f) }
    LaunchedEffect(Unit) { if (!Platform.reduceMotion) kotlinx.coroutines.delay(120L * seed); rise.animateTo(0f, buoyant()) }
    val bright = when { star.saved && star.here -> 1f; star.here -> 0.75f; star.saved -> 0.55f; else -> 0.3f }
    val action = starAction(star)
    Box {
        Box(Modifier.size(size).graphicsLayer { translationY = (rise.value + 2f * sin(time * 0.9f + seed * 1.9f)) * density }
            .pressable(CircleShape, enabled = !state.busy, label = action.ifEmpty { star.name }) { reach(state, star) }
            .onPointerEvent(PointerEventType.Press) { event -> if ((event.nativeEvent as? java.awt.event.MouseEvent)?.button == java.awt.event.MouseEvent.BUTTON3 && (saved != null || wpd != null)) menu = true }
            .onPreviewKeyEvent { event -> if (saved != null && event.type == KeyEventType.KeyDown && event.key == Key.Delete) { confirmForget(state, saved); true } else false }
            .semantics(mergeDescendants = true) { contentDescription = "${star.name}, ${star.status}" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawBubble(center, this.size.minDimension / 2 - 2.dp.toPx(), bright) }
            Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (star.cable) Icon(Icons.Filled.Usb, null, tint = Palette.amber, modifier = Modifier.size(14.dp))
                Text(star.name, color = Palette.fg.copy(alpha = 0.6f + 0.4f * bright), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (action.isNotEmpty()) Text(action, color = if (star.here) Palette.amber else Palette.muted, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (saved != null) DropdownMenuItem({ menu = false; confirmForget(state, saved) }) { Text(S.forget) }
            if (wpd != null && Platform.windows) DropdownMenuItem({ menu = false; Platform.openThisPc() }) { Text(S.openExplorer) }
        }
    }
}
