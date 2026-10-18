package org.openbridge.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawSunArch
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import javax.swing.JOptionPane
import javax.swing.UIManager

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val state = AppState()
    state.start()
    application {
        val window = rememberWindowState(size = DpSize(1060.dp, 700.dp))
        Window(onCloseRequest = ::exitApplication, title = "OpenBridge", state = window, icon = painterResource("icon.png"),
            onKeyEvent = { shortcut(state, it) }) {   // after the focused field, so typing in search never triggers these
            var dropping by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                // Files dragged from File Explorer upload into the open phone folder.
                this@Window.window.contentPane.dropTarget = DropTarget(this@Window.window.contentPane, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                    override fun dragEnter(event: DropTargetDragEvent) { dropping = state.client != null }
                    override fun dragExit(event: DropTargetEvent) { dropping = false }
                    override fun drop(event: DropTargetDropEvent) {
                        dropping = false
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching { event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull()
                        event.dropComplete(files != null)
                        if (files != null && state.client != null) state.upload(files)
                    }
                })
                onDispose { }
            }
            App(state, dropping)
        }
    }
}

private fun shortcut(state: AppState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || state.client == null) return false
    return when {
        event.isCtrlPressed && event.key == Key.A -> { state.selectAll(); true }
        event.key == Key.Enter -> { state.selected.firstOrNull()?.let(state::open); true }
        event.key == Key.Backspace || (event.isAltPressed && event.key == Key.DirectionUp) -> { state.up(); true }
        event.key == Key.F5 -> { state.refresh(); true }
        event.key == Key.Delete && !state.busy -> { confirmDelete(state); true }
        event.key == Key.F2 && !state.busy -> { promptRename(state); true }
        else -> false
    }
}

/** Asks before deleting anything on the phone: there's no recycle bin there. */
private fun confirmDelete(state: AppState) {
    val doomed = state.selected
    if (doomed.isEmpty()) return
    val answer = JOptionPane.showConfirmDialog(null, S.deleteConfirm(doomed.size, doomed.first().name), S.delete.trimEnd('…'),
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
    if (answer == JOptionPane.OK_OPTION) state.deleteSelected()
}

private fun promptRename(state: AppState) {
    val entry = state.selected.singleOrNull() ?: return
    val name = JOptionPane.showInputDialog(null, S.newName, entry.name) ?: return
    state.rename(entry, name)
}

private fun openPage(url: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }

private val glassShape = RoundedCornerShape(18.dp)

private fun Modifier.glass(shape: Shape = glassShape, border: Color = Color.White.copy(alpha = 0.14f)) =
    clip(shape).background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f)))).border(1.dp, border, shape)

@Composable
private fun App(state: AppState, dropping: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.plum, 0.45f to Palette.dusk, 1f to Palette.night))) {
        if (state.client == null) ConnectScreen(state) else BrowserScreen(state, dropping)
        state.update?.let { release ->
            Text(S.update(release.version), color = Palette.night, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp).clip(CircleShape).background(Palette.amber)
                    .clickable { openPage(release.page) }.padding(horizontal = 12.dp, vertical = 5.dp))
        }
        state.toast?.let { message ->
            Text(message, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).glass(CircleShape).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

/** The arch, drawn live with the same engine and values as the phone's. */
@Composable
private fun Sun(active: Boolean, connected: Boolean, modifier: Modifier) {
    val engine = remember { SunEngine() }
    var tick by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf(SunEngine.Frame()) }
    val activeNow by rememberUpdatedState(active)
    val connectedNow by rememberUpdatedState(connected)
    LaunchedEffect(engine) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
            last = now
            frame = engine.step(dt, activeNow, connectedNow, false)
            tick = now
        }
    }
    Canvas(modifier) {
        tick
        drawSunArch(engine, frame, Offset(size.width / 2, size.height / 2), minOf(size.width, size.height) * 0.27f, 280.dp.toPx())
    }
}

