package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Palette

@Composable
fun BrowserScreen(state: AppState, dropping: Boolean, searchFocus: FocusRequester) {
    Column(Modifier.fillMaxSize()) {
        Toolbar(state)
        FilterBar(state, searchFocus)
        if (state.update != null || state.updating != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) { UpdatePill(state) }
        state.phoneOutdated?.let { Hint(it, Icons.Filled.SystemUpdate, Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { state.dismissOutdated() } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val results = state.results
            if (results != null) ResultsList(state, results) else FolderList(state)
            if (dropping) {
                Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Palette.violet.copy(alpha = 0.18f))
                    .border(2.dp, Palette.sunset, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text("${S.dropHere} · ${state.path.last().second}", color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        state.error?.let { Text(it, color = Palette.amber, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
        state.transfer?.let { TransferBar(state, it) }
    }
}

/** Keeps the keyboard's row in view as the arrow keys move it. */
@Composable
private fun FollowCursor(list: LazyListState, ids: List<String>, cursor: String?) {
    LaunchedEffect(cursor) {
        val index = ids.indexOf(cursor)
        if (index < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (index <= first || index >= last) list.animateScrollToItem((index - (if (index >= last) (last - first - 1).coerceAtLeast(0) else 0)).coerceAtLeast(0))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FolderList(state: AppState) {
    val entries = state.sorted
    val list = rememberLazyListState()
    FollowCursor(list, entries.map { it.id }, state.cursor)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp).semantics { contentDescription = state.path.last().second },
            state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(entries, key = { it.id }) { entry ->
                LaunchedEffect(entry.id) { if (entry.directory) state.loadSize(entry) else state.loadThumbnail(entry) }
                val selected = entry.id in state.selection
                val measured = state.sizes[entry.id]
                val sizeText = when {
                    !entry.directory -> size(entry.size)
                    measured != null -> S.folderSize(size(measured.bytes), measured.files, measured.complete)
                    else -> "—"
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Brush.horizontalGradient(listOf(Palette.violet.copy(alpha = 0.55f), Palette.pink.copy(alpha = 0.35f))) else SolidColor(Color.Transparent))
                    .then(if (entry.id == state.cursor) Modifier.border(1.5.dp, Palette.sun.copy(alpha = 0.7f), RoundedCornerShape(8.dp)) else Modifier)
                    .onPointerEvent(PointerEventType.Press) { event ->
                        val awt = event.nativeEvent as? java.awt.event.MouseEvent
                        if ((awt?.clickCount ?: 1) >= 2) state.open(entry)
                        else state.click(entry, ctrl = awt?.isControlDown == true, shift = awt?.isShiftDown == true)
                    }
                    .semantics(mergeDescendants = true) {
                        this.selected = selected
                        contentDescription = "${entry.name}, ${if (entry.directory) S.open else sizeText}"
                        onClick(if (entry.directory) S.open else S.download) { state.open(entry); true }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val thumbnail = state.thumbnails[entry.id]
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (thumbnail != null) Image(thumbnail, null, Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                        else { val (icon, tint) = kindIcon(Kind.of(entry)); Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(entry.name, color = Palette.fg, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                    Text(sizeText, color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
        if (entries.isEmpty() && !state.busy && state.entries.isEmpty()) Text(S.emptyFolder, color = Palette.muted, modifier = Modifier.align(Alignment.Center))
        if (entries.isEmpty() && state.entries.isNotEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S.nothingMatches, color = Palette.muted)
                if (state.query.isNotBlank()) SunsetButton(S.searchAll(state.query.trim()), enabled = !state.busy) { state.searchEverywhere() }
            }
        }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val bridge = state.bridge != null
    Row(Modifier.fillMaxWidth().background(Palette.fg.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(Icons.Filled.ArrowUpward, S.up, enabled = state.path.size > 1 && !state.busy) { state.up() }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            state.path.forEachIndexed { index, crumb ->
                if (index > 0) Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.size(16.dp))
                val last = index == state.path.size - 1
                Text(if (index == 0) state.deviceName else crumb.second, color = if (last) Palette.fg else Palette.muted, fontSize = 14.sp,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // The open folder keeps its name; the ones above it give way first.
                    modifier = (if (last) Modifier.widthIn(max = 280.dp) else Modifier.weight(1f, fill = false)).pressable(RoundedCornerShape(6.dp), enabled = !last && !state.busy) { state.go(index) }.padding(4.dp))
            }
        }
        // How the phone is connected, also announced to screen readers when it changes.
        val announcement = S.connectedOver(state.deviceName, state.transport.label)
        Chip(state.transport.label, transportIcon(state.transport), Modifier.semantics { contentDescription = announcement; liveRegion = LiveRegionMode.Polite })
        if (bridge) IconButton(Icons.Filled.ChatBubbleOutline, S.texts) { state.panel = Panel.TEXTS }
        Box {
            IconButton(Icons.Filled.Sort, "${S.sort}: ${S.sortName} / ${S.sortSize} / ${S.sortKind}") { sortMenu = true }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                listOf(SortKey.NAME to S.sortName, SortKey.SIZE to S.sortSize, SortKey.KIND to S.sortKind).forEach { (key, label) ->
                    DropdownMenuItem({ if (state.sortKey == key) state.ascending = !state.ascending else { state.sortKey = key; state.ascending = true }; sortMenu = false }) {
                        Text(label + if (state.sortKey == key) (if (state.ascending) "  ↑" else "  ↓") else "")
                    }
                }
            }
        }
        Box {
            IconButton(Icons.Filled.MoreHoriz, S.more, enabled = !state.busy) { moreMenu = true }
            DropdownMenu(moreMenu, { moreMenu = false }) {
                DropdownMenuItem({ moreMenu = false; javax.swing.JOptionPane.showInputDialog(null, S.folderName, S.newFolder, javax.swing.JOptionPane.PLAIN_MESSAGE)?.takeIf { it.isNotBlank() }?.let(state::newFolder) }) { Text(S.newFolder) }
                DropdownMenuItem({ moreMenu = false; state.upload(Dialogs.chooseUploads()) }) { Text(S.upload) }
                DropdownMenuItem({ moreMenu = false; promptRename(state) }, enabled = state.selection.size == 1) { Text(S.rename) }
                DropdownMenuItem({ moreMenu = false; confirmDelete(state) }, enabled = state.selection.isNotEmpty()) { Text(S.delete, color = if (state.selection.isNotEmpty()) Palette.pink else Palette.muted) }
                DropdownMenuItem({ moreMenu = false; state.importNewPhotos() }) { Text(S.importNew) }
                DropdownMenuItem({ moreMenu = false; state.importNewPhotos(chooseFolder = true) }) { Text(S.importTo) }
                if (bridge) {
                    DropdownMenuItem({ moreMenu = false; state.openReceived() }) { Text(S.openReceived) }
                    DropdownMenuItem({ moreMenu = false; state.sendClipboard() }) { Text(S.sendClipboard) }
                    DropdownMenuItem({ moreMenu = false; state.fetchClipboard() }) { Text(S.getClipboard) }
                }
                DropdownMenuItem({ moreMenu = false; state.refresh() }) { Text(S.refresh) }
                if (state.transport == Transport.USB && Platform.windows) DropdownMenuItem({ moreMenu = false; Platform.openThisPc() }) { Text(S.openExplorer) }
                if (state.usbLinks.isNotEmpty() || state.wpdPhones.isNotEmpty()) {
                    DropdownMenuItem({ moreMenu = false; state.mirror() }) { Text(S.mirror) }
                    DropdownMenuItem({ moreMenu = false; state.mirror(wireless = true) }) { Text(S.mirrorWireless) }
                }
                DropdownMenuItem({ moreMenu = false; state.disconnect() }) { Text(if (state.transport == Transport.USB) S.close else S.disconnect) }
            }
        }
        SunsetButton(if (state.selection.size > 1) S.downloadCount(state.selection.size) else S.download,
            enabled = !state.busy && state.selected.isNotEmpty()) { state.download(state.selected) }
        IconButton(Icons.Filled.Settings, S.settings) { state.panel = Panel.SETTINGS }
    }
}

/** Hits from every folder below the open one, with where each one is. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResultsList(state: AppState, results: SearchResult) {
    var menuFor by remember { mutableStateOf<Found?>(null) }
    val hits = state.sortedResults
    val list = rememberLazyListState()
    FollowCursor(list, hits.map { it.entry.id }, state.cursor)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(S.foundCount(hits.size, !results.complete, state.resultsFor), color = Palette.fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                if (!results.complete) Text(S.searchStopped, color = Palette.muted, fontSize = 12.sp)
            }
            GhostButton(S.done) { state.changeQuery("") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = list) {
            items(hits, key = { it.entry.id }) { hit ->
                Box {
                    val selected = hit.entry.id in state.selection
                    val where = hit.location.ifEmpty { state.path.last().second }.replace("/", " › ")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Brush.horizontalGradient(listOf(Palette.violet.copy(alpha = 0.45f), Palette.pink.copy(alpha = 0.25f))) else SolidColor(Color.Transparent))
                        .then(if (hit.entry.id == state.cursor) Modifier.border(1.5.dp, Palette.sun.copy(alpha = 0.7f), RoundedCornerShape(8.dp)) else Modifier)
                        .onPointerEvent(PointerEventType.Press) { event ->
                            val awt = event.nativeEvent as? java.awt.event.MouseEvent
                            when {
                                awt?.button == java.awt.event.MouseEvent.BUTTON3 -> menuFor = hit
                                (awt?.clickCount ?: 1) >= 2 -> state.openFound(hit)
                            }
                        }
                        .semantics(mergeDescendants = true) {
                            this.selected = selected
                            contentDescription = "${hit.entry.name}, $where"
                            onClick(if (hit.entry.directory) S.open else S.download) { state.openFound(hit); true }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (icon, tint) = kindIcon(Kind.of(hit.entry))
                        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hit.entry.name, color = Palette.fg, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(where, color = Palette.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                        if (!hit.entry.directory) Text(size(hit.entry.size), color = Palette.muted, fontSize = 13.sp)
                    }
                    DropdownMenu(menuFor == hit, { menuFor = null }) {
                        DropdownMenuItem({ menuFor = null; state.openFound(hit) }) { Text(if (hit.entry.directory) S.open else S.download) }
                        DropdownMenuItem({ menuFor = null; state.reveal(hit) }) { Text(S.showInFolder2) }
                    }
                }
            }
        }
    }
}

/** Search within the folder, and show only photos and videos. */
@Composable
private fun FilterBar(state: AppState, searchFocus: FocusRequester) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(state.query, state::changeQuery, S.search, Modifier.weight(1f), onEnter = state::searchEverywhere, focus = searchFocus)
        if (state.query.isNotBlank() && state.results == null) GhostButton(S.searchAll(state.query.trim()), enabled = !state.busy) { state.searchEverywhere() }
        listOf(Show.ALL to S.showAll, Show.MEDIA to S.showMedia).forEach { (option, label) ->
            val on = state.show == option
            Text(label, color = if (on) Color.White else Palette.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(if (on) Palette.violet.copy(alpha = 0.6f) else Palette.fg.copy(alpha = 0.06f))
                    .pressable(CircleShape, role = Role.Tab) { state.show = option }.semantics { this.selected = on }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun TransferBar(state: AppState, transfer: Transfer) {
    Column(Modifier.fillMaxWidth().padding(16.dp).glass(RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (transfer.upload) Icons.Filled.Upload else Icons.Filled.Download, null, tint = Palette.amber, modifier = Modifier.size(18.dp))
            Text(transfer.name, color = Palette.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
            if (transfer.count > 1) Text(S.ofCount(transfer.index, transfer.count), color = Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            when {
                transfer.done -> {
                    Text(if (transfer.upload) S.uploaded else S.saved, color = Palette.ok, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    transfer.saved?.let { file -> GhostButton(S.showInFolder) { Platform.reveal(file) } }
                    IconButton(Icons.Filled.Close, S.done) { state.dismissTransfer() }
                }
                !state.busy -> {
                    SunsetButton(S.resume, enabled = transfer.remaining.isNotEmpty()) { state.resume() }
                    IconButton(Icons.Filled.Close, S.cancel) { state.dismissTransfer() }
                }
                else -> {
                    Text("${(transfer.fraction * 100).toInt()}%", color = Palette.muted, fontSize = 13.sp)
                    GhostButton(S.cancel) { state.cancel() }
                }
            }
        }
        // The bar in the look's accent colours, with a little light at its tip.
        Canvas(Modifier.fillMaxWidth().height(16.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(transfer.fraction, 0f..1f) }) {
            val h = 10.dp.toPx(); val top = (size.height - h) / 2
            drawRoundRect(Palette.fg.copy(alpha = 0.1f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
            val width = maxOf(h, size.width * transfer.fraction)
            drawRoundRect(accentBrush(width), Offset(0f, top), Size(width, h), CornerRadius(h / 2))
            if (!transfer.done) {
                val center = Offset(width - h / 2, size.height / 2)
                drawCircle(Brush.radialGradient(listOf(Palette.sun.copy(alpha = 0.6f), Color.Transparent), center, 14.dp.toPx()), 14.dp.toPx(), center)
                drawCircle(Brush.radialGradient(listOf(Palette.cream, Palette.sun, Palette.amber), center, 8.dp.toPx()), 8.dp.toPx(), center)
            }
        }
    }
}
