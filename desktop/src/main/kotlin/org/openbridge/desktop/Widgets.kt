package org.openbridge.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshots.Snapshot
import org.openbridge.ui.Look
import org.openbridge.ui.Palette

val glassShape = RoundedCornerShape(18.dp)

/** The frosted panels everything sits on; the border is the current look's text colour, faint. */
fun Modifier.glass(shape: Shape = glassShape, border: Color? = null) = composed {
    clip(shape).background(Brush.linearGradient(listOf(Palette.fg.copy(alpha = 0.12f), Palette.fg.copy(alpha = 0.03f))))
        .border(1.dp, border ?: Palette.fg.copy(alpha = 0.14f), shape)
}

/** A clickable that keyboard users can see: a ring in the look's highlight colour while it has focus. */
fun Modifier.pressable(shape: Shape, enabled: Boolean = true, role: Role = Role.Button, label: String? = null, onClick: () -> Unit) = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    clip(shape).then(if (focused) Modifier.border(2.dp, Palette.sun, shape) else Modifier)
        .clickable(interaction, LocalIndication.current, enabled = enabled, onClickLabel = label, role = role, onClick = onClick)
}

@Composable
fun Label(text: String) = Text(text, color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)

@Composable
fun Field(value: String, change: (String) -> Unit, placeholder: String, modifier: Modifier, onEnter: (() -> Unit)? = null,
          focus: FocusRequester? = null, singleLine: Boolean = true, monospace: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.glass(RoundedCornerShape(10.dp), border = if (focused) Palette.sun.copy(alpha = 0.8f) else null)
        .onPreviewKeyEvent { event ->
            if (onEnter != null && event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) { onEnter(); true } else false
        }
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Palette.muted.copy(alpha = 0.7f), fontSize = 13.5.sp)
        BasicTextField(value, change, singleLine = singleLine, maxLines = if (singleLine) 1 else 6, cursorBrush = SolidColor(Palette.sun),
            textStyle = TextStyle(color = Palette.fg, fontSize = 13.5.sp, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier).onFocusChanged { focused = it.isFocused })
    }
}

@Composable
fun SunsetButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).background(Palette.sunset, alpha = if (enabled) 1f else 0.4f).pressable(CircleShape, enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
fun GhostButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(CircleShape).background(Palette.fg.copy(alpha = 0.08f)).border(1.dp, Palette.fg.copy(alpha = 0.14f), CircleShape)
        .pressable(CircleShape, enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Palette.fg else Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** A round icon button with a tooltip; `description` is also what screen readers say. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconButton(icon: ImageVector, description: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    TooltipArea(tooltip = {
        Text(description, color = Palette.fg, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.night.copy(alpha = 0.95f))
            .border(1.dp, Palette.fg.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
    }, delayMillis = 500, tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp))) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.08f)).pressable(CircleShape, enabled, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = if (enabled) tint ?: Palette.fg else Palette.muted.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

/** An on/off switch with its label, read as one control. */
@Composable
fun Toggle(label: String, on: Boolean, hint: String? = null, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(RoundedCornerShape(10.dp), role = Role.Switch) { change(!on) }
        .semantics(mergeDescendants = true) { stateDescription = if (on) S.on else S.off }.padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, color = Palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(40.dp, 22.dp).clip(CircleShape).background(if (on) Palette.sunset else SolidColor(Palette.fg.copy(alpha = 0.16f)))) {
            Box(Modifier.padding(3.dp).size(16.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(Color.White))
        }
    }
}

/** A small rounded label: the transport, "Preview" on draft looks. */
@Composable
fun Chip(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(Palette.fg.copy(alpha = 0.1f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Palette.amber, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = Palette.fg, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

fun transportIcon(transport: Transport): ImageVector = when (transport) {
    Transport.WIFI -> Icons.Filled.Wifi
    Transport.FAST_USB -> Icons.Filled.Bolt
    Transport.USB -> Icons.Filled.Usb
}

fun kindIcon(kind: Kind): Pair<ImageVector, Color> = when (kind) {
    Kind.FOLDER -> Icons.Filled.Folder to Palette.amber
    Kind.IMAGE -> Icons.Filled.Image to Palette.violet
    Kind.VIDEO -> Icons.Filled.Movie to Palette.pink
    Kind.AUDIO -> Icons.Filled.MusicNote to Palette.sun
    Kind.DOCUMENT -> Icons.Filled.Description to Palette.muted
    Kind.ARCHIVE -> Icons.Filled.FolderZip to Palette.muted
    Kind.APP -> Icons.Filled.Android to Palette.ok
    Kind.OTHER -> Icons.Filled.InsertDriveFile to Palette.muted
}

fun size(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0; var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}

/**
 * Draws as if `look` were the current one, for the previews in settings: the shared drawing reads the palette, so
 * it's switched in a snapshot of its own that is thrown away afterwards, and the rest of the window never sees it.
 */
fun DrawScope.drawAs(look: Look, block: DrawScope.() -> Unit) {
    if (Palette.look == look) { block(); return }
    val snapshot = Snapshot.takeMutableSnapshot()
    try { snapshot.enter { Palette.look = look; block() } } finally { snapshot.dispose() }
}

/** The transfer bar's fill, in the look's accent colours, ending at `width`. */
fun accentBrush(width: Float): Brush = Brush.horizontalGradient(*Palette.look.colors.accent.toTypedArray(), startX = 0f, endX = width)

/** Centre of a size, for drawing heroes. */
fun center(width: Float, height: Float) = Offset(width / 2, height / 2)
