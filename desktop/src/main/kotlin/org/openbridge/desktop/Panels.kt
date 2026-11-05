package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawBackdrop
import org.openbridge.ui.drawHero
import java.text.DateFormat
import java.util.Date

/** A sheet over the window: a dim backdrop that closes it, and a glass card on the right or in the middle. */
@Composable
fun Sheet(title: String, close: () -> Unit, width: Dp, side: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.55f))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = close)) {
        Column(Modifier.align(if (side) Alignment.CenterEnd else Alignment.Center).width(width).then(if (side) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.9f))
            .padding(if (side) 0.dp else 12.dp).clip(if (side) RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp) else glassShape)
            .background(Palette.dusk.copy(alpha = 0.97f)).border(1.dp, Palette.fg.copy(alpha = 0.14f), if (side) RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp) else glassShape)
            // Clicks inside the card stay there.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Palette.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(Icons.Filled.Close, S.close, onClick = close)
            }
            content()
        }
    }
}

/** Texts: send one to the phone's clipboard, take the clipboard either way, and what was sent before. */
@Composable
fun TextsPanel(state: AppState) {
    var draft by remember { mutableStateOf("") }
    Sheet(S.texts, { state.panel = null }, 400.dp, side = true) {
        Text(S.textsHint, color = Palette.muted, fontSize = 12.sp)
        val connected = state.bridge != null
        Field(draft, { draft = it }, S.typeText, Modifier.fillMaxWidth().heightIn(min = 70.dp), singleLine = false, monospace = false,
            onEnter = { if (connected && draft.isNotEmpty()) state.sendText(draft) { draft = "" } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SunsetButton(S.send, enabled = connected && draft.isNotEmpty()) { state.sendText(draft) { draft = "" } }
            GhostButton(S.sendClipboard, enabled = connected) { state.sendClipboard() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(S.getClipboard, enabled = connected) { state.fetchClipboard() }
            if (state.texts.isNotEmpty()) GhostButton(S.clear) { state.clearTexts() }
        }
        Toggle(S.autoSend, state.autoSendTexts, S.autoSendHint) { state.changeAutoSend(it) }
        if (state.phoneInfo == null && connected) Text(S.textsOld, color = Palette.muted, fontSize = 11.5.sp)
        if (state.texts.isEmpty()) Text(S.noTexts, color = Palette.muted, fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.texts) { item -> TextRow(state, item) }
        }
    }
}

@Composable
private fun TextRow(state: AppState, item: TextItem) {
    val direction = if (item.fromPhone) S.fromPhone else S.toPhone
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.at))
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(direction, if (item.fromPhone) Icons.Filled.Smartphone else Icons.Filled.Computer)
            Spacer(Modifier.width(8.dp))
            Text(time, color = Palette.muted, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            IconButton(Icons.Filled.ContentCopy, "${S.copy}: ${item.text.take(40)}") { state.copyText(item.text) }
        }
        Text(item.text, color = Palette.fg, fontSize = 13.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

/** Settings: the look, texts, updates, diagnostics and about. */
@Composable
fun SettingsPanel(state: AppState, copyDiagnostics: () -> Unit) {
    Sheet(S.settings, { state.panel = null }, 620.dp, side = false) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(S.look)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Look.entries.forEach { look -> LookCard(look, Palette.look == look, Modifier.weight(1f)) { state.changeLook(look) } }
            }
            Label(S.textsSection)
            Toggle(S.autoSend, state.autoSendTexts, S.autoSendHint) { state.changeAutoSend(it) }
            Label(S.phoneSection)
            Toggle(S.showNotifications, state.showNotifications, S.showNotificationsHint) { state.changeShowNotifications(it) }
            Toggle(S.playSound, state.notificationSound) { state.changeNotificationSound(it) }
            Label(S.sendToSection)
            SendToSetting()
            Label(S.backupSection)
            if (state.saved.isEmpty()) Text(S.backupNoPhones, color = Palette.muted, fontSize = 12.5.sp)
            state.saved.forEach { phone -> BackupSetting(state, phone) }
            Label(S.updatesSection)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(if (state.updating == S.checking) S.checking else S.checkUpdates, enabled = state.updating == null) { state.checkForUpdates(manual = true) }
                UpdatePill(state)
            }
            Label(S.help)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(S.copyDiagnostics, onClick = copyDiagnostics)
                Text(S.diagnosticsHint, color = Palette.muted, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            }
            Label(S.about)
            Text("OpenBridge · ${S.version(AppVersion.current)}", color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(S.aboutLine, color = Palette.muted, fontSize = 12.5.sp)
            GhostButton(S.github) { openPage("https://github.com/PredatorWoah/OpenBridge") }
        }
    }
}

