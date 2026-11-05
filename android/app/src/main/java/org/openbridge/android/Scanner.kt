package org.openbridge.android

import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openbridge.ui.Palette
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Pair with QR code: the entry row, the full-screen scanner over the camera, and the cards for a code waiting for its
// computer, a computer that paired, and pairing that didn't finish.

/** What the scanner shows behind its frame: the camera (in the app), or a painted stand-in (screenshot tests). */
interface CameraFeed {
    /**
     * Shows the camera, calling [onText] (on the main thread) with each QR code's text it reads, [onReady] once it runs
     * (with whether it has a light), and [onFailed] when it can't start.
     */
    @Composable
    fun Feed(torch: Boolean, onText: (String) -> Unit, onReady: (hasTorch: Boolean) -> Unit, onFailed: () -> Unit, modifier: Modifier)
}

val LocalCameraFeed = staticCompositionLocalOf<CameraFeed> { PhoneCamera }

/** The back camera through CameraX: a preview, and frames for [QrDecoder] on a worker thread. */
object PhoneCamera : CameraFeed {
    @Composable
    override fun Feed(torch: Boolean, onText: (String) -> Unit, onReady: (Boolean) -> Unit, onFailed: () -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val text by rememberUpdatedState(onText)
        val ready by rememberUpdatedState(onReady)
        val failed by rememberUpdatedState(onFailed)
        val view = remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
        var camera by remember { mutableStateOf<Camera?>(null) }
        DisposableEffect(owner) {
            val worker = Executors.newSingleThreadExecutor()
            val main = Handler(Looper.getMainLooper())
            val future = ProcessCameraProvider.getInstance(context)
            var disposed = false
            var provider: ProcessCameraProvider? = null
            future.addListener({
                if (disposed) return@addListener
                try {
                    val cameras = future.get().also { provider = it }
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                        .build()
                    val decoder = QrDecoder()
                    analysis.setAnalyzer(worker) { image ->
                        val found = try { decoder.decode(image) } finally { image.close() }
                        if (found != null) main.post { if (!disposed) text(found) }
                    }
                    cameras.unbindAll()
                    val bound = cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    camera = bound
                    ready(bound.cameraInfo.hasFlashUnit())
                } catch (e: Exception) {
                    Log.add("The camera couldn't start: ${e.javaClass.simpleName}")
                    failed()
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed = true
                runCatching { provider?.unbindAll() }
                camera = null
                worker.shutdown()
            }
        }
        LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
        AndroidView({ view }, modifier)
    }
}

/** Finds a QR code in a camera frame's brightness plane (ZXing, pure Java), dark on light or light on dark. */
class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8")

    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val stride = plane.rowStride
        if (stride < image.width || bytes.size < stride * (image.height - 1) + image.width) return null
        return decode(bytes, stride, image.width, image.height)
    }

    /** [luminance] holds [height] rows of [stride] bytes, of which the first [width] are the picture. */
    fun decode(luminance: ByteArray, stride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, stride, height, 0, 0, width, height, false)
        for (candidate in listOf(source, source.invert())) {
            try { return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }
            catch (_: ReaderException) { /* no code in this frame */ }
            finally { reader.reset() }
        }
        return null
    }
}

/** The way into scanning, on Home and in Trusted computers. */
@Composable
fun PairWithQrRow(actions: Actions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().pressScale(interaction).glass(tileShape, edge = Palette.amber.copy(alpha = 0.35f))
        .clickable(interaction, androidx.compose.material3.ripple(color = Palette.amber), role = Role.Button) { haptics.tap(); actions.scanQr() }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.QrCodeScanner, Palette.amber, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(S.pairWithQr, style = Type.bodyStrong, color = Palette.fg)
            Text(S.pairWithQrDetail, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.muted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.muted)
    }
}

