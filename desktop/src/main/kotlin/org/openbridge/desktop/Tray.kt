package org.openbridge.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import org.openbridge.ui.Palette
import java.awt.BasicStroke
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin

/*
 * The system tray: an icon that shows how the phone is (an outline sun when nothing is connected, a filled one when
 * a phone is, a ring filling around it while files move), a one-line tooltip, a menu (Open OpenBridge, Disconnect,
 * Quit), and a small status window that opens when the icon is clicked. Where the desktop has no tray (some Linux
 * desktops), none of this shows.
 *
 * The icon is AWT's own TrayIcon: Compose's Tray only reports double-clicks on Windows and never where the click was,
 * and the status window opens on a single click, next to the icon.
 */

/** What the tray shows, read from the app's state a few times a second. */
data class TrayStatus(
    val connected: Boolean,
    val phone: String,
    val transport: String,
    /** Files are moving now. */
    val moving: Boolean = false,
    val upload: Boolean = false,
    val file: String = "",
    /** The whole queue, 0 to 1. */
    val fraction: Float = 0f,
    val index: Int = 1,
    val count: Int = 1,
    /** Bytes a second, smoothed. */
    val rate: Double = 0.0,
    val secondsLeft: Long? = null,
) {
    /** One line for the tray icon's tooltip, like "OpenBridge · Pixel 8 · 42% · 18.3 MB/s". */
    fun tooltip(): String {
        val parts = mutableListOf("OpenBridge")
        if (!connected) parts += S.notConnected
        else {
            parts += shorten(phone, 40)
            if (moving) {
                parts += "${(fraction * 100).toInt()}%"
                if (rate >= 1) parts += speed(rate)
            }
        }
        // Windows cuts tooltips at 127 characters.
        return shorten(parts.joinToString(" · "), 120)
    }
}

/** Bytes a second as "18.3 MB/s". */
fun speed(rate: Double): String = size(rate.toLong()) + "/s"

/** `text` cut to `max` characters, with an ellipsis in the middle. */
fun shorten(text: String, max: Int): String =
    if (text.length <= max) text else text.take((max - 1) / 2 + (max - 1) % 2) + "…" + text.takeLast((max - 1) / 2)

fun trayStatus(state: AppState): TrayStatus {
    val transfer = state.transfer
    val connected = state.source != null
    val moving = connected && transfer != null && !transfer.done && state.busy
    if (!moving || transfer == null) return TrayStatus(connected, state.deviceName, state.transport.label)
    val rate = state.rate()
    return TrayStatus(true, state.deviceName, state.transport.label, moving = true, upload = transfer.upload, file = transfer.name,
        fraction = transfer.progress, index = transfer.index, count = transfer.count, rate = rate,
        secondsLeft = if (transfer.bytesTotal > 0) secondsLeft(transfer.bytesTotal - transfer.bytesDone, rate) else null)
}

/** The tray icon, its menu, and the status window; nothing where there's no tray. */
@Composable
fun ApplicationScope.StatusTray(state: AppState, openMain: () -> Unit) {
    val supported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
    if (!supported) return
    var anchor by remember { mutableStateOf<Point?>(null) }
    // When the status window last closed by losing focus: a click on the icon that caused it doesn't reopen it.
    var closedAt by remember { mutableStateOf(0L) }
    val toggle = rememberUpdatedState { at: Point ->
        if (anchor != null) anchor = null
        else if (System.currentTimeMillis() - closedAt > 300) anchor = at
    }
    val open = rememberUpdatedState(openMain)
    val quit = rememberUpdatedState { exitApplication() }
    val disconnectItem = remember { java.awt.MenuItem(S.disconnect) }
    val icon = remember {
        val size = runCatching { SystemTray.getSystemTray().trayIconSize }.getOrNull()
        TrayIcon(trayImage(trayIconPixels(size?.width ?: 16), trayStatus(state)), "OpenBridge").apply {
            isImageAutoSize = true
            popupMenu = java.awt.PopupMenu().apply {
                add(java.awt.MenuItem(S.openApp).apply { addActionListener { open.value() } })
                add(disconnectItem.apply { addActionListener { state.disconnect() } })
                addSeparator()
                add(java.awt.MenuItem(S.quit).apply { addActionListener { quit.value() } })
            }
            // Windows reports a double click as an action: open the app then.
            addActionListener { anchor = null; open.value() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) toggle.value(e.point)
                }
            })
        }
    }
    DisposableEffect(icon) {
        val added = runCatching { SystemTray.getSystemTray().add(icon) }.isSuccess
        if (!added) Log.add("No system tray here")
        onDispose { runCatching { SystemTray.getSystemTray().remove(icon) } }
    }
    // A few times a second: the icon's picture (only when it looks different) and the tooltip.
    LaunchedEffect(icon) {
        var shown: Any? = null
        var tip = ""
        val pixels = trayIconPixels(icon.size.width.takeIf { it > 0 } ?: 16)
        while (true) {
            val status = trayStatus(state)
            val look = Palette.look
            val key = listOf(status.connected, status.moving, if (status.moving) (status.fraction * 100).toInt() else 0, look)
            if (key != shown) { icon.image = trayImage(pixels, status); shown = key }
            val text = status.tooltip()
            if (text != tip) { icon.toolTip = text; tip = text }
            disconnectItem.isEnabled = status.connected
            delay(250)
        }
    }
    anchor?.let { at ->
        StatusWindow(state, at, onClose = { anchor = null; closedAt = System.currentTimeMillis() }, openMain = { anchor = null; open.value() })
    }
}

