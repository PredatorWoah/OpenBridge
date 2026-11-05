package org.openbridge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import java.text.DateFormat
import java.util.Date

/*
 * The phone's side of things from 1.2.0: its battery, its notifications (in a sheet in the main window and in the
 * tray's status window), Ring, Send link and the place to drop files to send. Every row is one line that ends in an
 * ellipsis, whatever the language.
 */

fun batteryIcon(battery: Battery): ImageVector = when {
    battery.charging -> Icons.Filled.BatteryChargingFull
    battery.level <= 15 -> Icons.Filled.BatteryAlert
    battery.level >= 95 -> Icons.Filled.BatteryFull
    battery.level >= 80 -> Icons.Filled.Battery6Bar
    battery.level >= 65 -> Icons.Filled.Battery5Bar
    battery.level >= 50 -> Icons.Filled.Battery4Bar
    battery.level >= 35 -> Icons.Filled.Battery3Bar
    battery.level >= 20 -> Icons.Filled.Battery2Bar
    else -> Icons.Filled.Battery1Bar
}

/** The phone's battery as a small chip: an icon and "87%"; read out in full. */
@Composable
fun BatteryChip(battery: Battery, modifier: Modifier = Modifier, fontSize: TextUnit = 11.5.sp) {
    val low = battery.level <= 15 && !battery.charging
    val described = S.batteryLevel(battery.level, battery.charging)
    Row(modifier.clip(CircleShape).background(if (low) Palette.pink.copy(alpha = 0.22f) else Palette.fg.copy(alpha = 0.1f))
        .semantics(mergeDescendants = true) { contentDescription = described }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(batteryIcon(battery), null, tint = if (low) Palette.pink else if (battery.charging) Palette.ok else Palette.amber, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text("${battery.level}%", color = Palette.fg, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** The open phone's battery chip, when the phone has said. */
@Composable
fun PhoneBattery(state: AppState, modifier: Modifier = Modifier) { state.battery?.let { BatteryChip(it, modifier) } }

/** The rows' shape and surface in each look: glass in Dusk, light in Aurora, a rounded float in Tide. */
private val rowShape: Shape get() = if (Palette.look == Look.TIDE) RoundedCornerShape(20.dp) else RoundedCornerShape(12.dp)

private fun Modifier.rowSurface(): Modifier = when (Palette.look) {
    Look.AURORA -> this.auroraGlow(false, false, RoundedCornerShape(12.dp), resting = true)
    Look.TIDE -> this.clip(RoundedCornerShape(20.dp)).background(Palette.night.copy(alpha = 0.42f)).border(1.dp, Palette.cream.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
    else -> this.glass(RoundedCornerShape(12.dp))
}

/** A small round icon button, for rows. */
@Composable
fun MiniIcon(icon: ImageVector, label: String, size: Dp = 28.dp, tint: Color = Palette.fg, onClick: () -> Unit) {
    Tip(label) {
        Box(Modifier.size(size).clip(CircleShape).background(Palette.fg.copy(alpha = 0.07f)).pressable(CircleShape, label = label, onClick = onClick)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * One notification on one line: "App · Title  text…", the one-time code as a button that copies it, Reply (which
 * opens a field under the line) and Dismiss. `time` adds the time it came.
 */
@Composable
fun NotificationRow(n: PhoneNotification, compact: Boolean, onCopy: (String) -> Unit, onDismiss: () -> Unit,
                    onReply: (String, () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var replying by remember(n.key) { mutableStateOf(false) }
    var draft by remember(n.key) { mutableStateOf("") }
    val heading = listOf(n.app, n.title).filter { it.isNotBlank() }.joinToString(" · ")
    val body = n.text.replace('\n', ' ').trim()
    Column(modifier.fillMaxWidth().rowSurface().padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.heightIn(min = if (compact) 24.dp else 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The app and title take at most part of the line, so some of the text always shows.
            val size = if (compact) 12.5.sp else 13.sp
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$heading. $body" }, verticalAlignment = Alignment.CenterVertically) {
                Text(heading, color = Palette.fg, fontWeight = FontWeight.SemiBold, fontSize = size, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 120.dp else 170.dp))
                if (body.isNotEmpty()) Text(body, color = Palette.muted, fontSize = size, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f))
            }
            if (!compact) Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.time)), color = Palette.muted, fontSize = 11.sp, maxLines = 1, softWrap = false)
            if (n.code.isNotEmpty()) {
                Row(Modifier.clip(CircleShape).background(Palette.amber.copy(alpha = 0.2f)).pressable(CircleShape, label = S.copyCode) { onCopy(n.code) }
                    .semantics(mergeDescendants = true) { contentDescription = "${S.copyCode} ${n.code}" }.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentCopy, null, tint = Palette.amber, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(n.code, color = Palette.fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                }
            }
            if (n.canReply) MiniIcon(Icons.Filled.Reply, S.replyTo(n.title.ifBlank { n.app }), if (compact) 24.dp else 28.dp) { replying = !replying }
            if (n.canDismiss) MiniIcon(Icons.Filled.Close, S.dismiss, if (compact) 24.dp else 28.dp, onClick = onDismiss)
        }
        if (replying) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val send = { if (draft.isNotBlank()) onReply(draft) { draft = ""; replying = false } }
            Field(draft, { draft = it }, S.typeReply, Modifier.weight(1f), onEnter = send, monospace = false)
            MiniIcon(Icons.Filled.Send, S.reply, if (compact) 28.dp else 32.dp, tint = Palette.amber, onClick = send)
        }
    }
}

@Composable
fun NotificationRow(state: AppState, n: PhoneNotification, compact: Boolean, modifier: Modifier = Modifier) =
    NotificationRow(n, compact, onCopy = state::copyCode, onDismiss = { state.dismissNotification(n.key) },
        onReply = { text, done -> state.replyTo(n.key, text, done) }, modifier = modifier)

/** Where files dropped are sent to the phone: a dashed frame with one line of text, brighter while files are over it. */
@Composable
fun DropZone(text: String, active: Boolean, modifier: Modifier = Modifier, height: Dp = 54.dp, action: (@Composable () -> Unit)? = null) {
    val color = if (active) Palette.sun else Palette.fg.copy(alpha = 0.28f)
    Row(modifier.fillMaxWidth().height(height).clip(rowShape).background(if (active) Palette.amber.copy(alpha = 0.16f) else Palette.fg.copy(alpha = 0.04f))
        .drawBehind {
            val r = if (Palette.look == Look.TIDE) 20.dp.toPx() else 12.dp.toPx()
            drawRoundRect(color, cornerRadius = CornerRadius(r), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))))
        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SendToMobile, null, tint = if (active) Palette.sun else Palette.amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null) { Spacer(Modifier.width(8.dp)); action() }
    }
}

