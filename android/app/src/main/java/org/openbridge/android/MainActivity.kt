package org.openbridge.android

import android.Manifest
import android.app.Application
import android.content.Intent
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Pairing

class ShareModel(application: Application) : AndroidViewModel(application) {
    /** What tapping the sun shares: the whole phone (needs All files access) or one chosen folder. */
    var wholePhone by mutableStateOf(true); private set
    var folder by mutableStateOf<Uri?>(null); private set
    var server by mutableStateOf<SharingServer?>(null); private set
    var busy by mutableStateOf(false); private set
    /** A Mac has paired since sharing started. */
    var paired by mutableStateOf(false); private set
    /** A problem or hint to show under the status, or null. */
    var note by mutableStateOf<String?>(null); private set
    /** Sharing should start as soon as the needed permission or folder arrives. */
    private var startWhenReady = false
    private var awaitingWholePhone = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0

    /** 6-digit code of a pairing request waiting for the user, shown in a sheet. */
    var pairingCode by mutableStateOf<String?>(null); private set
    private var pairingDecision: CompletableFuture<Boolean>? = null

    /** Called on a server thread: shows the code and waits up to a minute for Pair or Cancel. */
    private val approver = Pairing.Approver { code ->
        val decision = CompletableFuture<Boolean>()
        synchronized(this) {
            if (pairingDecision != null) return@Approver false   // one request at a time
            pairingDecision = decision
        }
        main.post { pairingCode = code }
        val approved = try {
            decision.get(60, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: java.util.concurrent.ExecutionException) {
            false
        } finally {
            synchronized(this) { pairingDecision = null }
            main.post { if (pairingCode == code) pairingCode = null }
        }
        if (approved) main.post { if (server != null) paired = true }
        approved
    }

    fun decidePairing(approve: Boolean) {
        synchronized(this) { pairingDecision }?.complete(approve)
        pairingCode = null
    }

    fun chooseWholePhone() { if (server == null && !busy) { wholePhone = true; note = null } }

    fun select(uri: Uri) {
        // Local external-storage documents only: no cloud providers or broad filesystem permissions.
        if (uri.authority != "com.android.externalstorage.documents") {
            startWhenReady = false
            note = "Pick a folder on this phone's storage or SD card."
            return
        }
        folder = uri; wholePhone = false; note = null
        if (startWhenReady) { startWhenReady = false; start() }
    }

    fun folderCancelled() { startWhenReady = false }

    /** Folder mode with no folder yet: sharing starts once the picker returns one. */
    fun startAfterFolderPicked() { startWhenReady = true }

    /** The user went to grant All files access (or the storage permission); [wholePhoneResult] runs when they return. */
    fun awaitWholePhone(start: Boolean) { awaitingWholePhone = true; startWhenReady = start }

    fun wholePhoneResult() {
        if (!awaitingWholePhone) return
        awaitingWholePhone = false
        if (AllFiles.granted(getApplication())) {
            wholePhone = true; note = null
            if (startWhenReady) { startWhenReady = false; start() }
        } else {
            startWhenReady = false
            note = "OpenBridge needs All files access to share the whole phone. Or choose One folder."
        }
    }

    fun start() {
        val selected = folder
        val shareAll = wholePhone
        if ((!shareAll && selected == null) || busy || server != null) return
        busy = true; note = null; paired = false
        val current = ++generation
        worker.execute {
            try {
                val context = getApplication<Application>()
                val store = if (shareAll) {
                    if (!AllFiles.granted(context)) error("All files access was turned off. Allow it again to share the whole phone.")
                    FileStore(AllFiles.roots(context))
                } else {
                    val root = DocumentFile.fromTreeUri(context, selected!!) ?: error("Choose the folder again")
                    SafStore(context.contentResolver, root)
                }
                val created = SharingServer(context, store, approver) { reason ->
                    main.post { if (generation == current) { stop(); note = reason } }
                }
                main.post {
                    if (generation != current) created.close() else {
                        server = created; busy = false
                        created.start()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (generation == current) {
                        busy = false
                        note = when (e) {
                            is SecurityException -> "Android denied access to the folder. Choose it again."
                            is IllegalStateException -> e.message ?: "Couldn't start sharing."
                            else -> "Couldn't start sharing. Check that Wi‑Fi is on."
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        generation++
        decidePairing(false)
        server?.close(); server = null; busy = false; paired = false
    }
    override fun onCleared() { stop(); worker.shutdownNow() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: ShareModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        model = ViewModelProvider(this)[ShareModel::class.java]
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

    override fun onResume() {
        super.onResume()
        model.wholePhoneResult()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.stop()
        super.onStop()
    }
}

@Composable
private fun ShareScreen(model: ShareModel, tapSun: () -> Unit, onWholePhone: () -> Unit, onFolder: () -> Unit) {
    val sharing = model.server
    val active = sharing != null || model.busy
    val engine = remember { SunEngine() }
    val view = LocalView.current
    engine.onConnect = { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) }
    val reduceMotion = remember {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val activeNow by rememberUpdatedState(active)
    val pairedNow by rememberUpdatedState(model.paired)
    var tick by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf(SunEngine.Frame()) }
    LaunchedEffect(engine) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(1.0 / 30)
            last = now
            frame = engine.step(dt, activeNow, pairedNow, reduceMotion)
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
                model.paired -> "Your Mac is connected"
                sharing != null -> "Waiting for your Mac"
                model.busy -> "Starting…"
                else -> "Tap the sun to share with your Mac"
            }, color = Palette.muted, fontSize = 14.sp)
            // The sun's tap area. The arch itself is drawn by the full-screen canvas behind, so ripples are never clipped.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(250.dp).clip(CircleShape)
                    .onGloballyPositioned { anchor = it.boundsInRoot().center }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = tapSun)
                    .semantics { contentDescription = if (active) "Sharing" else "Start sharing" })
            }
            StatusPill(model)
            Spacer(Modifier.height(12.dp))
            if (sharing != null && !model.paired) {
                Text("On your Mac, type", color = Palette.muted, fontSize = 13.sp)
                SelectionContainer {
                    Text(sharing.host, color = Palette.fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Text("port ${sharing.port}", color = Palette.muted, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
            model.note?.let { Text(it, color = Palette.amber, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
            if (active) {
                GhostButton("Stop sharing", model::stop, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceTile("Whole phone", "Every folder, like a USB drive", model.wholePhone, onWholePhone, Modifier.weight(1f))
                    ChoiceTile("One folder", model.folder?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { "Storage" } ?: "Only the folder you pick",
                        !model.wholePhone, onFolder, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(14.dp))
            UsbHint()
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
private fun StatusPill(model: ShareModel) {
    val on = model.paired
    Row(Modifier.glass(CircleShape, if (on) Palette.ok.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.16f))
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Palette.ok else Color(0xFF8E8E9C)))
        Spacer(Modifier.width(8.dp))
        Text(when {
            on -> "Connected to your Mac"
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
private fun UsbHint() {
    Column(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Using a cable?", color = Palette.fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text("No app needed. Plug in, choose File transfer, and your Mac opens it.", color = Palette.muted, fontSize = 12.5.sp)
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
private fun BoxScope.PairingSheet(model: ShareModel) {
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
            Text("Pair with this Mac?", color = Palette.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Check that your Mac shows the same code", color = Palette.muted, fontSize = 13.sp)
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