@Composable
private fun ConnectScreen(state: AppState) {
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("OpenBridge", color = Palette.fg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(S.tagline, color = Palette.muted, fontSize = 14.sp)
            Sun(active = true, connected = state.pairingCode != null, modifier = Modifier.weight(1f).fillMaxWidth())
            Text(S.waiting, color = Palette.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(S.waitingHint, color = Palette.muted, fontSize = 13.5.sp)
        }
        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.saved.isNotEmpty()) SavedPhones(state)
            Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(S.nearby)
                val unsaved = state.nearby.filter { phone -> state.saved.none { it.id == phone.id } }
                if (unsaved.isEmpty()) Text(if (state.searchedLong) S.notFound else S.looking, color = Palette.muted, fontSize = 12.5.sp)
                unsaved.forEach { phone ->
                    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).clickable(enabled = !state.busy) { state.pairWith(phone) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Smartphone, null, tint = Palette.fg)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(phone.name, color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${phone.host} · ${phone.port}", color = Palette.muted, fontSize = 11.5.sp)
                        }
                        Text(S.pair, color = Palette.amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Label(S.orType)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(state.host, { state.host = it }, S.address, Modifier.weight(1f))
                    Field(state.port, { state.port = it }, S.port, Modifier.width(90.dp))
                }
                SunsetButton(S.pair, enabled = !state.busy && state.host.isNotBlank() && state.port.isNotBlank(), modifier = Modifier.fillMaxWidth()) { state.pair() }
                state.pairingCode?.let { code ->
                    Text(S.compare, color = Palette.muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        code.forEachIndexed { index, digit ->
                            if (index == 3) Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(44.dp, 56.dp).glass(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Text(digit.toString(), color = Palette.fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (state.busy && state.pairingCode == null && state.status.isNotEmpty()) Text(state.status, color = Palette.muted, fontSize = 12.5.sp)
                if (state.busy) GhostButton(S.cancel) { state.cancel() }
                state.error?.let { Text(it, color = Palette.amber, fontSize = 12.5.sp) }
            }
            UsbCard(state)
        }
    }
}

/** Phones paired before: they reconnect by themselves, or with a click. */
@Composable
private fun SavedPhones(state: AppState) {
    Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(S.yourPhones)
        state.saved.forEach { phone ->
            val here = state.isNearby(phone)
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp), if (here) Palette.ok.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Smartphone, null, tint = if (here) Palette.ok else Palette.fg)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(phone.name, color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (here) S.nearbyNow else S.reconnects, color = Palette.muted, fontSize = 11.5.sp)
                }
                GhostButton(S.connect, enabled = !state.busy) { state.connect(phone) }
                Spacer(Modifier.width(6.dp))
                IconButton(Icons.Filled.Close, S.forget) {
                    if (JOptionPane.showConfirmDialog(null, S.forgetConfirm(phone.name), S.forget, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                        state.forgetPhone(phone)
                }
            }
        }
    }
}