/** Pixels to draw the icon at: twice the tray's size, so it stays sharp when scaled for the screen. */
private fun trayIconPixels(traySize: Int) = (traySize.coerceIn(16, 64)) * 2

/**
 * The tray icon: OpenBridge's sun, as an outline when nothing is connected, filled in the look's colours when a
 * phone is, smaller inside a ring that fills with the queue while files move. A faint dark edge keeps it visible on
 * light taskbars too.
 */
fun trayImage(pixels: Int, status: TrayStatus): BufferedImage {
    val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val s = pixels.toFloat()
    val c = s / 2
    val colors = Palette.look.colors
    val accent = java.awt.Color(colors.amber.toArgb(), true)
    val light = java.awt.Color(colors.sun.toArgb(), true)
    val edge = java.awt.Color(0, 0, 0, 110)
    val grey = java.awt.Color(0xC8, 0xC4, 0xDC)
    fun stroke(width: Float) = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    fun rays(inner: Float, outer: Float, width: Float, color: java.awt.Color) {
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            val line = Line2D.Float(c + inner * cos(a).toFloat(), c + inner * sin(a).toFloat(), c + outer * cos(a).toFloat(), c + outer * sin(a).toFloat())
            g.color = edge; g.stroke = stroke(width + s * 0.05f); g.draw(line)
            g.color = color; g.stroke = stroke(width); g.draw(line)
        }
    }
    fun disc(r: Float) = Ellipse2D.Float(c - r, c - r, 2 * r, 2 * r)
    when {
        status.moving -> {
            val r = s * 0.38f
            val width = s * 0.12f
            g.stroke = stroke(width + s * 0.05f); g.color = edge; g.draw(disc(r))
            g.stroke = stroke(width); g.color = java.awt.Color(255, 255, 255, 70); g.draw(disc(r))
            g.color = accent
            g.draw(Arc2D.Float(c - r, c - r, 2 * r, 2 * r, 90f, -360f * status.fraction.coerceIn(0.02f, 1f), Arc2D.OPEN))
            val core = disc(s * 0.17f)
            g.color = edge; g.fill(disc(s * 0.17f + s * 0.03f))
            g.paint = java.awt.GradientPaint(c, c - s * 0.17f, light, c, c + s * 0.17f, accent); g.fill(core)
        }
        status.connected -> {
            rays(s * 0.33f, s * 0.46f, s * 0.08f, accent)
            g.color = edge; g.fill(disc(s * 0.27f))
            g.paint = java.awt.GradientPaint(c, c - s * 0.25f, light, c, c + s * 0.25f, accent); g.fill(disc(s * 0.245f))
        }
        else -> {
            rays(s * 0.33f, s * 0.46f, s * 0.07f, grey)
            val ring = disc(s * 0.21f)
            g.color = edge; g.stroke = stroke(s * 0.12f); g.draw(ring)
            g.color = grey; g.stroke = stroke(s * 0.075f); g.draw(ring)
        }
    }
    g.dispose()
    return image
}

/**
 * The small window the tray icon opens, next to it: undecorated, rounded, always on top, in the look's colours.
 * It closes when it loses focus, or with Esc.
 */
@Composable
private fun StatusWindow(state: AppState, at: Point, onClose: () -> Unit, openMain: () -> Unit) {
    // Where it's likely to go, so it doesn't flash elsewhere first; it's moved into place once its height is known.
    val guess = remember { guessPlace(at, 320, 220) }
    val windowState = rememberWindowState(size = DpSize(320.dp, Dp.Unspecified), position = WindowPosition(guess.x.dp, guess.y.dp))
    Window(onCloseRequest = onClose, state = windowState, title = "OpenBridge", undecorated = true, transparent = true, resizable = false,
        alwaysOnTop = true, focusable = true,
        onKeyEvent = { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false }) {
        val awt = window
        DisposableEffect(awt) {
            val listener = object : WindowAdapter() { override fun windowLostFocus(e: WindowEvent) { onClose() } }
            val sized = object : ComponentAdapter() { override fun componentResized(e: ComponentEvent) { place(awt, at) } }
            awt.addWindowFocusListener(listener)
            awt.addComponentListener(sized)
            place(awt, at)
            awt.isAutoRequestFocus = true
            awt.toFront(); awt.requestFocus()
            onDispose { awt.removeWindowFocusListener(listener); awt.removeComponentListener(sized) }
        }
        var status by remember { mutableStateOf(trayStatus(state)) }
        LaunchedEffect(Unit) { while (true) { status = trayStatus(state); delay(250) } }
        StatusCard(status, onOpen = openMain, onDisconnect = { state.disconnect() }, onCancel = { state.cancel() })
    }
}

