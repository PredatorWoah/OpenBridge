package org.openbridge.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawHero
import javax.swing.JOptionPane

/** The share button in the current look, drawn live with the same engine and values as the phone's. */
@Composable
fun Hero(active: Boolean, connected: Boolean, modifier: Modifier) {
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
            // With less motion the hero rests at the top and only warms up when a phone connects.
            frame = if (Platform.reduceMotion) engine.step(dt, connectedNow, connectedNow, true) else engine.step(dt, activeNow, connectedNow, false)
            tick = now
        }
    }
    Canvas(modifier) {
        tick
        drawHero(engine, frame, Offset(size.width / 2, size.height / 2), minOf(size.width, size.height) * 0.27f, 280.dp.toPx())
    }
}

@Composable
fun ConnectScreen(state: AppState) {
    Column(Modifier.fillMaxSize()) {
        TopBar(state, Modifier.padding(start = 28.dp, end = 20.dp, top = 12.dp))
        Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("OpenBridge", color = Palette.fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(S.tagline, color = Palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                Hero(active = true, connected = state.pairingCode != null, modifier = Modifier.weight(1f).fillMaxWidth())
                Text(S.waiting, color = Palette.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(S.waitingHint, color = Palette.muted, fontSize = 13.5.sp, textAlign = TextAlign.Center)
            }
            Column(Modifier.width(430.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.saved.isNotEmpty()) SavedPhones(state)
                NearbyCard(state)
                if (state.isolated) Hint(S.isolated, Icons.Filled.WifiOff)
                UsbCard(state)
            }
        }
    }
}

/** The update pill and the settings button, top right on both screens. */
@Composable
fun TopBar(state: AppState, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit = { Spacer(Modifier.weight(1f)) }) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
        UpdatePill(state)
        IconButton(Icons.Filled.Settings, S.settings) { state.panel = Panel.SETTINGS }
    }
}

/** A newer release: Update installs it, What's new opens the release page. Shows the download's progress. */
@Composable
fun UpdatePill(state: AppState) {
    val release = state.update
    val note = state.updating
    if (release == null && note == null) return
    Row(Modifier.clip(CircleShape).background(Palette.amber.copy(alpha = 0.18f)).padding(start = 14.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
        .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(note ?: S.update(release!!.version), color = Palette.fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            modifier = Modifier.widthIn(max = 420.dp))
        val progress = state.updateProgress
        if (progress != null) {
            Canvas(Modifier.size(70.dp, 6.dp)) {
                drawRoundRect(Palette.fg.copy(alpha = 0.15f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                drawRoundRect(accentBrush(size.width * progress), size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            }
        }
        when {
            note != null && progress == null && release != null && note != S.checking -> IconButton(Icons.Filled.Close, S.close) { state.dismissUpdateNote() }
            note == null && release != null -> {
                if (state.canInstallUpdate) SunsetButton(S.updateNow, enabled = !state.busy) { state.installUpdate(::openPage) }
                GhostButton(S.whatsNew) { openPage(release.page) }
            }
        }
    }
}

/** Phones paired before: they reconnect by themselves, or with a click. */
@Composable
private fun SavedPhones(state: AppState) {
    Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(S.yourPhones)
        state.saved.forEach { phone ->
            val cable = state.onCable(phone) != null
            val here = cable || state.isNearby(phone)
            val where = when { cable -> S.onCableNow; here -> S.nearbyNow; else -> S.reconnects }
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp), if (here) Palette.ok.copy(alpha = 0.45f) else null)
                .semantics(mergeDescendants = true) { contentDescription = "${phone.name}, $where" }
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (cable) Icons.Filled.Bolt else Icons.Filled.Smartphone, null, tint = if (here) Palette.ok else Palette.fg)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(phone.name, color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(where, color = Palette.muted, fontSize = 11.5.sp)
                }
                GhostButton(S.connect, enabled = !state.busy) { state.connect(phone) }
                Spacer(Modifier.width(6.dp))
                IconButton(Icons.Filled.Close, "${S.forget}: ${phone.name}") {
                    if (JOptionPane.showConfirmDialog(null, S.forgetConfirm(phone.name), S.forget, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                        state.forgetPhone(phone)
                }
            }
        }
    }
}

@Composable
private fun NearbyCard(state: AppState) {
    Column(Modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(S.nearby)
        val unsaved = state.nearby.filter { phone -> state.saved.none { it.id == phone.id } }
        if (unsaved.isEmpty()) Text(if (state.searchedLong) S.notFound else S.looking, color = Palette.muted, fontSize = 12.5.sp)
        unsaved.forEach { phone ->
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).pressable(RoundedCornerShape(12.dp), !state.busy, label = S.pair) { state.pairWith(phone) }
                .semantics(mergeDescendants = true) { contentDescription = "${phone.name}, ${phone.host}" }.padding(12.dp),
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
            Field(state.host, { state.host = it }, S.address, Modifier.weight(1f), onEnter = { if (!state.busy && state.host.isNotBlank()) state.pair() })
            Field(state.port, { state.port = it }, S.port, Modifier.width(90.dp), onEnter = { if (!state.busy && state.host.isNotBlank()) state.pair() })
        }
        SunsetButton(S.pair, enabled = !state.busy && state.host.isNotBlank() && state.port.isNotBlank(), modifier = Modifier.fillMaxWidth()) { state.pair() }
        if (state.pairingOver == null) Pairing(state)
    }
}

