package org.openbridge.android

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Pairing
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Cable { NONE, CHARGING, FILE_TRANSFER }

/**
 * Sharing state for the whole app process, so sharing can continue while the app is in the background
 * (kept alive by [ShareService] and its notification). The screen observes these values.
 */
object Sharing {
    private lateinit var app: Application

    /** What tapping the sun shares: the whole phone (needs All files access) or one chosen folder. */
    var wholePhone by mutableStateOf(true); private set
    var folder by mutableStateOf<Uri?>(null); private set
    var server by mutableStateOf<SharingServer?>(null); private set
    var busy by mutableStateOf(false); private set
    /** A computer has paired or reconnected since sharing started. */
    var paired by mutableStateOf(false); private set
    /** The name of the computer using the phone, once one has. */
    var computer by mutableStateOf<String?>(null); private set
    /** The USB cable: unplugged, charging only, or in File transfer mode (the Mac can browse the phone). */
    var cable by mutableStateOf(Cable.NONE)
    /** A problem or hint to show under the status, or null. */
    var note by mutableStateOf<String?>(null); private set
    /** Keep sharing after leaving the app, with a notification to stop it. Remembered between launches. */
    var inBackground by mutableStateOf(true); private set
    /** The OpenBridge screen is in front, so the clipboard can be read and pairing shown in the app. */
    @Volatile var onScreen = false
        set(value) { field = value; if (value) ShareService.clearPairing(app) else pairingCode?.let { ShareService.showPairing(app, it, pairingComputer) } }

    /** Sharing should start as soon as the needed permission or folder arrives. */
    private var startWhenReady = false
    private var awaitingWholePhone = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    /** 6-digit code of a pairing request waiting for the user, shown in a sheet (or a notification). */
    var pairingCode by mutableStateOf<String?>(null); private set
    /** The computer asking to pair, as it named itself. */
    var pairingComputer by mutableStateOf<String?>(null); private set
    private var pairingDecision: CompletableFuture<Boolean>? = null

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext as Application
        inBackground = app.getSharedPreferences("openbridge", Context.MODE_PRIVATE).getBoolean("inBackground", true)
        Trusted.attach(app)
        PhoneOutbox.attach(app)
        PhoneOutbox.onCollected = { name -> main.post {
            if (PhoneOutbox.waiting.isEmpty()) android.widget.Toast.makeText(app, S.sentTo(computer), android.widget.Toast.LENGTH_SHORT).show()
            if (note?.let { it == lastWaitingNote } == true) note = null
        } }
        // A remembered computer reconnecting lights the sun like a fresh pairing does.
        Trusted.onSeen = { name -> main.post { if (server != null && (!paired || computer != name)) { paired = true; computer = name; ShareService.refresh(app) } } }
    }

    /** Called on a server thread: shows the code and waits up to a minute for Pair or Cancel. */
    private val approver = object : Pairing.Approver {
        override fun approve(code: String) = approve(code, "Computer")
        override fun approve(code: String, computer: String): Boolean = ask(code, computer)
    }

    private fun ask(code: String, asking: String): Boolean {
        val decision = CompletableFuture<Boolean>()
        synchronized(this) {
            if (pairingDecision != null) return false   // one request at a time
            pairingDecision = decision
        }
        main.post { pairingCode = code; pairingComputer = asking; if (!onScreen) ShareService.showPairing(app, code, asking) }
        val approved = try {
            decision.get(60, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: java.util.concurrent.ExecutionException) {
            false
        } finally {
            synchronized(this) { pairingDecision = null }
            main.post { if (pairingCode == code) pairingCode = null; ShareService.clearPairing(app) }
        }
        if (approved) main.post { if (server != null) { paired = true; computer = asking; ShareService.refresh(app) } }
        return approved
    }

    fun decidePairing(approve: Boolean) {
        synchronized(this) { pairingDecision }?.complete(approve)
        pairingCode = null
        ShareService.clearPairing(app)
    }

    private var lastWaitingNote: String? = null

    /** Files are waiting to be sent and sharing needs a tap to start (shown on the main screen). */
    fun waitingNote(text: String) { note = text; lastWaitingNote = text }

    fun keepInBackground(enabled: Boolean) {
        inBackground = enabled
        app.getSharedPreferences("openbridge", Context.MODE_PRIVATE).edit().putBoolean("inBackground", enabled).apply()
        if (server != null) { if (enabled) ShareService.start(app) else if (!onScreen) stop() else ShareService.stop(app) }
    }

    fun chooseWholePhone() { if (server == null && !busy) { wholePhone = true; note = null } }

    fun select(uri: Uri) {
        // Local external-storage documents only: no cloud providers or broad filesystem permissions.
        if (uri.authority != "com.android.externalstorage.documents") {
            startWhenReady = false
            note = S.pickLocalFolder
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
        if (AllFiles.granted(app)) {
            wholePhone = true; note = null
            if (startWhenReady) { startWhenReady = false; start() }
        } else {
            startWhenReady = false
            note = S.needAllFiles
        }
    }

    fun start() {
        val selected = folder
        val shareAll = wholePhone
        if ((!shareAll && selected == null) || busy || server != null) return
        busy = true; note = null; paired = false; computer = null
        val current = ++generation
        worker.execute {
            try {
                val store = if (shareAll) {
                    if (!AllFiles.granted(app)) error(S.allFilesOff)
                    FileStore(AllFiles.roots(app)) { file, size -> Thumbs.ofFile(file, size) }
                } else {
                    val root = DocumentFile.fromTreeUri(app, selected!!) ?: error(S.chooseFolderAgain)
                    SafStore(app.contentResolver, root)
                }
                val created = SharingServer(app, store, Trusted, approver, PhoneClipboard(app)) { reason ->
                    main.post { if (generation == current) { stop(); note = reason } }
                }
                main.post {
                    if (generation != current) created.close() else {
                        server = created; busy = false
                        created.start()
                        // Android refuses a background service to an app that isn't visible; sharing then simply
                        // stops when the app is left, as with background sharing off.
                        if (inBackground) runCatching { ShareService.start(app) }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (generation == current) {
                        busy = false
                        note = when (e) {
                            is SecurityException -> S.folderDenied
                            is IllegalStateException -> e.message ?: S.couldNotStart
                            else -> S.checkWifi
                        }
                    }
                }
            }
        }
    }

    fun stop(reason: String? = null) {
        generation++
        decidePairing(false)
        server?.close(); server = null; busy = false; paired = false; computer = null
        if (reason != null) note = reason
        ShareService.stop(app)
    }
}
