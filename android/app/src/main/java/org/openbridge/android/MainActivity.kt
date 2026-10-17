package org.openbridge.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.openbridge.ui.Palette
import org.openbridge.ui.SunEngine
import org.openbridge.ui.drawSunArch

class MainActivity : ComponentActivity() {
    private val model = Sharing

    /** Android's sticky USB state broadcast: whether a computer is attached, and in which mode. */
    private val usbState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras ?: return
            val attached = extras.getBoolean("connected") && extras.getBoolean("configured")
            val transfer = extras.getBoolean("mtp") || extras.getBoolean("ptp")
            model.cable = when {
                attached && transfer -> Cable.FILE_TRANSFER
                extras.getBoolean("connected") -> Cable.CHARGING
                else -> Cable.NONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Sharing.attach(this)
        val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        ContextCompat.registerReceiver(this, usbState, IntentFilter("android.hardware.usb.action.USB_STATE"), ContextCompat.RECEIVER_EXPORTED)
        val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) model.select(uri) else model.folderCancelled()
        }
        val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.wholePhoneResult() }
        val chooseFolder: () -> Unit = {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            })
        }
        val requestWholePhone: (Boolean) -> Unit = { thenStart ->
            model.awaitWholePhone(thenStart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: the user turns on "Allow access to manage all files" for this app.
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            } else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Tapping the sun shares what's selected, asking for the folder or permission first when needed.
        val tapSun: () -> Unit = {
            // The background notification (Stop button, pairing requests) needs permission on Android 13+.
            if (Build.VERSION.SDK_INT >= 33 && model.inBackground &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            when {
                model.server != null || model.busy -> Unit
                model.wholePhone && !AllFiles.granted(this) -> requestWholePhone(true)
                model.wholePhone -> model.start()
                model.folder == null -> { model.startAfterFolderPicked(); chooseFolder() }
                else -> model.start()
            }
        }
        setContent {
            val sharing = model.server != null
            // Sharing stops when the Activity stops, so stop the screen timeout from ending transfers.
            LaunchedEffect(sharing) {
                if (sharing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.pink, background = Palette.night, surface = Palette.dusk)) {
                ShareScreen(model, tapSun, onWholePhone = model::chooseWholePhone, onFolder = chooseFolder)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbState)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        model.onScreen = true
        model.wholePhoneResult()
    }

    override fun onPause() {
        model.onScreen = false
        super.onPause()
    }

    override fun onStop() {
        // Without background sharing, leaving the app stops sharing, as before.
        if (!isChangingConfigurations && !model.inBackground) model.stop()
        super.onStop()
    }
}

@Composable
private fun ShareScreen(model: Sharing, tapSun: () -> Unit, onWholePhone: () -> Unit, onFolder: () -> Unit) {
    val sharing = model.server
    val sharingActive = sharing != null || model.busy
    val byCable = model.cable == Cable.FILE_TRANSFER
    // The sun lights up for a paired Mac over Wi-Fi, or for a Mac plugged in by cable in File transfer mode.
    val active = sharingActive || byCable
    val connected = model.paired || byCable
    val engine = remember { SunEngine() }
    val view = LocalView.current
    engine.onConnect = { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) }
    val reduceMotion = remember {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val activeNow by rememberUpdatedState(active)
    val connectedNow by rememberUpdatedState(connected)
    var tick by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf(SunEngine.Frame()) }
    LaunchedEffect(engine) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
            last = now
            frame = engine.step(dt, activeNow, connectedNow, reduceMotion)
            tick = now
        }
    }
    var anchor by remember { mutableStateOf(Offset.Unspecified) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.plum, 0.4f to Palette.dusk, 1f to Palette.night))) {
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { canvasOrigin = it.boundsInRoot().topLeft }) {
            tick   // redraw every frame
            if (anchor != Offset.Unspecified) {
                val r = 82.dp.toPx()
                drawSunArch(engine, frame, anchor - canvasOrigin, r, 280.dp.toPx())
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Text("OpenBridge", color = Palette.fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(when {
                byCable -> "Connected to your computer by cable"
                model.paired -> "Your computer is connected"
                sharing != null -> "Waiting for your computer"
                model.busy -> "Starting…"
                else -> "Tap the sun to share with your computer"
            }, color = Palette.muted, fontSize = 14.sp)
            // The sun's tap area. The arch itself is drawn by the full-screen canvas behind, so ripples are never clipped.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(250.dp).clip(CircleShape)
                    .onGloballyPositioned { anchor = it.boundsInRoot().center }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = tapSun)
                    .semantics { contentDescription = if (sharingActive) "Sharing" else "Start sharing" })
            }
            StatusPill(model)
            Spacer(Modifier.height(12.dp))
            if (sharing != null && !model.paired) {
                Text("On your computer, type", color = Palette.muted, fontSize = 13.sp)
                SelectionContainer {
                    Text(sharing.host, color = Palette.fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Text("port ${sharing.port}", color = Palette.muted, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
            if (byCable && (sharing == null || model.paired)) {
                Text("Open OpenBridge on your Mac, or File Explorer on a PC, to browse this phone.", color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            } else if (model.cable == Cable.CHARGING && sharing == null) {
                Text("Cable connected, but only charging. Choose File transfer in the USB notification to browse from your computer.",
                    color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            model.note?.let { Text(it, color = Palette.amber, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
            if (sharingActive) {
                GhostButton("Stop sharing", model::stop, Modifier.fillMaxWidth())
            } else {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceTile("Whole phone", "Every folder, like a USB drive", model.wholePhone, onWholePhone, Modifier.weight(1f).fillMaxHeight())
                    ChoiceTile("One folder", model.folder?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { "Storage" } ?: "Only the folder you pick",
                        !model.wholePhone, onFolder, Modifier.weight(1f).fillMaxHeight())
                }
            }
            BackgroundToggle(model)
            if (model.cable == Cable.NONE) UsbHint()
            Spacer(Modifier.height(14.dp))
        }
        PairingSheet(model)
    }
}

private val glassShape = RoundedCornerShape(22.dp)

private fun Modifier.glass(shape: Shape = glassShape, border: Color = Color.White.copy(alpha = 0.16f)) =
    clip(shape)
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))))
        .border(1.dp, border, shape)

@Composable
private fun StatusPill(model: Sharing) {
    val on = model.paired || model.cable == Cable.FILE_TRANSFER
    Row(Modifier.glass(CircleShape, if (on) Palette.ok.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.16f))
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Palette.ok else Color(0xFF8E8E9C)))
        Spacer(Modifier.width(8.dp))
        Text(when {
            model.paired -> "Connected to your computer"
            on -> "Connected by cable · File transfer"
            model.server != null -> if (model.wholePhone) "Sharing whole phone" else "Sharing one folder"
            model.busy -> "Starting…"
            else -> "Not sharing"
        }, color = Palette.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChoiceTile(title: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val border = if (selected) Modifier.border(1.5.dp, Palette.sunset, glassShape) else Modifier
    Column(modifier.glass().then(border).clickable(onClick = onClick, role = Role.RadioButton).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(18.dp).clip(CircleShape).then(
                if (selected) Modifier.background(Palette.sunset) else Modifier.border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)))
        }
        Spacer(Modifier.height(3.dp))
        Text(detail, color = Palette.muted, fontSize = 12.sp, maxLines = 2)
    }
}