/** Everything of pairing by QR code that goes over the app: the scanner, then the waiting, paired or problem card. */
@Composable
fun BoxScope.QrPairingLayer(qr: QrUi, now: Long, actions: Actions) {
    val motion = LocalMotion.current
    AnimatedVisibility(qr.scanning, Modifier.matchParentSize(),
        enter = if (motion.still) fadeIn(motion()) else slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it / 6 } + fadeIn(),
        exit = if (motion.still) fadeOut(motion()) else slideOutVertically { it / 8 } + fadeOut(tween(180))) {
        ScannerScreen(actions)
    }
    val card = when {
        qr.scanning -> null
        qr.waitingFor != null -> "waiting"
        qr.paired != null -> "paired"
        qr.problem != null -> "problem"
        else -> null
    }
    // Keep the last card's content while it leaves.
    var shown by remember { mutableStateOf(qr) }
    if (card != null) shown = qr
    if (card != null) BackHandler { if (card == "waiting") actions.cancelQr() else actions.dismissQr() }
    AnimatedVisibility(card != null, Modifier.matchParentSize(), enter = fadeIn(motion()), exit = fadeOut(motion())) {
        Box(Modifier.fillMaxSize().background(Palette.night.copy(alpha = 0.72f)).clickable(remember { MutableInteractionSource() }, indication = null) {})
    }
    AnimatedVisibility(card != null, Modifier.align(Alignment.Center),
        enter = if (motion.still) fadeIn(motion()) else scaleIn(spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) + fadeIn(),
        exit = if (motion.still) fadeOut(motion()) else scaleOut(targetScale = 0.92f) + fadeOut()) {
        Column(Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth()
            .glass(RoundedCornerShape(32.dp), strong = true, edge = (if (shown.problem != null && shown.waitingFor == null && shown.paired == null) Palette.pink else Palette.amber).copy(alpha = 0.45f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 26.dp)
            .semantics { paneTitle = S.pairWithQr },
            horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(shown, label = "qr card", contentKey = { if (it.waitingFor != null) 0 else if (it.paired != null) 1 else 2 }, transitionSpec = {
                if (motion.still) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(220, delayMillis = 80)) + scaleIn(fluid(), initialScale = 0.94f)) togetherWith fadeOut(tween(120))
            }) { state ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        state.waitingFor != null -> WaitingCard(state.waitingFor, state.expiresAt, now, actions)
                        state.paired != null -> PairedCard(state.paired, actions)
                        else -> ProblemCard(state.problem ?: QrProblem.EXPIRED, state.problemComputer, actions)
                    }
                }
            }
        }
    }
}

/** A scanned code waits for its computer: a ring empties over the two minutes, around the computer. */
@Composable
private fun WaitingCard(computer: String, expiresAt: Long, frozenNow: Long, actions: Actions) {
    val still = LocalMotion.current.still
    val frozen = LocalMotion.current.frozen
    var now by remember { mutableLongStateOf(if (frozen) frozenNow else System.currentTimeMillis()) }
    LaunchedEffect(expiresAt, frozen) {
        if (!frozen) while (true) { now = System.currentTimeMillis(); delay(200) }
    }
    val left = (expiresAt - now).coerceIn(0, 120_000)
    val haptics = rememberHaptics()
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        CountdownRing(left / 120_000f, still)
        IconBadge(Icons.Rounded.Laptop, Palette.amber, 64.dp)
    }
    Spacer(Modifier.height(16.dp))
    Text(S.pairWithQr.uppercase(), style = Type.overline, color = Palette.amber)
    Spacer(Modifier.height(6.dp))
    Text(S.readyToPair(computer), style = Type.title, color = Palette.fg, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Spacer(Modifier.height(8.dp))
    Text(S.readyDetail, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center)
    Spacer(Modifier.height(14.dp))
    val seconds = (left + 999) / 1000
    Text(S.timeLeft(seconds), style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = if (seconds <= 20) Palette.pink else Palette.amber,
        modifier = Modifier.clip(CircleShape).background(Palette.fg.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 4.dp))
    Spacer(Modifier.height(20.dp))
    SecondaryButton(S.cancel, { haptics.reject(); actions.cancelQr() }, Modifier.fillMaxWidth())
}

