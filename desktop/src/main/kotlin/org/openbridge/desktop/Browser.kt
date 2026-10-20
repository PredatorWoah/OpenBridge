package org.openbridge.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette

/*
 * The browser's parts that every look shares: the folder as a list or a grid, search hits, and the toolbar's
 * actions. Each look's shell arranges them; `look` picks how a row or tile is drawn.
 */

/** The open folder (as a list or grid) or the search hits, with the empty and no-match notes. */
@Composable
fun FileArea(state: AppState, look: Look, modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(8.dp)) {
    Box(modifier) {
        val results = state.results
        when {
            results != null -> ResultsList(state, results, look, padding)
            state.showGrid -> FileGrid(state, look, padding)
            else -> FileList(state, look, padding)
        }
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

@Composable
private fun FollowCursor(grid: LazyGridState, ids: List<String>, cursor: String?) {
    LaunchedEffect(cursor) {
        val index = ids.indexOf(cursor)
        if (index < 0) return@LaunchedEffect
        val visible = grid.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index }) grid.animateScrollToItem(index)
    }
}

/** What a row says about its size: a file's, a folder's once measured. */
private fun sizeText(state: AppState, entry: Entry): String {
    val measured = state.sizes[entry.id]
    return when {
        !entry.directory -> size(entry.size)
        measured != null -> S.folderSize(size(measured.bytes), measured.files, measured.complete)
        else -> "—"
    }
}

/** Click selects (Ctrl and Shift as in a file manager), double click opens; screen readers get one action. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.entryInput(state: AppState, entry: Entry, selected: Boolean, description: String) =
    onPointerEvent(PointerEventType.Press) { event ->
        val awt = event.nativeEvent as? java.awt.event.MouseEvent
        if ((awt?.clickCount ?: 1) >= 2) state.open(entry)
        else state.click(entry, ctrl = awt?.isControlDown == true, shift = awt?.isShiftDown == true)
    }.semantics(mergeDescendants = true) {
        this.selected = selected
        contentDescription = description
        onClick(if (entry.directory) S.open else S.download) { state.open(entry); true }
    }

@Composable
private fun FileList(state: AppState, look: Look, padding: PaddingValues) {
    val entries = state.sorted
    val list = rememberLazyListState()
    FollowCursor(list, entries.map { it.id }, state.cursor)
    // Tide's depth gauges compare each folder with the largest measured one here.
    val deepest = entries.filter { it.directory }.maxOfOrNull { state.sizes[it.id]?.bytes ?: 0L } ?: 0L
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = state.path.last().second }, state = list, contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(if (look == Look.TIDE) 6.dp else if (look == Look.AURORA) 2.dp else 0.dp)) {
            items(entries, key = { it.id }) { entry ->
                LaunchedEffect(entry.id) { if (entry.directory) state.loadSize(entry) else state.loadThumbnail(entry) }
                val gauge = if (entry.directory && deepest > 0) (state.sizes[entry.id]?.bytes ?: 0L).toFloat() / deepest else null
                EntryRow(state, entry, look, gauge)
            }
        }
        EmptyNotes(state, entries, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun EmptyNotes(state: AppState, entries: List<Entry>, modifier: Modifier) {
    if (entries.isEmpty() && !state.busy && state.entries.isEmpty())
        Text(S.emptyFolder, color = Palette.muted, textAlign = TextAlign.Center, modifier = modifier.padding(24.dp))
    if (entries.isEmpty() && state.entries.isNotEmpty()) {
        Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S.nothingMatches, color = Palette.muted)
            if (state.query.isNotBlank()) SunsetButton(S.searchAll(state.query.trim()), enabled = !state.busy) { state.searchEverywhere() }
        }
    }
}

/** A file's preview when there is one, else its kind's icon. */
@Composable
private fun Thumb(state: AppState, entry: Entry, size: Dp, corner: Dp, iconScale: Float = 0.78f) {
    val thumbnail = state.thumbnails[entry.id]
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (thumbnail != null) Image(thumbnail, null, Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
        else { val (icon, tint) = kindIcon(Kind.of(entry)); Icon(icon, null, tint = tint, modifier = Modifier.size(size * iconScale)) }
    }
}

