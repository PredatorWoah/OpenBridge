package org.openbridge.android

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import org.openbridge.protocol.Outbox
import org.openbridge.ui.Look

// What the screens show, as plain values. The screens never read the sharing objects directly: they read an [AppModel],
// which in the app reads Sharing, PhoneOutbox, Texts and the rest (so each screen redraws only when what it reads
// changes), and in the screenshot tests is a fixed picture of a moment.

/** Where sharing is. */
enum class Phase { OFF, STARTING, WAITING, CONNECTED }

@Immutable
data class ShareUi(
    val phase: Phase = Phase.OFF,
    /** Sharing over the USB cable only (asked over USB debugging). */
    val cableOnly: Boolean = false,
    val computer: String? = null,
    val transport: Via? = null,
    val endpoints: List<Endpoint> = emptyList(),
    val cable: Cable = Cable.NONE,
    val usbDebugging: Boolean = false,
    /** A problem or hint from sharing, dismissible. */
    val note: String? = null,
    val wholePhone: Boolean = true,
    /** The chosen folder's name, when one was chosen. */
    val folder: String? = null,
    val inBackground: Boolean = true,
) {
    val sharing get() = phase == Phase.WAITING || phase == Phase.CONNECTED
    val running get() = phase != Phase.OFF
    val byCable get() = cable == Cable.FILE_TRANSFER
    /** The hero lights up for a paired computer, or one plugged in by cable in File transfer mode. */
    val connected get() = phase == Phase.CONNECTED || byCable
}

@Immutable
data class PairingUi(val code: String, val computer: String?)

@Immutable
data class OutboxUi(val items: List<PhoneOutbox.Item> = emptyList(), val progress: Map<String, Float> = emptyMap()) {
    val files get() = items.count { it.kind == Outbox.FILE }
    val texts get() = items.size - files
}

@Immutable
data class AppEntry(val packageName: String, val label: String)

@Immutable
data class MirrorUi(val enabled: Boolean = false, val access: Boolean = false, val excluded: Set<String> = emptySet(),
                    val apps: List<AppEntry> = emptyList()) {
    val on get() = enabled && access
}

@Immutable
data class HotspotUi(
    val name: String? = null,
    val password: String? = null,
    val starting: Boolean = false,
    val problem: String? = null,
    /** This Android can start a temporary hotspot itself (13 and later). */
    val supported: Boolean = true,
    /** Sharing listens on a hotspot now (the phone's own or the temporary one). */
    val served: Endpoint? = null,
)

@Immutable
data class UpdateUi(
    val version: String? = null,
    val page: String? = null,
    val step: Updater.Step = Updater.Step.IDLE,
    val progress: Float = 0f,
    val problem: String? = null,
    val checking: Boolean = false,
    val upToDate: Boolean = false,
) {
    val busy get() = step == Updater.Step.DOWNLOADING || step == Updater.Step.VERIFYING || step == Updater.Step.INSTALLING
}

@Stable
interface AppModel {
    val share: ShareUi
    val pairing: PairingUi?
    val outbox: OutboxUi
    val texts: List<Texts.Entry>
    val trusted: List<Computer>
    val mirror: MirrorUi
    val hotspot: HotspotUi
    val update: UpdateUi
    val events: List<Feed.Event>
    /** How much of the item a computer is collecting has gone, 0 to 1 (raises Tide's water). Apart from [share], as it changes often. */
    val collecting: Float
    /** The time relative times are counted from; moves on every half minute. */
    val now: Long
    val version: String
    val look: Look
}

/** What the screens ask for: the activity does it (permissions, pickers, other apps) or hands it to the sharing objects. */
interface Actions {
    /** Starts sharing (asking for the folder or permission first when needed), or stops it. */
    fun toggleSharing()
    fun chooseWholePhone()
    fun chooseFolder()
    fun keepInBackground(on: Boolean)
    fun hotspot(on: Boolean)
    /** The phone's own hotspot settings, where the user turns on the regular hotspot. */
    fun hotspotSettings()
    fun update()
    fun checkForUpdates()
    fun open(url: String)
    fun copy(text: String, sensitive: Boolean = false)
    fun sendClipboard()
    /** Queues a text for the computer; false when it's too long. */
    fun sendText(text: String): Boolean
    /** Picks files on the phone to send to the computer. */
    fun sendFiles()
    fun clearOutbox()
    fun clearTexts()
    fun copyDiagnostics()
    /** Android's notification access screen, for Notifications on computers. */
    fun notificationAccess()
    fun mirror(on: Boolean)
    fun showApp(packageName: String, shown: Boolean)
    fun forget(id: String)
    fun decidePairing(approve: Boolean)
    fun dismissNote()
    fun chooseLook(look: Look)
    fun removeEvent(id: Long)
    fun clearEvents()
    /** Opens a received file or a link from the Activity screen. */
    fun openEvent(event: Feed.Event)
    fun appIcon(packageName: String): ImageBitmap?
    /** A small preview of a photo or video at [location], or null. Off the main thread. */
    suspend fun thumbnail(location: String): ImageBitmap?
}

/** The app's model: every value read from the sharing objects, which are Compose state. */
object LiveModel : AppModel {
    private var clock by mutableLongStateOf(System.currentTimeMillis())
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { clock = System.currentTimeMillis(); main.postDelayed(this, 30_000) }
    }

    /** Relative times move on while the screen is shown. */
    fun resume() { main.removeCallbacks(tick); tick.run() }
    fun pause() = main.removeCallbacks(tick)

    override val share: ShareUi get() {
        val model = Sharing
        val server = model.server
        return ShareUi(
            phase = when {
                model.busy -> Phase.STARTING
                server != null && model.paired -> Phase.CONNECTED
                server != null -> Phase.WAITING
                else -> Phase.OFF
            },
            cableOnly = server?.cableOnly == true,
            computer = model.computer, transport = model.transport, endpoints = model.endpoints,
            cable = model.cable, usbDebugging = model.usbDebugging, note = model.note,
            wholePhone = model.wholePhone,
            folder = model.folder?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { S.storage },
            inBackground = model.inBackground,
        )
    }

    override val pairing: PairingUi? get() = Sharing.pairingCode?.let { PairingUi(it, Sharing.pairingComputer) }
    override val outbox get() = OutboxUi(PhoneOutbox.waiting, PhoneOutbox.progress)
    override val texts get() = Texts.history
    override val trusted get() = Trusted.computers
    override val mirror get() = MirrorUi(Mirror.enabled, Mirror.access, Mirror.excluded,
        Mirror.seen.map { AppEntry(it, Mirror.label(it)) }.sortedBy { it.label.lowercase() })
    override val hotspot get() = HotspotUi(Hotspot.network?.name, Hotspot.network?.password, Hotspot.starting, Hotspot.problem,
        Hotspot.supported, Sharing.endpoints.firstOrNull { it.via == Via.HOTSPOT })
    override val update get() = UpdateUi(Updater.available?.version, Updater.available?.page, Updater.step, Updater.progress, Updater.problem,
        Updater.checking, Updater.upToDate)
    override val events get() = Feed.events
    override val collecting get() = PhoneOutbox.progress.values.maxOrNull() ?: 0f
    override val now get() = clock
    override val version: String get() = BuildConfig.VERSION_NAME
    override val look get() = org.openbridge.ui.Palette.look
}