/** The 6-digit code to compare with the phone's, the progress of connecting, and what went wrong. */
@Composable
private fun Pairing(state: AppState) {
    state.pairingCode?.let { code ->
        Text(S.compare, color = Palette.muted, fontSize = 12.sp)
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${S.compare} ${code.toList().joinToString(" ")}"; liveRegion = LiveRegionMode.Assertive },
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            code.forEachIndexed { index, digit ->
                if (index == 3) Spacer(Modifier.width(8.dp))
                Box(Modifier.size(44.dp, 56.dp).glass(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(digit.toString(), color = Palette.fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (state.busy && state.pairingCode == null && state.status.isNotEmpty())
        Text(state.status, color = Palette.muted, fontSize = 12.5.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (state.busy) GhostButton(S.cancel) { state.cancel() }
    state.error?.let { Text(it, color = Palette.amber, fontSize = 12.5.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

/** A calm explanation in its own card. */
@Composable
fun Hint(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, dismiss: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().glass(border = Palette.amber.copy(alpha = 0.4f)).padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.amber, modifier = Modifier.size(20.dp))
        Text(text, color = Palette.fg, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        if (dismiss != null) IconButton(Icons.Filled.Close, S.close, onClick = dismiss)
    }
}

/** Phones on the cable: in File transfer mode (Windows) to browse here, and with USB debugging for Fast USB. */
@Composable
private fun UsbCard(state: AppState) {
    val found = state.wpdPhones.isNotEmpty() || state.usbLinks.isNotEmpty()
    Column(Modifier.fillMaxWidth().glass(border = if (found) Palette.ok.copy(alpha = 0.45f) else null).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(S.cable)
        state.wpdPhones.forEach { phone ->
            DeviceRow(Icons.Filled.Usb, phone.name, S.fileTransfer) {
                SunsetButton(S.open, enabled = !state.busy) { state.openWpd(phone) }
            }
            if (Platform.windows) GhostButton(S.openExplorer, modifier = Modifier.padding(start = 34.dp)) { Platform.openThisPc() }
        }
        state.usbLinks.forEach { link ->
            val (status, ok) = when (link.state) {
                UsbLink.State.READY -> (if (link.phone != null) S.fastUsbReady else S.fastUsbNew) to true
                UsbLink.State.UNAUTHORIZED -> S.allowDebugging to false
                UsbLink.State.NO_PERMISSION -> S.noPermission to false
                UsbLink.State.STARTING -> S.startingOnPhone to false
                UsbLink.State.NOT_SHARING -> S.notSharing to false
                UsbLink.State.NO_APP -> S.noApp to false
            }
            DeviceRow(Icons.Filled.Bolt, link.name, status, ok) {
                when {
                    link.ready && link.phone != null -> GhostButton(S.connect, enabled = !state.busy) { state.connectUsb(link) }
                    link.ready -> SunsetButton(S.pairOverUsb, enabled = !state.busy) { state.pairOverUsb(link) }
                }
            }
            if (state.pairingOver == link.serial) Pairing(state)
        }
        if (!found) Text(S.cableHint, color = Palette.muted, fontSize = 12.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(S.mirror, enabled = !state.mirroring) { state.mirror() }
            if (state.usbLinks.any { it.state != UsbLink.State.UNAUTHORIZED }) GhostButton(S.mirrorWireless, enabled = !state.mirroring) { state.mirror(wireless = true) }
        }
        Text(S.mirrorHint, color = Palette.muted.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}

@Composable
private fun DeviceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, status: String, ok: Boolean = true, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name, $status" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (ok) Palette.ok else Palette.amber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Palette.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(status, color = Palette.muted, fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

fun openPage(url: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }.onFailure { if (!Platform.windows) Platform.exitCode(listOf("xdg-open", url)) } }