/** One row of the folder, drawn the look's way: Dusk's highlighted rows, Aurora's airy glowing rows, Tide's capsules. */
@Composable
private fun EntryRow(state: AppState, entry: Entry, look: Look, gauge: Float?) {
    val selected = entry.id in state.selection
    val cursor = entry.id == state.cursor
    val sizeText = sizeText(state, entry)
    val description = "${entry.name}, ${if (entry.directory) S.open else sizeText}"
    when (look) {
        Look.DUSK -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brush.horizontalGradient(listOf(Palette.violet.copy(alpha = 0.55f), Palette.pink.copy(alpha = 0.35f))) else SolidColor(Color.Transparent))
            .then(if (cursor) Modifier.border(1.5.dp, Palette.sun.copy(alpha = 0.7f), RoundedCornerShape(8.dp)) else Modifier)
            .entryInput(state, entry, selected, description)
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(state, entry, 28.dp, 5.dp)
            Spacer(Modifier.width(12.dp))
            Text(entry.name, color = Palette.fg, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            Text(sizeText, color = Palette.muted, fontSize = 13.sp)
        }
        Look.AURORA -> Row(Modifier.fillMaxWidth().auroraGlow(selected, cursor, RoundedCornerShape(12.dp))
            .entryInput(state, entry, selected, description)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(state, entry, 32.dp, 8.dp, 0.7f)
            Spacer(Modifier.width(16.dp))
            Text(entry.name, color = Palette.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(sizeText, color = Palette.muted, fontSize = 13.sp, letterSpacing = 0.4.sp, maxLines = 1)
        }
        Look.TIDE -> {
            val fill by animateFloatAsState(if (selected) 1f else 0f, if (Platform.reduceMotion) tween(0) else spring(0.62f, Spring.StiffnessLow))
            val shape = RoundedCornerShape(50)
            val time = if (fill > 0f) animationTime() else 0f
            Row(Modifier.fillMaxWidth().clip(shape).background(Palette.night.copy(alpha = 0.42f))
                .drawBehind { drawWaterFill(fill, time) }
                .border(if (cursor) 1.5.dp else 1.dp, if (cursor) Palette.sun.copy(alpha = 0.85f) else Palette.fg.copy(alpha = 0.10f), shape)
                .entryInput(state, entry, selected, description)
                .padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    Thumb(state, entry, 34.dp, 17.dp, 0.6f)
                }
                Spacer(Modifier.width(14.dp))
                Text(entry.name, color = Palette.fg, fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                if (gauge != null) { DepthGauge(gauge, Modifier.padding(horizontal = 12.dp)) }
                Text(sizeText, color = if (selected) Palette.fg else Palette.muted, fontSize = 12.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FileGrid(state: AppState, look: Look, padding: PaddingValues) {
    val entries = state.sorted
    val grid = rememberLazyGridState()
    FollowCursor(grid, entries.map { it.id }, state.cursor)
    val tile = when (look) { Look.AURORA -> 160.dp; Look.TIDE -> 138.dp; Look.DUSK -> 124.dp }
    val gap = if (look == Look.DUSK) 10.dp else 16.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inner = maxWidth - padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) - padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        val columns = maxOf(1, ((inner + gap) / (tile + gap)).toInt())
        SideEffect { state.columns = columns }
        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().semantics { contentDescription = state.path.last().second }, grid,
            contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            gridItems(entries, key = { it.id }) { entry ->
                LaunchedEffect(entry.id) { if (entry.directory) state.loadSize(entry) else state.loadThumbnail(entry) }
                EntryTile(state, entry, look)
            }
        }
        EmptyNotes(state, entries, Modifier.align(Alignment.Center))
    }
}

/** One tile of the grid: Aurora's large gallery tiles with a soft glow, Tide's round cards that bob in, Dusk's glass. */
@Composable
private fun EntryTile(state: AppState, entry: Entry, look: Look) {
    val selected = entry.id in state.selection
    val cursor = entry.id == state.cursor
    val sizeText = sizeText(state, entry)
    val description = "${entry.name}, ${if (entry.directory) S.open else sizeText}"
    val caption: @Composable ColumnScope.() -> Unit = {
        Text(entry.name, color = Palette.fg, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Text(sizeText, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    when (look) {
        Look.DUSK -> Column(Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brush.verticalGradient(listOf(Palette.violet.copy(alpha = 0.55f), Palette.pink.copy(alpha = 0.35f))) else SolidColor(Palette.fg.copy(alpha = 0.05f)))
            .then(if (cursor) Modifier.border(1.5.dp, Palette.sun.copy(alpha = 0.7f), RoundedCornerShape(12.dp)) else Modifier)
            .entryInput(state, entry, selected, description).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Palette.night.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                PreviewFill(state, entry)
            }
            Spacer(Modifier.height(6.dp))
            caption()
        }
        Look.AURORA -> Column(Modifier.entryInput(state, entry, selected, description), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).auroraGlow(selected, cursor, RoundedCornerShape(16.dp), resting = true), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { PreviewFill(state, entry) }
            }
            Spacer(Modifier.height(8.dp))
            caption()
        }
        Look.TIDE -> {
            // Cards bob up into place as they appear, with a little overshoot.
            val rise = remember { Animatable(if (Platform.reduceMotion) 0f else 14f) }
            LaunchedEffect(Unit) { rise.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) }
            val fill by animateFloatAsState(if (selected) 1f else 0f, if (Platform.reduceMotion) tween(0) else spring(0.62f, Spring.StiffnessLow))
            val time = if (fill > 0f) animationTime() else 0f
            val shape = RoundedCornerShape(24.dp)
            Column(Modifier.graphicsLayer { translationY = rise.value * density }.clip(shape).background(Palette.night.copy(alpha = 0.42f))
                .drawBehind { drawWaterFill(fill, time, vertical = true) }
                .border(if (cursor) 1.5.dp else 1.dp, if (cursor) Palette.sun.copy(alpha = 0.85f) else Palette.fg.copy(alpha = 0.10f), shape)
                .entryInput(state, entry, selected, description).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Palette.fg.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    PreviewFill(state, entry)
                }
                Spacer(Modifier.height(6.dp))
                caption()
            }
        }
    }
}

