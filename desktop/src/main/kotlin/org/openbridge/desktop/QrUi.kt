package org.openbridge.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import kotlin.math.floor
import kotlin.math.sin

/*
 * Pairing by QR code on the connect screens: the button that shows the code, and the code itself in each look's
 * frame (Dusk's glass card, Aurora's halo with a ring of light counting down, Tide's porthole draining as time runs
 * out), then the moment it pairs.
 */

/** The modules of a QR code for `text`, without its quiet zone (drawn around it). */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"))

/**
 * The code, crisp: every module a whole number of pixels, dark on white with a quiet zone (with the tile) of four modules or more, so
 * phone cameras read it in any look. `dim` fades it when it expired.
 */
@Composable
fun QrMatrix(text: String, modifier: Modifier, dim: Boolean = false) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.semantics { contentDescription = S.qrDescription }) {
        val quiet = 3   // with the white tile around it, that is more than the four modules a reader needs
        val modules = matrix.width + 2 * quiet
        val module = floor(minOf(size.width, size.height) / modules).coerceAtLeast(1f)
        val side = module * modules
        val left = floor((size.width - side) / 2); val top = floor((size.height - side) / 2)
        drawRect(Color.White, Offset(left, top), Size(side, side))
        val ink = Color(0xFF101018).copy(alpha = if (dim) 0.18f else 1f)
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix[x, y]) { x++; continue }
                val start = x
                while (x < matrix.width && matrix[x, y]) x++
                drawRect(ink, Offset(left + (start + quiet) * module, top + (y + quiet) * module), Size((x - start) * module, module))
            }
        }
    }
}

/** "Pair with QR code", in the look's way: Dusk's sunset button, a quiet pill in Aurora and Tide. */
@Composable
fun QrButton(state: AppState, modifier: Modifier = Modifier) {
    val enabled = !state.busy
    val accent = Palette.look != Look.DUSK
    Row(modifier.clip(CircleShape)
        .then(if (accent) Modifier.background(Palette.night.copy(alpha = 0.5f)).border(1.dp, Palette.amber.copy(alpha = 0.55f), CircleShape)
            else Modifier.background(Palette.sunset, alpha = if (enabled) 1f else 0.4f))
        .pressable(CircleShape, enabled, label = S.pairWithQr) { state.showQr() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.QrCode2, null, tint = if (accent) Palette.amber else Color.White, modifier = Modifier.size(18.dp))
        Text(S.pairWithQr, color = if (accent) Palette.fg else Color.White, fontSize = 13.5.sp, fontWeight = if (Palette.look == Look.AURORA) FontWeight.Medium else FontWeight.Bold,
            letterSpacing = if (Palette.look == Look.AURORA) 0.6.sp else 0.sp, maxLines = 1, softWrap = false)
    }
}

/** The clock for a code: now, a few times a second. */
@Composable
private fun rememberNow(code: QrCode): Long {
    var now by remember(code) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(code) { while (true) { delay(250); now = System.currentTimeMillis() } }
    return now
}

/**
 * The QR code card in the current look, at most `side` across for the code. Shows the countdown, a new code when
 * it expired, the old phones that need the 6-digit code, and the success once a phone paired.
 */