@Composable
private fun BackgroundToggle(model: Sharing) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep sharing in the background", color = Palette.fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(if (model.inBackground) "A notification lets you stop it anytime." else "Leaving the app stops sharing.",
                color = Palette.muted, fontSize = 12.sp)
        }
        Switch(model.inBackground, model::keepInBackground, colors = SwitchDefaults.colors(
            checkedTrackColor = Palette.pink, checkedThumbColor = Color.White,
            uncheckedTrackColor = Color.White.copy(alpha = 0.12f), uncheckedThumbColor = Palette.muted, uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
private fun UsbHint() {
    Column(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Using a cable?", color = Palette.fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text("Plug into your Mac or PC and choose File transfer in the USB notification. OpenBridge on the Mac, or File Explorer on Windows, opens your phone. No need to tap the sun for a cable.",
            color = Palette.muted, fontSize = 12.5.sp)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
        .clickable(onClick = onClick, role = Role.Button).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SunsetButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Palette.sunset).clickable(onClick = onClick, role = Role.Button).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BoxScope.PairingSheet(model: Sharing) {
    val code = model.pairingCode
    // Keep the last code on screen while the sheet slides away.
    var last by remember { mutableStateOf("") }
    LaunchedEffect(code) { if (code != null) last = code }
    val shown = code ?: last
    BackHandler(enabled = code != null) { model.decidePairing(false) }
    AnimatedVisibility(code != null, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()) {
        Column(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF23C2878), Color(0xF7140E32))))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
            Spacer(Modifier.height(18.dp))
            Text("Pair with this computer?", color = Palette.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Check that your computer shows the same code", color = Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEachIndexed { index, digit ->
                    if (index == 3) Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(44.dp, 58.dp).glass(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text(digit.toString(), color = Palette.fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Codes differ? Tap Cancel: another device may be trying to connect.", color = Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Cancel", { model.decidePairing(false) }, Modifier.weight(1f))
                SunsetButton("Pair", { model.decidePairing(true) }, Modifier.weight(1.4f))
            }
        }
    }
}