/** A tile's picture: the preview filling it, or the kind's icon in the middle. */
@Composable
private fun PreviewFill(state: AppState, entry: Entry) {
    val thumbnail = state.thumbnails[entry.id]
    if (thumbnail != null) Image(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else { val (icon, tint) = kindIcon(Kind.of(entry)); Icon(icon, null, tint = tint, modifier = Modifier.fillMaxSize(0.42f)) }
}

/** Hits from every folder below the open one, with where each one is. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResultsList(state: AppState, results: SearchResult, look: Look, padding: PaddingValues) {
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
        val shape = if (look == Look.TIDE) RoundedCornerShape(50) else RoundedCornerShape(if (look == Look.AURORA) 12.dp else 8.dp)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(if (look == Look.TIDE) 6.dp else 0.dp)) {
            items(hits, key = { it.entry.id }) { hit ->
                Box {
                    val selected = hit.entry.id in state.selection
                    val cursor = hit.entry.id == state.cursor
                    val where = hit.location.ifEmpty { state.path.last().second }.replace("/", " › ")
                    Row(Modifier.fillMaxWidth()
                        .then(when (look) {
                            Look.AURORA -> Modifier.auroraGlow(selected, cursor, shape)
                            Look.TIDE -> Modifier.clip(shape).background(if (selected) Palette.amber.copy(alpha = 0.35f) else Palette.night.copy(alpha = 0.42f))
                                .border(if (cursor) 1.5.dp else 1.dp, if (cursor) Palette.sun.copy(alpha = 0.85f) else Palette.fg.copy(alpha = 0.1f), shape)
                            Look.DUSK -> Modifier.clip(shape)
                                .background(if (selected) Brush.horizontalGradient(listOf(Palette.violet.copy(alpha = 0.45f), Palette.pink.copy(alpha = 0.25f))) else SolidColor(Color.Transparent))
                                .then(if (cursor) Modifier.border(1.5.dp, Palette.sun.copy(alpha = 0.7f), shape) else Modifier)
                        })
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
                        .padding(horizontal = if (look == Look.DUSK) 12.dp else 18.dp, vertical = if (look == Look.DUSK) 6.dp else 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
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

/** A short bar: how big a folder is next to the largest one here. */
@Composable
private fun DepthGauge(fraction: Float, modifier: Modifier) {
    Box(modifier.size(44.dp, 6.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.10f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.04f, 1f)).clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Palette.violet, Palette.amber))))
    }
}