@Composable
fun QrCard(state: AppState, side: Dp, modifier: Modifier = Modifier) {
    val code = state.qr ?: return
    val now = rememberNow(code)
    val paired = state.qrPaired
    val expired = paired == null && code.expired(now)
    val left = ((code.expires - now).coerceAtLeast(0).toFloat() / QrPairing.LIFETIME).coerceIn(0f, 1f)
    val time = animationTime()
    val qrSide = side.coerceIn(120.dp, 340.dp)
    val narrow = qrSide < 200.dp
    val picture: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.Center) {
            if (paired != null) PairedBurst(Modifier.fillMaxSize(0.8f))
            else {
                QrMatrix(code.text, Modifier.fillMaxSize(), dim = expired)
                if (expired) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S.qrExpired, color = Color(0xFF202030), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    SunsetButton(S.qrNew) { state.showQr() }
                }
            }
        }
    }
    val caption: @Composable () -> Unit = {
        when {
            paired != null -> Text(S.qrPaired(paired), color = Palette.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            else -> {
                val parts: @Composable () -> Unit = {
                    Text(if (expired) S.qrExpired else S.qrExpiresIn(code.secondsLeft(now)), color = if (expired) Palette.amber else Palette.muted, fontSize = 12.5.sp,
                        maxLines = 1, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    // A narrow card has room for the button's icon only.
                    if (!expired) { if (narrow) IconButton(Icons.Filled.Refresh, S.qrNew) { state.showQr() } else GhostButton(S.qrNew) { state.showQr() } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 10.dp)) { parts() }
            }
        }
        if (paired == null && state.qrOld.isNotEmpty())
            Text(S.qrOld(state.qrOld.joinToString(", ")), color = Palette.amber, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = qrSide + 80.dp))
    }
    val close: @Composable () -> Unit = { if (paired == null) IconButton(Icons.Filled.Close, S.close) { state.closeQr() } }
    val title: @Composable (Modifier) -> Unit = { m ->
        Text(S.qrTitle, color = Palette.fg, fontSize = if (narrow) 14.sp else if (Palette.look == Look.AURORA) 18.sp else 17.sp,
            fontWeight = if (Palette.look == Look.AURORA) FontWeight.Light else FontWeight.Bold, letterSpacing = if (Palette.look == Look.AURORA) 1.5.sp else 0.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m.semantics { heading() })
    }
    val hint: @Composable () -> Unit = {
        if (paired == null && !narrow) Text(S.qrHint, color = Palette.muted, fontSize = 12.5.sp, textAlign = TextAlign.Center, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = qrSide + 80.dp))
    }
    when (Palette.look) {
        Look.DUSK -> Column(modifier.glass(RoundedCornerShape(22.dp), border = Palette.amber.copy(alpha = 0.45f)).padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.width(qrSide + 24.dp), verticalAlignment = Alignment.CenterVertically) { title(Modifier.weight(1f)); close() }
            // The code on a white tile ringed with the sunset.
            picture(Modifier.size(qrSide + 12.dp).clip(RoundedCornerShape(18.dp)).background(Palette.sunset).padding(4.dp)
                .clip(RoundedCornerShape(15.dp)).background(Color.White).padding(4.dp))
            // The countdown: the sunset bar shortening.
            if (paired == null) Box(Modifier.width(qrSide).height(5.dp).clip(CircleShape).background(Palette.fg.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(left.coerceAtLeast(0.01f)).background(Palette.sunset))
            }
            caption()
            hint()
        }
        Look.AURORA -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.width(qrSide + 60.dp), verticalAlignment = Alignment.CenterVertically) { Spacer(Modifier.width(34.dp)); title(Modifier.weight(1f).wrapContentWidth()); close() }
            // The code floating in a halo, a ring of light around it running down with the time.
            Box(Modifier.size(qrSide + 56.dp).drawBehind {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Palette.amber.copy(alpha = 0.30f), Palette.violet.copy(alpha = 0.12f), Color.Transparent), c, r * 1.25f), r * 1.25f, c)
                val ring = r - 3.dp.toPx()
                drawCircle(Palette.fg.copy(alpha = 0.10f), ring, c, style = Stroke(2.dp.toPx()))
                if (paired == null && !expired) {
                    val sweep = 360f * left
                    drawArc(Brush.sweepGradient(listOf(Palette.violet, Palette.amber, Palette.sun, Palette.violet), c), -90f, sweep, false,
                        Offset(c.x - ring, c.y - ring), Size(2 * ring, 2 * ring), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    val a = Math.toRadians((-90f + sweep).toDouble())
                    val tip = Offset(c.x + ring * kotlin.math.cos(a).toFloat(), c.y + ring * kotlin.math.sin(a).toFloat())
                    drawCircle(Brush.radialGradient(listOf(Palette.sun, Palette.amber.copy(alpha = 0.5f), Color.Transparent), tip, 9.dp.toPx()), 9.dp.toPx(), tip)
                }
            }, contentAlignment = Alignment.Center) {
                // The tile's corners stay inside the ring.
                val inner = (qrSide + 56.dp) * 0.70f
                picture(Modifier.size(inner).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(3.dp))
            }
            caption()
            hint()
        }
        Look.TIDE -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.width(qrSide + 40.dp), verticalAlignment = Alignment.CenterVertically) { title(Modifier.weight(1f)); close() }
            // A porthole of water around the code, draining as the time runs out; it bobs gently.
            val shape = RoundedCornerShape(34.dp)
            Box(Modifier.size(qrSide + 40.dp).graphicsLayer { translationY = kotlin.math.round(2f * density * sin(time * 1.2f)) }.clip(shape)
                .background(Palette.night.copy(alpha = 0.72f)).drawBehind { drawWaterFill(if (paired != null) 1f else left, time, vertical = true) }
                .border(2.dp, Palette.cream.copy(alpha = 0.55f), shape), contentAlignment = Alignment.Center) {
                picture(Modifier.size(qrSide).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(4.dp))
            }
            caption()
            hint()
        }
    }
}

/** The moment it paired: a disc swells in the look's colours, a ring spreads out, and a check is drawn. */
@Composable
fun PairedBurst(modifier: Modifier) {
    val progress = remember { Animatable(if (Platform.reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) }
    val colors = Palette.look.colors.accent
    Canvas(modifier.semantics { contentDescription = S.qrPaired("") }) {
        val p = progress.value
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        // The disc overshoots a little, then settles.
        val grow = (p / 0.45f).coerceIn(0f, 1f)
        val scale = if (grow < 1f) 1.12f * grow else 1.12f - 0.12f * ((p - 0.45f) / 0.25f).coerceIn(0f, 1f)
        val ring = (p / 0.8f).coerceIn(0f, 1f)
        drawCircle(colors.last().second.copy(alpha = 0.45f * (1f - ring)), r * (0.6f + 0.4f * ring), c, style = Stroke(4.dp.toPx() * (1f - ring) + 1f))
        drawCircle(Brush.linearGradient(*colors.toTypedArray(), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)), r * 0.62f * scale, c)
        val check = Path().apply {
            moveTo(c.x - r * 0.26f, c.y + r * 0.02f); lineTo(c.x - r * 0.07f, c.y + r * 0.21f); lineTo(c.x + r * 0.29f, c.y - r * 0.18f)
        }
        val draw = ((p - 0.35f) / 0.5f).coerceIn(0f, 1f)
        if (draw > 0f) {
            val measure = PathMeasure().apply { setPath(check, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * draw, part, true)
            drawPath(part, Color.White, style = Stroke(r * 0.1f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}