/** Puts the window by the point the tray was clicked, inside the screen's free area (above a bottom taskbar, below a top panel). */
private fun place(window: java.awt.Window, at: Point) {
    val spot = guessPlace(at, window.width, window.height)
    if (window.x != spot.x || window.y != spot.y) window.setLocation(spot.x, spot.y)
}

private fun guessPlace(at: Point, width: Int, height: Int): Point {
    val config = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(at) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    }.getOrNull() ?: return at
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val free = Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    val gap = 8
    val x = (at.x - width / 2).coerceIn(free.x + gap, maxOf(free.x + gap, free.x + free.width - width - gap))
    val below = at.y < free.centerY
    val y = (if (below) at.y + gap else at.y - height - gap).coerceIn(free.y + gap, maxOf(free.y + gap, free.y + free.height - height - gap))
    return Point(x, y)
}

/** What the status window shows: the phone, how it's connected, the transfer moving now, and the buttons. */
@Composable
fun StatusCard(status: TrayStatus, onOpen: () -> Unit, onDisconnect: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.width(320.dp).clip(shape).background(Brush.verticalGradient(listOf(Palette.dusk, Palette.night)))
        .border(1.dp, Palette.amber.copy(alpha = 0.32f), shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = if (status.connected) Palette.ok else Palette.muted
            Canvas(Modifier.size(12.dp)) {
                if (status.connected) drawCircle(dot.copy(alpha = 0.3f), size.minDimension / 2)
                drawCircle(dot, size.minDimension / 2 * 0.62f)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(if (status.connected) status.phone else "OpenBridge", color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (status.connected) "${status.transport} · ${S.connectedWord}" else S.notConnected, color = Palette.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (status.moving) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (status.upload) Icons.Filled.Upload else Icons.Filled.Download, null, tint = Palette.amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    MiddleText(status.file, Palette.fg, 13.sp, Modifier.weight(1f))
                    if (status.count > 1) Text(S.ofCount(status.index, status.count), color = Palette.muted, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 130.dp))
                }
                val shown by animateFloatAsState(status.fraction, tween(if (Platform.reduceMotion) 0 else 450))
                Canvas(Modifier.fillMaxWidth().height(8.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(status.fraction, 0f..1f) }) {
                    val h = size.height
                    drawRoundRect(Palette.fg.copy(alpha = 0.1f), Offset.Zero, size, CornerRadius(h / 2))
                    val width = maxOf(h, size.width * shown)
                    drawRoundRect(accentBrush(width), Offset.Zero, Size(width, h), CornerRadius(h / 2))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(status.fraction * 100).toInt()}%", color = Palette.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (status.rate >= 1) Text(" · " + speed(status.rate), color = Palette.fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    status.secondsLeft?.let { left ->
                        Text(S.timeLeft(left), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 150.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayButton(S.openApp, primary = true, modifier = Modifier.weight(1f), onClick = onOpen)
        }
        if (status.connected) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrayButton(S.disconnect, modifier = Modifier.weight(1f), onClick = onDisconnect)
            if (status.moving) TrayButton(S.cancelTransfer, modifier = Modifier.weight(1f), onClick = onCancel)
        }
    }
}

/** A button that never wraps: its label shortens with an ellipsis instead. */
@Composable
private fun TrayButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val base = modifier.clip(CircleShape)
        .then(if (primary) Modifier.background(Palette.sunset) else Modifier.background(Palette.fg.copy(alpha = 0.08f)).border(1.dp, Palette.fg.copy(alpha = 0.14f), CircleShape))
    Box(base.pressable(CircleShape, label = text, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) Color.White else Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One line of text that shortens in the middle ("IMG_2026…final.jpg"), so both the start and the end of a name show. */
@Composable
fun MiddleText(text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = color, fontSize = fontSize)
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val shown = remember(text, room, style) {
            if (room == Constraints.Infinity) text else middleFit(text, room) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        }
        Text(shown, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.semantics { contentDescription = text })
    }
}

/** The longest middle-shortened form of `text` that is at most `room` wide, by `width`. */
fun middleFit(text: String, room: Int, width: (String) -> Int): String {
    if (width(text) <= room) return text
    fun cut(keep: Int) = text.take(keep - keep / 2) + "…" + text.takeLast(keep / 2)
    var low = 0; var high = text.length - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (width(cut(mid)) <= room) low = mid else high = mid - 1
    }
    return cut(low)
}