// Toolbar actions, shared by every look's toolbar.

@Composable
fun SearchField(state: AppState, focus: FocusRequester, modifier: Modifier) =
    Field(state.query, state::changeQuery, S.search, modifier, onEnter = state::searchEverywhere, focus = focus)

/** Offers the deep search while typing in the folder search. */
@Composable
fun SearchAllButton(state: AppState) {
    if (state.query.isNotBlank() && state.results == null) GhostButton(S.searchAll(state.query.trim()), enabled = !state.busy) { state.searchEverywhere() }
}

@Composable
fun SortButton(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(Icons.Filled.Sort, "${S.sort}: ${S.sortName} / ${S.sortSize} / ${S.sortKind}") { open = true }
        DropdownMenu(open, { open = false }) { SortItems(state) { open = false } }
    }
}

@Composable
private fun SortItems(state: AppState, close: () -> Unit) {
    listOf(SortKey.NAME to S.sortName, SortKey.SIZE to S.sortSize, SortKey.KIND to S.sortKind).forEach { (key, label) ->
        DropdownMenuItem({ if (state.sortKey == key) state.ascending = !state.ascending else { state.sortKey = key; state.ascending = true }; close() }) {
            Text(label + if (state.sortKey == key) (if (state.ascending) "  ↑" else "  ↓") else "")
        }
    }
}

@Composable
fun ViewToggle(state: AppState) {
    val grid = state.showGrid
    IconButton(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, if (grid) S.listView else S.gridView) { state.grid = !grid }
}

/** Only photos and videos, as a toggle. */
@Composable
fun MediaToggle(state: AppState) {
    val on = state.show == Show.MEDIA
    IconButton(Icons.Filled.PhotoLibrary, S.showMedia, on = on) { state.show = if (on) Show.ALL else Show.MEDIA }
}

@Composable
fun UploadButton(state: AppState) = IconButton(Icons.Filled.Upload, S.upload.trimEnd('…'), enabled = !state.busy) { state.upload(Dialogs.chooseUploads()) }

@Composable
fun NewFolderButton(state: AppState) = IconButton(Icons.Filled.CreateNewFolder, S.newFolder.trimEnd('…'), enabled = !state.busy) { promptNewFolder(state) }

fun promptNewFolder(state: AppState) {
    javax.swing.JOptionPane.showInputDialog(null, S.folderName, S.newFolder, javax.swing.JOptionPane.PLAIN_MESSAGE)?.takeIf { it.isNotBlank() }?.let(state::newFolder)
}

@Composable
fun DownloadButton(state: AppState) = SunsetButton(if (state.selection.size > 1) S.downloadCount(state.selection.size) else S.download,
    enabled = !state.busy && state.selected.isNotEmpty()) { state.download(state.selected) }

/**
 * The ⋯ menu: everything else the browser can do. `compact` also holds what a narrow toolbar had no room for
 * (sort, list or grid, photos only, upload and new folder).
 */