/** The main window's sheet for the phone: battery, Ring, Send link, the drop zone, and its notifications. */
@Composable
fun NotificationsPanel(state: AppState, dropping: Boolean) {
    Sheet(S.phoneNotifications, { state.panel = null }, 440.dp, side = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.deviceName, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            PhoneBattery(state)
            Spacer(Modifier.weight(1f))
            if (state.companion) GhostButton(if (state.ringing) S.stopRinging else S.ringPhone) { state.ring() }
        }
        if (!state.companion) {
            if (state.bridge != null) Hint(S.companionUpdate, Icons.Filled.SystemUpdate)
        } else {
            var link by remember { mutableStateOf(state.clipboardLink() ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(link, { link = it }, S.pasteLink, Modifier.weight(1f), onEnter = { state.sendLink(link) { link = "" } }, monospace = false)
                MiniIcon(Icons.Filled.Link, S.sendLink, 34.dp, tint = Palette.amber) { state.sendLink(link) { link = "" } }
            }
        }
        NowPlayingCard(state)
        DropZone(S.dropToSend(state.deviceName), dropping) {
            MiniIcon(Icons.Filled.AttachFile, S.sendFiles.trimEnd('…'), 30.dp) { state.sendToPhone(Dialogs.chooseUploads()) }
        }
        val items = state.phoneStatus.items
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(S.notifications.uppercase())
            if (items.isNotEmpty()) Text("  ${items.size}", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (items.isEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(S.noNotifications, color = Palette.fg, fontSize = 13.sp)
            if (state.companion) Text(S.notificationsHint, color = Palette.muted, fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.key }) { n -> NotificationRow(state, n, compact = false) }
        }
    }
}

/** The tray status window's part for the phone: Ring and Send link, the drop zone, and the newest notifications. */
@Composable
fun TrayPhone(state: AppState, dropping: Boolean) {
    if (state.source == null) {
        if (state.sendQueue.isNotEmpty()) Text(S.sendWaiting(state.sendQueue.size, state.saved.firstOrNull()?.name), color = Palette.muted, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    if (!state.companion) {
        if (state.bridge != null && state.phoneInfo != null) Text(S.companionUpdate, color = Palette.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    var editing by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    NowPlayingCard(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrayButton(if (state.ringing) S.stopRinging else S.ringPhone, modifier = Modifier.weight(1f)) { state.ring() }
        TrayButton(S.sendLinkShort, modifier = Modifier.weight(1f)) {
            val copied = state.clipboardLink()
            if (copied != null) state.sendLink(copied) else editing = !editing
        }
    }
    if (editing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val send = { state.sendLink(link) { link = ""; editing = false } }
        Field(link, { link = it }, S.pasteLink, Modifier.weight(1f), onEnter = send, monospace = false)
        MiniIcon(Icons.Filled.Send, S.sendLink, 30.dp, tint = Palette.amber, onClick = send)
    }
    DropZone(S.dropToSend(state.deviceName), dropping, height = 44.dp)
    val items = state.phoneStatus.items
    if (items.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(S.notifications, color = Palette.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        items.take(5).forEach { n -> NotificationRow(state, n, compact = true) }
        if (items.size > 5) Text(S.moreNotifications(items.size - 5), color = Palette.amber, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.pressable(RoundedCornerShape(6.dp)) { state.openNotifications() }.padding(4.dp))
    }
}

/** How many notifications the phone has, on the button that opens them. */
@Composable
fun NotificationBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.sizeIn(minWidth = 16.dp, minHeight = 16.dp).clip(CircleShape).background(Palette.sunset).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** Opens the phone's sheet: notifications, Ring, Send link and files. */
@Composable
fun NotificationsButton(state: AppState) {
    Box {
        IconButton(Icons.Filled.Notifications, S.phoneNotifications) { state.panel = Panel.NOTIFICATIONS }
        NotificationBadge(state.phoneStatus.items.size, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
    }
}
