package org.openbridge.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Pairing

class ShareModel(application: Application) : AndroidViewModel(application) {
    var folder by mutableStateOf<Uri?>(null); private set
    /** Share all of internal storage and SD cards instead of one folder (needs All files access). */
    var wholePhone by mutableStateOf(false); private set
    private var awaitingWholePhone = false
    var server by mutableStateOf<SharingServer?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("Choose a local folder to share."); private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0

    /** 6-digit code of a pairing request waiting for the user, shown in a dialog. */
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
        try {
            decision.get(60, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: java.util.concurrent.ExecutionException) {
            false
        } finally {
            synchronized(this) { pairingDecision = null }
            main.post { if (pairingCode == code) pairingCode = null }
        }
    }

    fun decidePairing(approve: Boolean) {
        synchronized(this) { pairingDecision }?.complete(approve)
        pairingCode = null
    }

    fun select(uri: Uri) {
        stop()
        // Local external-storage documents only: no cloud providers or broad filesystem permissions.
        if (uri.authority != "com.android.externalstorage.documents") {
            message = "Select a folder from this device's internal storage or SD card."
            return
        }
        folder = uri; wholePhone = false
        message = "Folder approved. Sharing is off."
    }

    fun selectWholePhone() {
        stop()
        if (!AllFiles.granted(getApplication())) {
            message = "Allow all files access for OpenBridge to share the whole phone."
            return
        }
        wholePhone = true; folder = null
        message = "Whole phone approved. Sharing is off."
    }

    /** The user went to grant All files access; called when they come back. */
    fun awaitWholePhone() { awaitingWholePhone = true }

    fun wholePhoneResult() {
        if (!awaitingWholePhone) return
        awaitingWholePhone = false
        if (AllFiles.granted(getApplication())) selectWholePhone()
        else message = "All files access was not allowed, so whole-phone sharing is off."
    }

    fun start() {
        val selected = folder
        val shareAll = wholePhone
        if (selected == null && !shareAll) return
        if (busy || server != null) return
        busy = true
        val current = ++generation
        worker.execute {
            try {
                val context = getApplication<Application>()
                val store = if (shareAll) {
                    if (!AllFiles.granted(context)) error("All files access was turned off. Allow it again to share the whole phone.")
                    FileStore(AllFiles.roots(context))
                } else {
                    val root = DocumentFile.fromTreeUri(context, selected!!) ?: error("Select the folder again")
                    SafStore(context.contentResolver, root)
                }
                val created = SharingServer(context, store, approver) { reason ->
                    main.post { if (generation == current) { stop(); message = reason } }
                }
                main.post {
                    if (generation != current) created.close() else {
                        server = created; busy = false
                        message = if (created.usb) "Sharing over the USB cable. Keep this app visible."
                            else "Sharing on local Wi-Fi. Keep this app visible."
                        created.start()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (generation == current) {
                        busy = false
                        message = when (e) {
                            is SecurityException -> "Storage permission denied. Select the folder again."
                            is IllegalStateException -> e.message ?: "Unable to start sharing"
                            else -> "Unable to start sharing. Check Wi-Fi or USB tethering, and folder access."
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        generation++
        decidePairing(false)
        server?.close(); server = null; busy = false
        message = "Sharing stopped. Pair your Mac again after starting."
    }
    override fun onCleared() { stop(); worker.shutdownNow() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: ShareModel

    /** There is no public tethering-settings intent; fall back to the general network settings. */
    private fun openTetheringSettings() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        try { startActivity(tether) } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ShareModel::class.java]
        val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.data?.let(model::select)
        }
        val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.wholePhoneResult() }
        val requestWholePhone: () -> Unit = {
            if (AllFiles.granted(this)) model.selectWholePhone() else {
                model.awaitWholePhone()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+: the user turns on "Allow access to manage all files" for this app.
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }
                } else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        setContent {
            val sharing = model.server != null
            // Sharing stops when the Activity stops, so stop the screen timeout from ending transfers.
            LaunchedEffect(sharing) {
                if (sharing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("OpenBridge", style = MaterialTheme.typography.headlineLarge)
                        Text("Share one folder, or your whole phone, with your Mac over local Wi-Fi or a USB cable.")
                        Button(onClick = {
                            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                            })
                        }, enabled = model.server == null && !model.busy) { Text("Choose folder") }
                        OutlinedButton(onClick = requestWholePhone, enabled = model.server == null && !model.busy) {
                            Text("Share whole phone")
                        }
                        Text(when {
                            model.wholePhone -> "Whole phone: internal storage and SD card"
                            model.folder != null -> model.folder!!.lastPathSegment ?: "Folder selected"
                            else -> "Nothing selected"
                        })
                        if (model.wholePhone) {
                            Text("A paired Mac can read and upload anywhere in your storage while sharing is on. " +
                                "Nothing is ever deleted or overwritten. Android keeps Android/data and Android/obb private.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = model::start,
                                enabled = (model.folder != null || model.wholePhone) && model.server == null && !model.busy) {
                                Text(if (model.busy) "Starting…" else "Start sharing")
                            }
                            OutlinedButton(onClick = model::stop, enabled = model.server != null || model.busy) { Text("Stop") }
                        }
                        if (model.server == null) {
                            OutlinedButton(onClick = ::openTetheringSettings, enabled = !model.busy) {
                                Text("USB cable: turn on USB tethering")
                            }
                        }
                        Text(model.message)
                        model.server?.let { sharing ->
                            SelectionContainer {
                                Text("Connection: ${if (sharing.usb) "USB cable" else "Wi-Fi"}\nPhone IP: ${sharing.host}\nPort: ${sharing.port}",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Text("On your Mac, enter this IP and port and click Pair. Both screens then show a 6-digit code: " +
                                "tap Pair here only if they match. A paired Mac can read and upload in this folder while sharing is on.")
                        }
                        model.pairingCode?.let { code ->
                            AlertDialog(
                                onDismissRequest = { model.decidePairing(false) },
                                title = { Text("Pair with this Mac?") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("Check that your Mac shows exactly this code:")
                                        Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium)
                                        Text("If the codes differ, tap Cancel: another device may be trying to connect.")
                                    }
                                },
                                confirmButton = { TextButton(onClick = { model.decidePairing(true) }) { Text("Pair") } },
                                dismissButton = { TextButton(onClick = { model.decidePairing(false) }) { Text("Cancel") } }
                            )
                        }
                        Text("The screen stays on while sharing. Leaving this app stops sharing. Interrupted uploads are discarded. " +
                            "Existing filenames are never intentionally overwritten.")
                    }
                }
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