@Composable
fun MoreButton(state: AppState, compact: Boolean = false, withUpload: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val bridge = state.bridge != null
    Box {
        IconButton(Icons.Filled.MoreHoriz, S.more, enabled = !state.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            val close = { open = false }
            if (compact) {
                SortItems(state, close)
                DropdownMenuItem({ close(); state.grid = !state.showGrid }) { Text(if (state.showGrid) S.listView else S.gridView) }
                DropdownMenuItem({ close(); state.show = if (state.show == Show.MEDIA) Show.ALL else Show.MEDIA }) {
                    Text((if (state.show == Show.MEDIA) "✓ " else "") + S.showMedia)
                }
            }
            if (withUpload || compact) {
                DropdownMenuItem({ close(); promptNewFolder(state) }) { Text(S.newFolder) }
                DropdownMenuItem({ close(); state.upload(Dialogs.chooseUploads()) }) { Text(S.upload) }
            }
            DropdownMenuItem({ close(); promptRename(state) }, enabled = state.selection.size == 1) { Text(S.rename) }
            DropdownMenuItem({ close(); confirmDelete(state) }, enabled = state.selection.isNotEmpty()) { Text(S.delete, color = if (state.selection.isNotEmpty()) Palette.pink else Palette.muted) }
            DropdownMenuItem({ close(); state.importNewPhotos() }) { Text(S.importNew) }
            DropdownMenuItem({ close(); state.importNewPhotos(chooseFolder = true) }) { Text(S.importTo) }
            if (bridge) {
                DropdownMenuItem({ close(); state.openReceived() }) { Text(S.openReceived) }
                DropdownMenuItem({ close(); state.sendClipboard() }) { Text(S.sendClipboard) }
                DropdownMenuItem({ close(); state.fetchClipboard() }) { Text(S.getClipboard) }
            }
            DropdownMenuItem({ close(); state.refresh() }) { Text(S.refresh) }
            if (state.transport == Transport.USB && Platform.windows) DropdownMenuItem({ close(); Platform.openThisPc() }) { Text(S.openExplorer) }
            if (state.usbLinks.isNotEmpty() || state.wpdPhones.isNotEmpty()) {
                DropdownMenuItem({ close(); state.mirror() }) { Text(S.mirror) }
                DropdownMenuItem({ close(); state.mirror(wireless = true) }) { Text(S.mirrorWireless) }
            }
            DropdownMenuItem({ close(); state.disconnect() }) { Text(if (state.transport == Transport.USB) S.close else S.disconnect) }
        }
    }
}

/** How the phone is connected, also announced to screen readers when it changes. */
@Composable
fun TransportChip(state: AppState) {
    val announcement = S.connectedOver(state.deviceName, state.transport.label)
    Chip(state.transport.label, transportIcon(state.transport), Modifier.semantics { contentDescription = announcement; liveRegion = LiveRegionMode.Polite })
}

/** What went wrong last, said calmly. */
@Composable
fun ErrorLine(state: AppState, modifier: Modifier = Modifier) {
    state.error?.let { Text(it, color = Palette.amber, fontSize = 12.5.sp, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

/** The transfer's own buttons: Show in folder and Done when finished, Resume when stopped part way, else Cancel. */
@Composable
fun TransferActions(state: AppState, transfer: Transfer, percent: Boolean = true) {
    when {
        transfer.done -> {
            Text(if (transfer.upload) S.uploaded else S.saved, color = Palette.ok, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            transfer.saved?.let { file -> GhostButton(S.showInFolder) { Platform.reveal(file) } }
            IconButton(Icons.Filled.Close, S.done) { state.dismissTransfer() }
        }
        !state.busy -> {
            SunsetButton(S.resume, enabled = transfer.remaining.isNotEmpty()) { state.resume() }
            IconButton(Icons.Filled.Close, S.cancel) { state.dismissTransfer() }
        }
        else -> {
            if (percent) Text("${(transfer.fraction * 100).toInt()}%", color = Palette.muted, fontSize = 13.sp)
            GhostButton(S.cancel) { state.cancel() }
        }
    }
}
