package org.openbridge.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.openbridge.protocol.FileStore
import org.openbridge.protocol.Pairing
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Cable { NONE, CHARGING, FILE_TRANSFER }

/**
 * Sharing state for the whole app process, so sharing can continue while the app is in the background
 * (kept alive by [ShareService] and its notification). The screen observes these values.
 */
object Sharing {
    /** Asked over USB debugging (`am start … -a org.openbridge.android.SHARE_USB`): share over the cable only. */
    const val SHARE_USB = "org.openbridge.android.SHARE_USB"

    private lateinit var app: Application

    /** What tapping the hero shares: the whole phone (needs All files access) or one chosen folder. */
    var wholePhone by mutableStateOf(true); private set
    var folder by mutableStateOf<Uri?>(null); private set
    var server by mutableStateOf<SharingServer?>(null); private set
    /** Where computers can reach the phone while sharing: Wi-Fi or tethering, the hotspot, Fast USB. */
    var endpoints by mutableStateOf(emptyList<Endpoint>()); private set
    var busy by mutableStateOf(false); private set
    /** A computer has paired or reconnected since sharing started. */
    var paired by mutableStateOf(false); private set
    /** The name of the computer using the phone, once one has. */
    var computer by mutableStateOf<String?>(null); private set
    /** How that computer reaches the phone. */
    var transport by mutableStateOf<Via?>(null); private set
    /** The computer that made the latest request, as soon as it's known (on a server thread). */
    @Volatile var lastComputer: String? = null; private set
    /** The USB cable: unplugged, charging only, or in File transfer mode (the computer can browse the phone). */
    var cable by mutableStateOf(Cable.NONE); private set
    /** USB debugging is on over the cable, so a computer can use Fast USB. */
    var usbDebugging by mutableStateOf(false); private set
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
    @Volatile private var generation = 0

    /** 6-digit code of a pairing request waiting for the user, shown in a sheet (or a notification). */
    var pairingCode by mutableStateOf<String?>(null); private set
    /** The computer asking to pair, as it named itself. */
    var pairingComputer by mutableStateOf<String?>(null); private set
    private var pairingDecision: CompletableFuture<Boolean>? = null

