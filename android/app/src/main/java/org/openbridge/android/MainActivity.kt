package org.openbridge.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import java.util.concurrent.Executors

class ShareModel(application: Application) : AndroidViewModel(application) {
    var folder by mutableStateOf<Uri?>(null); private set
    var server by mutableStateOf<SharingServer?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("Choose a local folder to share."); private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0

    fun select(uri: Uri) {
        stop()
        // Local external-storage documents only: no cloud providers or broad filesystem permissions.
        if (uri.authority != "com.android.externalstorage.documents") {
            message = "Select a folder from this device's internal storage or SD card."
            return
        }
        folder = uri
        message = "Folder approved. Sharing is off."
    }

    fun start() {
        val selected = folder ?: return
        if (busy || server != null) return
        busy = true
        val current = ++generation
        worker.execute {
            try {
                val context = getApplication<Application>()
                val root = DocumentFile.fromTreeUri(context, selected) ?: error("Select the folder again")
                val created = SharingServer(context, SafStore(context.contentResolver, root)) { reason ->
                    main.post { if (generation == current) { stop(); message = reason } }
                }
                main.post {
                    if (generation != current) created.close() else {
                        server = created; busy = false; message = "Sharing on local Wi-Fi. Keep this app visible."
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
                            else -> "Unable to start sharing. Check Wi-Fi and folder access."
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        generation++
        server?.close(); server = null; busy = false
        message = "Sharing stopped. Start again for new pairing credentials."
    }
    override fun onCleared() { stop(); worker.shutdownNow() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: ShareModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ShareModel::class.java]
        val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.data?.let(model::select)
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("OpenBridge", style = MaterialTheme.typography.headlineLarge)
                        Text("Share one approved folder with your Mac over local Wi-Fi.")
                        Button(onClick = {
                            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                            })
                        }, enabled = model.server == null && !model.busy) { Text("Choose folder") }
                        Text(model.folder?.lastPathSegment ?: "No folder selected")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = model::start,
                                enabled = model.folder != null && model.server == null && !model.busy) {
                                Text(if (model.busy) "Starting…" else "Start sharing")
                            }
                            OutlinedButton(onClick = model::stop, enabled = model.server != null || model.busy) { Text("Stop") }
                        }
                        Text(model.message)
                        model.server?.let { sharing ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Phone IP: ${sharing.host}\nPort: ${sharing.port}")
                                    Text("Certificate SHA-256:\n${sharing.fingerprint}")
                                    Text("Pairing secret:\n${sharing.pairingSecret}")
                                }
                            }
                            Text("Enter these values on your Mac. Verify the fingerprint directly from this screen. " +
                                "Anyone with these credentials can read and upload in this folder while sharing is on.")
                        }
                        Text("Leaving this app stops sharing. Interrupted uploads are discarded. " +
                            "Existing filenames are never intentionally overwritten.")
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.stop()
        super.onStop()
    }
}