/** The time left as an arc in the accent gradient, with a dot riding its end, and a soft pulse while it waits. */
@Composable
private fun CountdownRing(fraction: Float, still: Boolean) {
    val transition = if (still) null else rememberInfiniteTransition(label = "waiting")
    val pulse = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pulse")
    val shown by animateFloatAsStateCompat(fraction)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 7.dp.toPx()
        val radius = size.minDimension / 2 - stroke
        val p = pulse?.value ?: 0.4f
        drawCircle(Palette.amber.copy(alpha = 0.22f * (1 - p)), radius = radius * (0.7f + 0.35f * p), style = Stroke(2.dp.toPx()))
        drawCircle(Palette.fg.copy(alpha = 0.10f), radius = radius, style = Stroke(stroke))
        val sweep = 360f * shown
        val topLeft = Offset(center.x - radius, center.y - radius)
        drawArc(Brush.sweepGradient(listOf(Palette.amber, Palette.pink, Palette.amber), center), -90f, sweep, false, topLeft,
            GeometrySize(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        if (shown > 0f) {
            val angle = (-90f + sweep) * PI.toFloat() / 180f
            drawCircle(Palette.cream, stroke * 0.75f, Offset(center.x + radius * cos(angle), center.y + radius * sin(angle)))
        }
    }
}

@Composable
private fun animateFloatAsStateCompat(target: Float): State<Float> =
    androidx.compose.animation.core.animateFloatAsState(target, motion(tween(300, easing = LinearEasing)), label = "left")

/** Paired: a check draws itself in a circle, with a burst of the look's colours. */
@Composable
private fun PairedCard(computer: String, actions: Actions) {
    val motion = LocalMotion.current
    val haptics = rememberHaptics()
    val circle = remember { Animatable(if (motion.still) 1f else 0f) }
    val check = remember { Animatable(if (motion.still) 1f else 0f) }
    val burst = remember { Animatable(if (motion.still) 0.55f else 0f) }
    LaunchedEffect(Unit) {
        if (motion.still) return@LaunchedEffect
        haptics.confirm()
        circle.animateTo(1f, tween(420))
        kotlinx.coroutines.coroutineScope {
            launch { check.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
            launch { burst.animateTo(1f, tween(900)) }
        }
    }
    Box(Modifier.size(132.dp).semantics { contentDescription = S.pairedWith(computer) }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawSuccess(circle.value, check.value, burst.value) }
    }
    Spacer(Modifier.height(16.dp))
    Text(S.pairedWith(computer), style = Type.title, color = Palette.fg, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
    Spacer(Modifier.height(8.dp))
    Text(S.pairedDetail, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center)
    Spacer(Modifier.height(22.dp))
    PrimaryButton(S.done, { haptics.tap(); actions.dismissQr() }, Modifier.fillMaxWidth())
}

private fun DrawScope.drawSuccess(circle: Float, check: Float, burst: Float) {
    val radius = size.minDimension * 0.30f
    // The burst: dots flying out and fading.
    if (burst in 0.001f..0.999f) {
        val colors = listOf(Palette.amber, Palette.pink, Palette.violet, Palette.ok, Palette.cream)
        for (i in 0 until 14) {
            val angle = (i / 14f) * 2 * PI.toFloat() + 0.2f
            val distance = radius * (1.15f + 0.75f * burst) * (if (i % 2 == 0) 1f else 0.82f)
            drawCircle(colors[i % colors.size].copy(alpha = (1 - burst).coerceIn(0f, 1f)), (if (i % 3 == 0) 4.5f else 3f) * density * (1 - burst * 0.4f),
                Offset(center.x + distance * cos(angle), center.y + distance * sin(angle)))
        }
    }
    drawCircle(Palette.ok.copy(alpha = 0.16f * circle), radius * 1.05f)
    val topLeft = Offset(center.x - radius, center.y - radius)
    drawArc(Palette.ok, -90f, 360f * circle, false, topLeft, GeometrySize(radius * 2, radius * 2), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    if (check > 0f) {
        val a = Offset(center.x - radius * 0.42f, center.y + radius * 0.02f)
        val b = Offset(center.x - radius * 0.10f, center.y + radius * 0.34f)
        val c = Offset(center.x + radius * 0.46f, center.y - radius * 0.30f)
        val first = (check / 0.4f).coerceIn(0f, 1f)
        val second = ((check - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val path = Path().apply {
            moveTo(a.x, a.y)
            lineTo(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first)
            if (second > 0f) lineTo(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second)
        }
        drawPath(path, Palette.ok, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Pairing didn't finish: why, and the way on. */
@Composable
private fun ProblemCard(problem: QrProblem, computer: String?, actions: Actions) {
    val haptics = rememberHaptics()
    LaunchedEffect(problem) { haptics.reject() }
    IconBadge(if (problem == QrProblem.NO_CAMERA) Icons.Rounded.NoPhotography else Icons.Rounded.SyncProblem, Palette.pink, 64.dp)
    Spacer(Modifier.height(16.dp))
    Text(S.pairingDidntFinish, style = Type.title, color = Palette.fg, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(when (problem) {
        QrProblem.EXPIRED -> S.qrExpired(computer)
        QrProblem.REFUSED -> S.qrRefused
        QrProblem.NOT_SHARING -> S.qrNotSharing
        QrProblem.NO_CAMERA -> S.qrNoCamera
    }, style = Type.body, color = Palette.muted, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(S.close, { actions.dismissQr() }, Modifier.weight(1f))
        if (problem == QrProblem.NO_CAMERA) PrimaryButton(S.openSettings, { actions.dismissQr(); actions.appSettings() }, Modifier.weight(1.3f))
        else PrimaryButton(S.scanAgain, { actions.dismissQr(); actions.scanQr() }, Modifier.weight(1.3f))
    }
}

/**
 * The scanner: the camera fills the screen under a dimmed veil with a clear window, whose corners breathe and across
 * which a line of light sweeps. Close and the light at hand; a hint when a code isn't OpenBridge's.
 */
@Composable
fun ScannerScreen(actions: Actions) {
    val feed = LocalCameraFeed.current
    val motion = LocalMotion.current
    val haptics = rememberHaptics()
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var lastText by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf(false) }
    BackHandler { actions.closeScanner() }
    // A hint fades after a while, so the next code gets a fresh one.
    LaunchedEffect(hint) { if (hint != null) { delay(4_000); hint = null; lastText = null } }
    val transition = if (motion.still) null else rememberInfiniteTransition(label = "scanner")
    val sweep = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "sweep")
    val breathe = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    val lock by androidx.compose.animation.core.animateFloatAsState(if (found) 1f else 0f, motion(spring(dampingRatio = 0.55f)), label = "lock")
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // The room between the title and the controls, where the window goes (so large text never covers it).
    var area by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { paneTitle = S.scanTitle }) {
        feed.Feed(torch, onText = { text ->
            if (found || text == lastText) return@Feed
            lastText = text
            when (actions.scanned(text)) {
                ScanPairing.Read.PAIRING -> { found = true; haptics.confirm() }
                ScanPairing.Read.OTHER -> { hint = S.scanNotOurs; haptics.reject() }
                ScanPairing.Read.DAMAGED -> { hint = S.scanDamaged; haptics.reject() }
            }
        }, onReady = { hasTorch = it }, onFailed = { failed = true }, modifier = Modifier.fillMaxSize())

        // The veil with its window, the corners and the sweeping line.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val room = area ?: Rect(0f, size.height * 0.1f, size.width, size.height * 0.7f)
            val side = minOf(room.width * 0.72f, room.height * 0.86f, 300.dp.toPx())
            val window = Rect(Offset(room.center.x - side / 2, room.center.y - side / 2), GeometrySize(side, side))
            val corner = CornerRadius(28.dp.toPx())
            drawRect(Palette.night.copy(alpha = 0.66f))
            drawRoundRect(Color.Black, window.topLeft, window.size, corner, blendMode = BlendMode.Clear)
            val b = breathe?.value ?: 0.5f
            val grow = 6.dp.toPx() * (b - 0.5f) * (1 - lock) - 10.dp.toPx() * lock
            drawCorners(window.inflate(grow), 28.dp.toPx(), side * 0.18f, 5.dp.toPx(),
                if (found) Brush.linearGradient(listOf(Palette.ok, Palette.ok)) else Brush.linearGradient(listOf(Palette.amber, Palette.pink), window.topLeft, window.bottomRight))
            if (!found && !failed) {
                val y = window.top + window.height * (0.08f + 0.84f * (sweep?.value ?: 0.42f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Palette.amber.copy(alpha = 0.28f)), y - 36.dp.toPx(), y),
                    Offset(window.left + 14.dp.toPx(), y - 36.dp.toPx()), GeometrySize(window.width - 28.dp.toPx(), 36.dp.toPx()))
                drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Palette.cream, Palette.amber, Color.Transparent), window.left, window.right),
                    Offset(window.left + 10.dp.toPx(), y - 1.5f.dp.toPx()), GeometrySize(window.width - 20.dp.toPx(), 3.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
        }

        Column(Modifier.fillMaxSize()) {
            // Title and Close.
            Row(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(S.pairWithQr.uppercase(), style = Type.overline, color = Palette.amber)
                    Text(S.scanTitle, style = Type.title, color = Palette.fg)
                }
                IconAction(Icons.Rounded.Close, S.close, { actions.closeScanner() }, filled = true)
            }

            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { area = it.boundsInParent() })
            // Help or a hint, the light, and the code alternative.
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = navBottom + 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val message = when {
                    failed -> S.cameraUnavailable
                    hint != null -> hint!!
                    else -> S.scanHelp
                }
                val warn = failed || hint != null
                AnimatedContent(message, label = "hint", transitionSpec = {
                    if (motion.still) EnterTransition.None togetherWith ExitTransition.None
                    else (fadeIn(tween(200)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(120))
                }) { text ->
                    Row(Modifier.fillMaxWidth().glass(tileShape, strong = true, edge = if (warn) Palette.pink.copy(alpha = 0.6f) else null).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (warn) Icons.Rounded.SyncProblem else Icons.Rounded.QrCodeScanner, null, tint = if (warn) Palette.pink else Palette.amber,
                            modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = Type.label.copy(fontWeight = FontWeight.Normal), color = Palette.fg,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                if (hasTorch) TorchButton(torch) { torch = it; haptics.tick() }
                Text(S.orCode, style = Type.caption, color = Palette.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun DrawScope.drawCorners(window: Rect, radius: Float, arm: Float, width: Float, brush: Brush) {
    val r = radius
    val path = Path()
    // Top left, top right, bottom right, bottom left: an arm, the rounded corner, an arm.
    path.moveTo(window.left, window.top + r + arm); path.lineTo(window.left, window.top + r)
    path.quadraticTo(window.left, window.top, window.left + r, window.top); path.lineTo(window.left + r + arm, window.top)
    path.moveTo(window.right - r - arm, window.top); path.lineTo(window.right - r, window.top)
    path.quadraticTo(window.right, window.top, window.right, window.top + r); path.lineTo(window.right, window.top + r + arm)
    path.moveTo(window.right, window.bottom - r - arm); path.lineTo(window.right, window.bottom - r)
    path.quadraticTo(window.right, window.bottom, window.right - r, window.bottom); path.lineTo(window.right - r - arm, window.bottom)
    path.moveTo(window.left + r + arm, window.bottom); path.lineTo(window.left + r, window.bottom)
    path.quadraticTo(window.left, window.bottom, window.left, window.bottom - r); path.lineTo(window.left, window.bottom - r - arm)
    drawPath(path, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun TorchButton(on: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(60.dp).pressScale(interaction, 0.9f).clip(CircleShape)
        .then(if (on) Modifier.background(Palette.sunset) else Modifier.glass(CircleShape, strong = true))
        .toggleable(on, interaction, androidx.compose.material3.ripple(), role = Role.Switch) { onChange(it) }
        .semantics { contentDescription = if (on) S.torchOff else S.torchOn }, contentAlignment = Alignment.Center) {
        Icon(if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, null, tint = if (on) Palette.night else Palette.fg, modifier = Modifier.size(26.dp))
    }
}