    /** Android's sticky USB state broadcast: whether a computer is attached, in which mode, and with USB debugging. */
    private val usbState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = usb(intent)
    }

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext as Application
        val preferences = preferences()
        inBackground = preferences.getBoolean("inBackground", true)
        Palette.look = Look.of(preferences.getString("look", null))
        Trusted.attach(app)
        PhoneOutbox.attach(app)
        Texts.attach(app)
        Hotspot.attach(app)
        PhoneOutbox.onCollected = { _ -> main.post {
            if (PhoneOutbox.waiting.isEmpty()) android.widget.Toast.makeText(app, S.sentTo(computer), android.widget.Toast.LENGTH_SHORT).show()
            if (note?.let { it == lastWaitingNote } == true) note = null
        } }
        usb(ContextCompat.registerReceiver(app, usbState, IntentFilter("android.hardware.usb.action.USB_STATE"), ContextCompat.RECEIVER_EXPORTED))
    }

    private fun preferences() = app.getSharedPreferences("openbridge", Context.MODE_PRIVATE)

    /** The look picked in Settings, remembered on this phone. */
    fun chooseLook(look: Look) {
        Palette.look = look
        preferences().edit().putString("look", look.id).apply()
        Log.add("Look: ${look.id}")
    }

    private fun usb(intent: Intent?) {
        val extras = intent?.extras ?: return
        val connected = extras.getBoolean("connected")
        val transfer = extras.getBoolean("mtp") || extras.getBoolean("ptp")
        val was = cable
        cable = when {
            connected && extras.getBoolean("configured") && transfer -> Cable.FILE_TRANSFER
            connected -> Cable.CHARGING
            else -> Cable.NONE
        }
        usbDebugging = connected && extras.getBoolean("adb")
        if (was != cable) Log.add("USB cable: ${cable.name.lowercase()}${if (usbDebugging) ", USB debugging" else ""}")
        // Sharing over the cable only ends with the cable.
        if (!connected && server?.cableOnly == true) stop(S.cableUnplugged)
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
        Log.add("A computer asked to pair")
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
        Log.add(if (approved) "Pairing approved" else "Pairing declined or timed out")
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

    fun dismissNote() { note = null }

    /** Queues [text] for the computer's clipboard and keeps it in Texts. False when it's empty or too long. */
    fun sendText(text: String): Boolean {
        if (!PhoneOutbox.addText(text)) return false
        Texts.sent(text)
        Log.add("Queued a text")
        return true
    }

    fun keepInBackground(enabled: Boolean) {
        inBackground = enabled
        preferences().edit().putBoolean("inBackground", enabled).apply()
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

    /**
     * A computer asked over USB debugging: share over the cable only, when nothing needs the user first. Only while a
     * cable with USB debugging is plugged in, so other apps can't start sharing by themselves.
     */
    fun shareOverCable() {
        Log.add("Asked to share over the USB cable")
        if (server != null || busy) return
        when {
            !usbDebugging -> note = S.cableNeedsDebugging
            (wholePhone && AllFiles.granted(app)) || (!wholePhone && folder != null) -> start(cableOnly = true)
            else -> note = S.cableNeedsSetup
        }
    }

    /**
     * After files or a text were queued from another app: when sharing is off but can start by itself (whole phone,
     * permission granted, background sharing on), start it, and call [done] once it's up, since Android only lets a
     * visible app start the background service. Without background sharing it would run unseen, with no notification
     * to stop it, once this screen closes. [done] gets whether a computer can collect now. On the main thread.
     */
    fun handOver(done: (sharing: Boolean) -> Unit) {
        when {
            server != null -> done(true)
            inBackground && wholePhone && AllFiles.granted(app) -> {
                start()
                val started = SystemClock.uptimeMillis()
                main.post(object : Runnable {
                    override fun run() {
                        if (busy && SystemClock.uptimeMillis() - started < 8_000) main.postDelayed(this, 100) else done(server != null)
                    }
                })
            }
            else -> done(false)
        }
    }

    fun start(cableOnly: Boolean = false) {
        val selected = folder
        val shareAll = wholePhone
        if ((!shareAll && selected == null) || busy || server != null) return
        busy = true; note = null; paired = false; computer = null; transport = null
        val current = ++generation
        Log.add("Starting to share ${if (shareAll) "the whole phone" else "one folder"}${if (cableOnly) ", over the USB cable only" else ""}")
        worker.execute {
            try {
                val store = if (shareAll) {
                    if (!AllFiles.granted(app)) error(S.allFilesOff)
                    FileStore(AllFiles.roots(app), { file, size -> Thumbs.ofFile(file, size) }, MediaIndex(app))
                } else {
                    val root = DocumentFile.fromTreeUri(app, selected!!) ?: error(S.chooseFolderAgain)
                    SafStore(app.contentResolver, root)
                }
                val created = SharingServer(app, store, Trusted, approver, PhoneClipboard(app), cableOnly, events(current))
                // Stopped meanwhile: free the ports now, so a start queued behind this one gets the usual ones.
                if (generation != current) { created.close(); return@execute }
                main.post {
                    if (generation != current) created.close() else {
                        server = created; busy = false
                        endpoints = created.endpoints
                        created.start()
                        if (!cableOnly && created.endpoints.all { it.via == Via.USB }) note = S.noWifi
                        // Android refuses a background service to an app that isn't visible; sharing then simply
                        // stops when the app is left, as with background sharing off.
                        if (inBackground) runCatching { ShareService.start(app) }
                    }
                }
            } catch (e: Exception) {
                Log.add("Couldn't start sharing: ${e.javaClass.simpleName}")
                main.post {
                    if (generation == current) {
                        busy = false
                        note = when (e) {
                            is SecurityException -> S.folderDenied
                            is IllegalStateException -> e.message ?: S.couldNotStart
                            else -> S.couldNotStart
                        }
                    }
                }
            }
        }
    }

    private fun events(current: Int) = object : SharingServer.Events {
        override fun changed(problem: String?) {
            main.post {
                val running = server ?: return@post
                if (generation != current) return@post
                endpoints = running.endpoints
                when {
                    problem != null -> note = S.stillSharing(problem)
                    endpoints.any { it.via != Via.USB } && note == S.noWifi -> note = null
                }
                ShareService.refresh(app)
            }
        }

        override fun stopped(reason: String) { main.post { if (generation == current) { stop(); note = reason } } }

        override fun seen(computer: String, via: Via) {
            lastComputer = computer
            main.post {
                if (generation != current || server == null) return@post
                if (!paired || this@Sharing.computer != computer || transport != via) {
                    if (!paired || transport != via) Log.add("A computer connected over ${via.label}")
                    paired = true; this@Sharing.computer = computer; transport = via
                    ShareService.refresh(app)
                }
            }
        }
    }

    fun stop(reason: String? = null) {
        generation++
        decidePairing(false)
        if (server != null || busy) Log.add("Stopped sharing")
        server?.close(); server = null; busy = false; paired = false; computer = null; transport = null
        endpoints = emptyList()
        if (reason != null) note = reason
        Hotspot.stop()
        ShareService.stop(app)
    }
}