@Composable
private fun UsbCard(state: AppState) {
    val phone = state.usbPhones.firstOrNull()
    Column(Modifier.fillMaxWidth().glass(border = if (phone != null) Palette.ok.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(S.cable)
        if (phone != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.ok))
                Spacer(Modifier.width(8.dp))
                Text(S.cableFound(phone), color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SunsetButton(S.openExplorer, modifier = Modifier.weight(1f)) { Usb.openExplorer() }
                GhostButton(S.mirror, enabled = !state.mirroring) { state.mirror() }
            }
        } else {
            Text(S.cableHint, color = Palette.muted, fontSize = 12.5.sp)
            GhostButton(S.mirror, enabled = !state.mirroring) { state.mirror() }
        }
        Text(S.mirrorHint, color = Palette.muted.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowserScreen(state: AppState, dropping: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Toolbar(state)
        FilterBar(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val entries = state.sorted
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    LaunchedEffect(entry.id) { if (entry.directory) state.loadSize(entry) else state.loadThumbnail(entry) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (entry.id in state.selection) Brush.horizontalGradient(listOf(Palette.violet.copy(alpha = 0.55f), Palette.pink.copy(alpha = 0.35f)))
                            else SolidColor(Color.Transparent))
                        .onPointerEvent(PointerEventType.Press) { event ->
                            val awt = event.nativeEvent as? java.awt.event.MouseEvent
                            if ((awt?.clickCount ?: 1) >= 2) state.open(entry)
                            else state.click(entry, ctrl = awt?.isControlDown == true, shift = awt?.isShiftDown == true)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val thumbnail = state.thumbnails[entry.id]
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            if (thumbnail != null) Image(thumbnail, null, Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                            else { val (icon, tint) = kindIcon(Kind.of(entry)); Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(entry.name, color = Palette.fg, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                        val measured = state.sizes[entry.id]
                        Text(when {
                            !entry.directory -> size(entry.size)
                            measured != null -> S.folderSize(size(measured.bytes), measured.files, measured.complete)
                            else -> "—"
                        }, color = Palette.muted, fontSize = 13.sp)
                    }
                }
            }
            if (entries.isEmpty() && !state.busy && state.entries.isEmpty()) Text(S.emptyFolder, color = Palette.muted, modifier = Modifier.align(Alignment.Center))
            if (dropping) {
                Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Palette.violet.copy(alpha = 0.18f))
                    .border(2.dp, Palette.sunset, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text("${S.dropHere} · ${state.path.last().second}", color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        state.error?.let { Text(it, color = Palette.amber, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
        state.transfer?.let { TransferBar(state, it) }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(Icons.Filled.ArrowUpward, S.up, enabled = state.path.size > 1 && !state.busy) { state.up() }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            state.path.forEachIndexed { index, crumb ->
                if (index > 0) Icon(Icons.Filled.ChevronRight, null, tint = Palette.muted, modifier = Modifier.size(16.dp))
                val last = index == state.path.size - 1
                Text(if (index == 0) state.deviceName else crumb.second, color = if (last) Palette.fg else Palette.muted, fontSize = 14.sp,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !last && !state.busy) { state.go(index) }.padding(4.dp))
            }
        }
        Box {
            IconButton(Icons.Filled.Sort, "${S.sortName} / ${S.sortSize} / ${S.sortKind}") { sortMenu = true }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                listOf(SortKey.NAME to S.sortName, SortKey.SIZE to S.sortSize, SortKey.KIND to S.sortKind).forEach { (key, label) ->
                    DropdownMenuItem({ if (state.sortKey == key) state.ascending = !state.ascending else { state.sortKey = key; state.ascending = true }; sortMenu = false }) {
                        Text(label + if (state.sortKey == key) (if (state.ascending) "  ↑" else "  ↓") else "")
                    }
                }
            }
        }
        Box {
            IconButton(Icons.Filled.MoreHoriz, "…", enabled = !state.busy) { moreMenu = true }
            DropdownMenu(moreMenu, { moreMenu = false }) {
                DropdownMenuItem({ moreMenu = false; JOptionPane.showInputDialog(null, S.folderName, S.newFolder, JOptionPane.PLAIN_MESSAGE)?.takeIf { it.isNotBlank() }?.let(state::newFolder) }) { Text(S.newFolder) }
                DropdownMenuItem({ moreMenu = false; state.upload(Dialogs.chooseUploads()) }) { Text(S.upload) }
                DropdownMenuItem({ moreMenu = false; promptRename(state) }, enabled = state.selection.size == 1) { Text(S.rename) }
                DropdownMenuItem({ moreMenu = false; confirmDelete(state) }, enabled = state.selection.isNotEmpty()) { Text(S.delete, color = if (state.selection.isNotEmpty()) Palette.pink else Palette.muted) }
                DropdownMenuItem({ moreMenu = false; state.sendClipboard() }) { Text(S.sendClipboard) }
                DropdownMenuItem({ moreMenu = false; state.fetchClipboard() }) { Text(S.getClipboard) }
                DropdownMenuItem({ moreMenu = false; state.refresh() }) { Text(S.refresh) }
                if (state.usbPhones.isNotEmpty()) DropdownMenuItem({ moreMenu = false; state.mirror() }) { Text(S.mirror) }
                DropdownMenuItem({ moreMenu = false; state.disconnect() }) { Text(S.disconnect) }
            }
        }
        SunsetButton(if (state.selection.size > 1) S.downloadCount(state.selection.size) else S.download,
            enabled = !state.busy && state.selection.isNotEmpty()) { state.download(state.selected) }
    }
}

/** Search within the folder, and show only photos and videos. */
@Composable
private fun FilterBar(state: AppState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(state.query, { state.query = it }, S.search, Modifier.weight(1f))
        listOf(Show.ALL to S.showAll, Show.MEDIA to S.showMedia).forEach { (option, label) ->
            val on = state.show == option
            Text(label, color = if (on) Color.White else Palette.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(if (on) Palette.violet.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f))
                    .clickable { state.show = option }.padding(horizontal = 12.dp, vertical = 7.dp))
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
                    Text(if (transfer.upload) S.uploaded else S.saved, color = Palette.ok, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    transfer.saved?.let { file -> GhostButton(S.showInFolder) { runCatching { ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start() } } }
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
        // The sunset bar, with a little sun at its tip.
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val h = 10.dp.toPx(); val top = (size.height - h) / 2
            drawRoundRect(Color.White.copy(alpha = 0.1f), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            val width = maxOf(h, size.width * transfer.fraction)
            drawRoundRect(Brush.horizontalGradient(0f to Palette.violet, 0.55f to Palette.pink, 0.85f to Palette.amber, 1f to Palette.sun, endX = width),
                Offset(0f, top), androidx.compose.ui.geometry.Size(width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            if (!transfer.done) {
                val center = Offset(width - h / 2, size.height / 2)
                drawCircle(Brush.radialGradient(listOf(Palette.sun.copy(alpha = 0.6f), Color.Transparent), center, 14.dp.toPx()), 14.dp.toPx(), center)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFFDF0), Palette.sun, Palette.amber), center, 8.dp.toPx()), 8.dp.toPx(), center)
            }
        }
    }
}

private fun kindIcon(kind: Kind): Pair<ImageVector, Color> = when (kind) {
    Kind.FOLDER -> Icons.Filled.Folder to Palette.amber
    Kind.IMAGE -> Icons.Filled.Image to Palette.violet
    Kind.VIDEO -> Icons.Filled.Movie to Palette.pink
    Kind.AUDIO -> Icons.Filled.MusicNote to Palette.sun
    Kind.DOCUMENT -> Icons.Filled.Description to Palette.muted
    Kind.ARCHIVE -> Icons.Filled.FolderZip to Palette.muted
    Kind.APP -> Icons.Filled.Android to Palette.ok
    Kind.OTHER -> Icons.Filled.InsertDriveFile to Palette.muted
}

private fun size(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0; var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}

@Composable
private fun Label(text: String) = Text(text, color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)

@Composable
private fun Field(value: String, change: (String) -> Unit, placeholder: String, modifier: Modifier) {
    Box(modifier.glass(RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Palette.muted.copy(alpha = 0.7f), fontSize = 13.5.sp)
        BasicTextField(value, change, singleLine = true, cursorBrush = SolidColor(Palette.sun),
            textStyle = TextStyle(color = Palette.fg, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SunsetButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).background(Palette.sunset, alpha = if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GhostButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Palette.fg else Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (enabled) Palette.fg else Palette.muted.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}