/** "Add OpenBridge to Send to" (Windows) or "Open With" (Linux), for the installed app. */
@Composable
private fun SendToSetting() {
    var added by remember { mutableStateOf(Integration.installed) }
    var note by remember { mutableStateOf<String?>(null) }
    val available = Integration.launcher != null
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(if (added) S.removeSendTo else S.addSendTo, enabled = available) {
            val worked = if (added) Integration.remove() else Integration.add()
            if (worked) added = Integration.installed
            note = if (!worked) S.sendToFailed else if (added) S.sendToAdded else S.sendToRemoved
        }
        note?.let { Text(it, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
    }
    Text(if (available) S.sendToHint else S.sendToInstalled, color = Palette.muted, fontSize = 12.sp)
}

/** One saved phone's photo backup: on or off, and where to. */
@Composable
private fun BackupSetting(state: AppState, phone: SavedPhone) {
    val folder = state.backupFolder(phone)
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material.Icon(Icons.Filled.Smartphone, null, tint = Palette.amber, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(phone.name, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Toggle(S.backupAuto, folder != null, S.backupHint((folder ?: Backups.defaultFolder(phone.name)).path)) { state.changeBackup(phone, it) }
        if (folder != null) Row(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
            GhostButton(S.changeFolder) { Dialogs.chooseFolder(S.chooseBackupFolder)?.let { state.changeBackup(phone, true, it) } }
        }
    }
}

/** A look to choose, with a small live preview of its background and share button. */
@Composable
private fun LookCard(look: Look, chosen: Boolean, modifier: Modifier, choose: () -> Unit) {
    val engine = remember { SunEngine() }
    var frame by remember { mutableStateOf(SunEngine.Frame()) }
    var time by remember { mutableFloatStateOf(7f) }
    LaunchedEffect(engine) {
        // Lit, as when a phone is connected; with less motion, one still frame.
        repeat(240) { frame = engine.step(1.0 / 60, true, true, Platform.reduceMotion) }
        if (Platform.reduceMotion) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
            last = now
            frame = engine.step(dt, true, true, false); time += dt.toFloat()
        }
    }
    val name = if (java.util.Locale.getDefault().language == "zh") look.labelZh else look.label
    Column(modifier.clip(RoundedCornerShape(14.dp)).border(if (chosen) 2.dp else 1.dp, if (chosen) Palette.sun else Palette.fg.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
        .pressable(RoundedCornerShape(14.dp), role = Role.RadioButton, onClick = choose)
        .semantics(mergeDescendants = true) { selected = chosen; contentDescription = if (look.draft) "$name, ${S.preview}" else name }) {
        Box {
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                drawAs(look) {
                    drawBackdrop(look, time, energy = 1f, level = 0.4f)
                    drawHero(engine, frame, Offset(size.width / 2, size.height * 0.48f), size.height * 0.27f, size.height * 0.6f, look)
                }
            }
            if (look.draft) Text(S.preview, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Row(Modifier.fillMaxWidth().background(Palette.fg.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (chosen) androidx.compose.material.Icon(Icons.Filled.CheckCircle, null, tint = Palette.ok, modifier = Modifier.size(16.dp))
        }
    }
}
