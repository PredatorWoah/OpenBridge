package org.openbridge.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.openbridge.desktop.wpd.UsbPhone
import org.openbridge.desktop.wpd.Wpd
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.openbridge.ui.Release
import org.openbridge.ui.Updates
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

enum class SortKey { NAME, SIZE, KIND }

/** Sheets over the window. */
enum class Panel { TEXTS, SETTINGS }

/** What the folder shows: everything, or only photos and videos. */
enum class Show { ALL, MEDIA }

/** What a file is, from its name: decides its icon, sorting group and whether it has a preview. */
enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APP, OTHER;
    val previewable get() = this == IMAGE || this == VIDEO
    companion object {
        fun of(entry: Entry): Kind = if (entry.directory) FOLDER else when (entry.name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "bmp" -> IMAGE
            "mp4", "mov", "mkv", "3gp", "webm", "m4v", "avi" -> VIDEO
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus" -> AUDIO
            "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "rtf", "csv" -> DOCUMENT
            "zip", "rar", "7z", "tar", "gz", "iso" -> ARCHIVE
            "apk", "xapk", "apks" -> APP
            else -> OTHER
        }
    }
}

/** One transfer; `at` is the phone folder by name, so it can be found again after pairing anew. */
sealed class Job(val name: String) {
    class Download(val entry: Entry, val target: File, val at: List<String>) : Job(entry.name)
    class Upload(val file: File, val into: String, val at: List<String>, name: String = file.name) : Job(name)
}

data class Transfer(
    val name: String, val upload: Boolean, val index: Int = 1, val count: Int = 1, val fraction: Float = 0f,
    val done: Boolean = false, val saved: File? = null, val remaining: List<Job> = emptyList(), val connection: Int = 0,
)

/**
 * The desktop app's state. Work runs on one background thread, one task at a time; the UI reads these values.
 * The browser works on any [PhoneSource]: a phone over Wi‑Fi or Fast USB (OB01), or in File transfer mode over USB.
 * (Setters that are internal let the tests draw the screens with chosen values.)
 */
class AppState {
    private val preferences = java.util.prefs.Preferences.userRoot().node("org/openbridge")

    var nearby by mutableStateOf(emptyList<NearbyPhone>()); internal set
    /** Phones in File transfer mode (Windows Portable Devices). */
    var wpdPhones by mutableStateOf(emptyList<UsbPhone>()); internal set
    /** Phones on the cable with USB debugging, for Fast USB. */
    var usbLinks by mutableStateOf(emptyList<UsbLink>()); internal set
    var host by mutableStateOf("")
    var port by mutableStateOf("")
    var pairingCode by mutableStateOf<String?>(null); internal set
    /** The cable a pairing runs through, or null over Wi‑Fi: where the code shows. */
    var pairingOver by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); internal set
    var toast by mutableStateOf<String?>(null); internal set
    /** The phone open in the browser, and how it's connected. */
    var source by mutableStateOf<PhoneSource?>(null); internal set
    var transport by mutableStateOf(Transport.WIFI); internal set
    /** The open phone when it speaks OB01 (Wi‑Fi or Fast USB): texts, the clipboard and files sent from the phone. */
    val bridge: BridgeClient? get() = source as? BridgeClient
    var deviceName by mutableStateOf(S.phone); internal set
    var path by mutableStateOf(listOf("" to S.phone)); internal set
    var entries by mutableStateOf(emptyList<Entry>()); internal set
    var selection by mutableStateOf(emptySet<String>()); internal set
    /** The row the keyboard is on. */
    var cursor by mutableStateOf<String?>(null); internal set
    var sortKey by mutableStateOf(SortKey.NAME)
    var ascending by mutableStateOf(true)
    var thumbnails by mutableStateOf(emptyMap<String, ImageBitmap>()); private set
    var transfer by mutableStateOf<Transfer?>(null); internal set
    var mirroring by mutableStateOf(false); private set
    /** Phones paired before, which reconnect by themselves. */
    var saved by mutableStateOf(Phones.load()); internal set
    /** The saved phone connected now, if the connection came from one. */
    private var current: SavedPhone? = null
    /** The cable a Fast USB connection runs through. */
    private var usbSerial: String? = null
    /** Who the open phone is, for imports and to continue transfers: its fingerprint ID, or its USB device. */
    private var openKey: String? = null
    /** Off after Disconnect, so the phone doesn't reconnect straight away; on again with the next connection. */
    @Volatile private var autoConnect = true
    var query by mutableStateOf("")
    /** Results of searching every folder below the open one; null while browsing. */
    var results by mutableStateOf<SearchResult?>(null); private set
    var resultsFor by mutableStateOf(""); private set
    var show by mutableStateOf(Show.ALL)
    /** Folder sizes measured on the phone, by entry ID. */
    var sizes by mutableStateOf(emptyMap<String, FolderSize>()); internal set
    private val pendingSizes = HashSet<String>()
    /** A newer release, to offer. */
    var update by mutableStateOf<Release?>(null); internal set
    /** The update being installed: what's happening (downloading, checking…), and how far. */
    var updating by mutableStateOf<String?>(null); internal set
    var updateProgress by mutableStateOf<Float?>(null); internal set
    /** The last file sent from the phone, to show in its folder. */
    var received by mutableStateOf<File?>(null); private set
    /** What INFO said about the open phone (null for phones before 0.13.0, and over USB). */
    var phoneInfo by mutableStateOf<PhoneInfo?>(null); internal set
    /** Why the phone app should be updated, when it's older than this computer's. */
    var phoneOutdated by mutableStateOf<String?>(null); internal set
    /** Texts sent either way, newest first. */
    var texts by mutableStateOf(TextHistory.load()); internal set
    var autoSendTexts by mutableStateOf(preferences.getBoolean("autoSendTexts", false)); private set
    /** A phone announces itself here but can't be reached: this Wi‑Fi keeps devices apart. */
    var isolated by mutableStateOf(false); internal set
    /** True once discovery has run a while without finding a phone, to explain why. */
    var searchedLong by mutableStateOf(false); internal set
    /** The sheet open over the window, if any. */
    var panel by mutableStateOf<Panel?>(null)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "openbridge-work").apply { isDaemon = true } }
    // One preview at a time: the phone serves only a few connections at once, and browsing and transfers come first.
    private val previews = Executors.newSingleThreadExecutor { Thread(it, "openbridge-previews").apply { isDaemon = true } }
    // Texts and the clipboard go on their own thread, so they work during a long transfer.
    private val texting = Executors.newSingleThreadExecutor { Thread(it, "openbridge-texts").apply { isDaemon = true } }
    private val discovery = Discovery { found -> ui { nearby = found } }
    private val isolation = Isolation()
    private val watch = ClipboardWatch()
    private var adb: AdbTool? = null
    private var fastUsb: FastUsb? = null
    /** Set when quitting or installing an update: Fast USB stops calling adb, which would start its server again. */
    @Volatile private var quitting = false
    @Volatile private var cancelled = false
    // Counts connections, on the worker thread, so a transfer planned on an earlier one finds its files again.
    @Volatile private var connection = 0
    private var lastKey: String? = null
    @Volatile private var pairing: java.io.Closeable? = null
    private var anchor: String? = null
    private val pendingPreviews = HashSet<String>()

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)
    private fun daemon(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()

    fun start() {
        Log.add("OpenBridge ${AppVersion.current} on ${Platform.description}")
        // Discovery runs for the whole session: the phone may stop and start sharing at any time.
        discovery.start()
        // The installed app knows its version; a development run doesn't, and skips the check.
        if (AppVersion.installed != null) checkForUpdates(manual = false)
        daemon("openbridge-reconnect") {
            while (true) {
                if (source == null && !busy && autoConnect) runCatching { reconnectSaved() }
                Thread.sleep(4_000)
            }
        }
        daemon("openbridge-searching") { Thread.sleep(10_000); ui { searchedLong = true } }
        // Phones in File transfer mode; Windows only. Not while a phone is open over Fast USB: it would be listed twice.
        if (Platform.windows) daemon("openbridge-wpd") {
            while (true) {
                val found = if (source != null && transport == Transport.FAST_USB) emptyList() else runCatching { Wpd.phones() }.getOrDefault(emptyList())
                ui { wpdChanged(found) }
                Thread.sleep(3_000)
            }
        }
        Tools.adb?.let { program ->
            val tool = AdbTool(program).also { adb = it }
            val usb = FastUsb(tool).also { fastUsb = it }
            daemon("openbridge-fast-usb") {
                while (!quitting) {
                    val inUse = usbSerial.takeIf { source != null && transport == Transport.FAST_USB }
                    val links = runCatching { usb.scan(saved, inUse) }.getOrDefault(emptyList())
                    ui { usbChanged(links) }
                    Thread.sleep(if (usb.starting) 1_000 else 3_000)
                }
            }
        } ?: Log.add("Fast USB: adb not found")
        // "Send copied text automatically": a look at the clipboard each second while a phone is connected.
        daemon("openbridge-clipboard") {
            while (true) {
                Thread.sleep(1_000)
                val phone = bridge ?: continue
                if (!autoSendTexts) continue
                val text = watch.changed(SystemClipboard.text()) ?: continue
                texting.execute {
                    try { phone.setClipboard(text); ui { addText(TextItem(text, fromPhone = false, at = System.currentTimeMillis())) } }
                    catch (e: Exception) { Log.add("Copied text not sent: ${e.message}") }
                }
            }
        }
        // Forwards to the phone end with the app; so does the adb server, if this app started it.
        Runtime.getRuntime().addShutdownHook(Thread {
            quitting = true
            runCatching { fastUsb?.close() }
            if (!mirroring) runCatching { adb?.stopOwnServer() }
        })
    }

    fun show(message: String) {
        toast = message
        Thread { Thread.sleep(2_500); ui { if (toast == message) toast = null } }.apply { isDaemon = true }.start()
    }

    /** Runs blocking work in the background; errors become a readable message. */
    private fun run(work: () -> Unit) {
        if (busy) return
        busy = true; error = null; cancelled = false
        worker.execute {
            try { work() }
            catch (e: InterruptedException) { ui { status = S.cancelled; transfer = null } }
            catch (e: Exception) {
                Log.add("Failed (${transport.english}): ${e.message ?: e.javaClass.simpleName}")
                when {
                    cancelled && source == null -> ui { status = "" }   // pairing cancelled
                    e is BridgeException && e.forgotten -> ui { current?.let(::forgetPhone); lose(S.forgotten) }
                    e is BridgeException && e.lost -> ui { lose(e.message ?: S.dropped) }
                    else -> ui { error = e.message ?: e.toString() }
                }
            }
            finally { pairing = null; ui { busy = false; pairingCode = null; pairingOver = null } }
        }
    }

    // Connecting

    fun pair(name: String? = null) {
        // Accept "192.168.1.23:41987" pasted into the address field.
        val typed = host.trim()
        if (typed.count { it == ':' } == 1 && port.isBlank()) { host = typed.substringBefore(':'); port = typed.substringAfter(':') }
        pairNow(name)
    }

    private fun pairNow(name: String?) = run {
        ui { status = S.pairing }
        Log.add("Pairing over Wi-Fi with ${host.trim()}:${port.trim()}")
        val paired = try { BridgeClient.pair(host.trim(), port.trim(), { pairing = it }) { code -> ui { pairingCode = code } } }
            catch (e: BridgeException) { if (e.lost) noteReach(host.trim(), false); throw e }
        val top = paired.list()
        val phone = SavedPhone(name ?: "${S.phone} ${paired.host}", paired.fingerprint, paired.host, paired.port, paired.secret)
        ui { remember(phone) }
        opened(paired, Transport.WIFI, phone.name, phone.id, phone, top)
    }

    /** Pairs with a phone on the cable through its Fast USB tunnel: the same 6-digit comparison as over Wi‑Fi. */
    fun pairOverUsb(link: UsbLink) {
        if (busy) return
        pairingOver = link.serial
        run {
            ui { status = S.pairing }
            Log.add("Pairing over Fast USB with ${link.name}")
            val paired = BridgeClient.pairAt("127.0.0.1", link.port, { pairing = it }, transport = Transport.FAST_USB) { code -> ui { pairingCode = code } }
            val top = paired.list()
            val info = runCatching { paired.info() }.getOrNull()
            val id = paired.fingerprint.take(8).joinToString("") { "%02x".format(it) }
            // Its Wi‑Fi address, if it's announcing one, for when the cable comes out; else it's learned later.
            val seen = nearby.firstOrNull { it.id == id }
            val phone = SavedPhone(info?.name?.takeIf { it.isNotBlank() } ?: link.name, paired.fingerprint, seen?.host ?: "",
                seen?.port?.toIntOrNull() ?: 41987, paired.secret)
            ui { remember(phone) }
            opened(paired, Transport.FAST_USB, phone.name, phone.id, phone, top, link.serial)
        }
    }

    /** Opens a saved phone through its Fast USB tunnel. */
    fun connectUsb(link: UsbLink) {
        val phone = link.phone ?: return
        run {
            ui { status = S.pairing }
            val client = phone.overUsb(link.port)
            val top = client.list()
            ui { remember(phone) }
            opened(client, Transport.FAST_USB, phone.name, phone.id, phone, top, link.serial)
        }
    }

    /** Opens a phone in File transfer mode in the browser. */
    fun openWpd(phone: UsbPhone) = run {
        ui { status = S.pairing }
        Log.add("Opening ${phone.name} over USB (file transfer)")
        val usb = Wpd.open(phone)
        val top = usb.list()
        opened(usb, Transport.USB, phone.name, "usb-" + sha(phone.id), null, top)
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** Shows a connected phone, and continues an interrupted transfer when it's the same phone as before. */
    private fun opened(phone: PhoneSource, via: Transport, name: String, key: String, saved: SavedPhone?, top: List<Entry>, serial: String? = null) {
        connection++
        val samePhone = lastKey == key
        lastKey = key
        autoConnect = true
        Log.add("Connected over ${via.english}" + if (phone is BridgeClient && via == Transport.WIFI) " (${phone.host}:${phone.port})" else "")
        ui {
            source = phone; transport = via; current = saved; usbSerial = serial; openKey = key; deviceName = name
            path = listOf("" to S.phone); entries = top; selection = emptySet(); cursor = null; anchor = null; status = ""; thumbnails = emptyMap(); sizes = emptyMap()
            pendingSizes.clear(); pendingPreviews.clear(); query = ""; results = null; phoneInfo = null; phoneOutdated = null
            isolated = false; isolation.clear()
            if (phone is BridgeClient) { startReceiving(phone); describe(phone); watch.reset(SystemClipboard.text()) }
        }
        // An interrupted transfer continues by itself when the same phone connects again.
        val pending = transfer?.takeIf { samePhone && !it.done && it.remaining.isNotEmpty() } ?: return
        ui { show(S.continuing(pending.remaining.first().name)) }
        perform(pending.remaining, phone, pending.connection)
    }

    /** Moves the open phone to another connection to the same phone (Wi‑Fi ⇄ Fast USB), staying in the same folder. */
    private fun switchTo(phone: BridgeClient, via: Transport, serial: String?) = run {
        val (listed, trail) = follow(phone, path.drop(1).map { it.second })
        connection++   // IDs may differ between the phone's listeners: a later Resume finds its files by name
        Log.add("Switched to ${via.english}")
        ui {
            source = phone; transport = via; usbSerial = serial
            entries = listed; path = trail; selection = emptySet(); cursor = null; anchor = null; results = null
            startReceiving(phone)
            show(S.switchedTo(via.label))
        }
    }

    /** Lists the folder at `names` from the top, as far as it still exists. */
    private fun follow(phone: PhoneSource, names: List<String>): Pair<List<Entry>, List<Pair<String, String>>> {
        var trail = listOf("" to S.phone)
        var listed = phone.list("")
        for (name in names) {
            val next = listed.firstOrNull { it.directory && it.name == name } ?: break
            listed = phone.list(next.id); trail = trail + (next.id to next.name)
        }
        return listed to trail
    }

    /** Asks the phone app's version once; tells the user to update it when it's older than this app. */
    private fun describe(phone: BridgeClient) = texting.execute {
        val info = try { phone.info() } catch (e: BridgeException) { if (!e.unsupported) return@execute; null } catch (_: Exception) { return@execute }
        Log.add(if (info != null) "Phone app ${info.version} (revision ${info.revision})" else "Phone app older than 0.13.0 (no INFO)")
        val mine = AppVersion.current
        val outdated = when {
            mine == "dev" -> null
            info == null -> S.phoneOld(mine)
            Updates.isNewer(mine, info.version) -> S.phoneOlder(info.version, mine)
            else -> null
        }
        ui { if (source === phone) { phoneInfo = info; phoneOutdated = outdated } }
    }

    fun dismissOutdated() { phoneOutdated = null }

    // Files and texts sent from the phone

    /**
     * Collects what's shared to OpenBridge on the phone while this connection lasts: files into Downloads/OpenBridge,
     * texts onto the clipboard. Phones before 0.13.0 can't send texts (OUTBOX ALL): then files only.
     */
    private fun startReceiving(phone: BridgeClient) = daemon("openbridge-receive") {
        var withTexts = true
        while (source === phone) {
            val waiting = try {
                if (withTexts) phone.outboxAll(20_000) else phone.outbox(20_000).map { Waiting(it, false) }
            } catch (e: BridgeException) {
                if (withTexts && e.unsupported) { withTexts = false; continue }
                // A phone app before 0.12.0 can't send: stop asking.
                if (e.unsupported || e.message?.contains("not available") == true) return@daemon
                Thread.sleep(3_000); continue
            } catch (_: Exception) { Thread.sleep(3_000); continue }
            for (item in waiting) {
                if (source !== phone) break
                try {
                    if (item.text) {
                        val text = phone.readText(item.entry)
                        // On the clipboard before the phone drops its copy.
                        SwingUtilities.invokeAndWait { textArrived(text) }
                        phone.collected(item.entry)
                    } else {
                        val target = Names.free(File(receivedFolder.apply { mkdirs() }, item.entry.name))
                        phone.receive(item.entry, target)
                        ui { received = target; show(S.received(item.entry.name)) }
                    }
                } catch (_: Exception) { Thread.sleep(2_000); break }
            }
        }
    }

    private fun textArrived(text: String) {
        watch.arrived(text)
        SystemClipboard.put(text)
        addText(TextItem(text, fromPhone = true, at = System.currentTimeMillis()))
        show(S.textFrom(deviceName))
    }

    private fun addText(item: TextItem) {
        texts = (listOf(item) + texts).take(TextHistory.KEEP)
        val snapshot = texts
        texting.execute { TextHistory.save(snapshot) }
    }

    fun clearTexts() { texts = emptyList(); texting.execute { TextHistory.save(emptyList()) } }

    /** Sends text to the phone's clipboard (CLIPBOARD SET). */
    fun sendText(text: String, done: () -> Unit = {}) {
        val phone = bridge ?: return
        if (text.isEmpty()) { show(S.emptyClipboard); return }
        if (text.toByteArray().size > org.openbridge.protocol.Wire.MAX_CLIPBOARD) { show(S.tooLong); return }
        texting.execute {
            try { phone.setClipboard(text); ui { addText(TextItem(text, false, System.currentTimeMillis())); show(S.sentText); done() } }
            catch (e: Exception) { ui { show(e.message ?: S.dropped) } }
        }
    }

    fun sendClipboard() {
        val text = SystemClipboard.text()
        if (text.isNullOrEmpty()) { show(S.emptyClipboard); return }
        sendText(text)
    }

    fun fetchClipboard() {
        val phone = bridge ?: return
        texting.execute {
            try {
                val text = phone.clipboard()
                ui {
                    if (text.isEmpty()) show(S.emptyClipboard)
                    else { watch.arrived(text); SystemClipboard.put(text); addText(TextItem(text, true, System.currentTimeMillis())); show(S.gotClipboard) }
                }
            } catch (e: Exception) { ui { show(e.message ?: S.dropped) } }
        }
    }

    fun copyText(text: String) { watch.arrived(text); SystemClipboard.put(text); show(S.copied) }

    fun changeAutoSend(on: Boolean) {
        autoSendTexts = on
        preferences.putBoolean("autoSendTexts", on)
        if (on) watch.reset(SystemClipboard.text())
    }

    fun openReceived() {
        val file = received
        if (file != null && file.exists()) Platform.reveal(file) else Platform.openFolder(receivedFolder.apply { mkdirs() })
    }

    // Importing photos

    /** Downloads the camera's photos and videos that weren't imported from this phone before (any connection). */
    fun importNewPhotos(chooseFolder: Boolean = false) {
        val phone = source ?: return
        val key = openKey ?: return
        val destination = Imports.folder(chooseFolder) ?: return
        run {
            val (folderId, at) = cameraFolder(phone) ?: throw BridgeException(S.noCamera)
            val media = phone.list(folderId).filter { !it.directory && Kind.of(it).previewable }
            val done = Imports.load(key)
            // Already imported, or already in the folder (an import that was cut off): skip.
            val fresh = media.filter { item ->
                Imports.line(item) !in done && File(destination, item.name).let { !(it.isFile && it.length() == item.size) }
            }
            if (fresh.isEmpty()) {
                Imports.save(key, done + media.map(Imports::line))
                ui { show(S.noNewPhotos) }
                return@run
            }
            ui { transfer = Transfer("…", upload = false) }
            perform(fresh.map { Job.Download(it, Names.free(File(destination, it.name)), at) }, phone, connection)
            Imports.save(key, done + media.map(Imports::line))
            ui { show(S.imported(fresh.size)) }
        }
    }

    /** DCIM/Camera (or DCIM), looking inside the first storage when the phone lists storages first (as over USB). */
    private fun cameraFolder(phone: PhoneSource): Pair<String, List<String>>? {
        var trail = emptyList<String>()
        var entries = phone.list("")
        if (entries.none { it.directory && it.name.equals("DCIM", ignoreCase = true) }) {
            val storage = entries.firstOrNull { it.directory } ?: return null
            entries = phone.list(storage.id); trail = listOf(storage.name)
        }
        val dcim = entries.firstOrNull { it.directory && it.name.equals("DCIM", ignoreCase = true) } ?: return null
        val camera = phone.list(dcim.id).firstOrNull { it.directory && it.name.equals("Camera", ignoreCase = true) }
            ?: return dcim.id to trail + dcim.name
        return camera.id to trail + dcim.name + camera.name
    }

    // Saved phones

    private fun remember(phone: SavedPhone) {
        saved = listOf(phone) + saved.filter { it != phone }
        runCatching { Phones.save(saved) }
    }

    fun forgetPhone(phone: SavedPhone) {
        saved = saved.filter { it != phone }
        runCatching { Phones.save(saved) }
    }

    /** Where a saved phone is now: its announced address on this Wi‑Fi, or where it was last time. */
    private fun located(phone: SavedPhone): SavedPhone {
        val seen = nearby.firstOrNull { it.id == phone.id } ?: return phone
        return phone.copy(host = seen.host, port = seen.port.toIntOrNull() ?: phone.port)
    }

    fun isNearby(phone: SavedPhone) = nearby.any { it.id == phone.id }

    /** The saved phone's Fast USB tunnel, when it's on the cable and answering. */
    fun onCable(phone: SavedPhone) = usbLinks.firstOrNull { it.ready && it.phone == phone }

    /**
     * Tries each saved phone quietly; the first that answers opens. Besides its address, the default gateway is
     * tried, for a phone running its own hotspot. Runs on its own thread, not the worker.
     */
    private fun reconnectSaved() {
        val gateway = Network.gateway()
        for (phone in saved) {
            val here = located(phone)
            val candidates = buildList {
                if (here.host.isNotBlank()) add(here)
                if (gateway != null && gateway != here.host) {
                    add(phone.copy(host = gateway, port = 41987))
                    if (phone.port != 41987) add(phone.copy(host = gateway))
                }
            }
            for (target in candidates) {
                val reached = BridgeClient.reachable(target.host, target.port)
                if (target === here && isNearby(phone)) noteReach(phone.id, reached)
                if (!reached) continue
                // Only the phone's own address can say it forgot this computer; at the gateway another certificate
                // just means another device, so it's checked before anything is sent.
                if (target !== here && BridgeClient.fingerprintAt(target.host, target.port)?.contentEquals(phone.fingerprint) != true) continue
                val client = target.client()
                val top = try { client.list() } catch (e: BridgeException) {
                    if (e.forgotten && target === here) ui { forgetPhone(phone); error = "${phone.name}: ${S.forgotten}" }
                    continue
                } catch (_: Exception) { continue }
                ui {
                    if (source == null && !busy && autoConnect) {
                        remember(if (target === here) here else phone)
                        run { opened(client, Transport.WIFI, phone.name, phone.id, phone, top) }
                    }
                }
                return
            }
        }
        // Phones announcing themselves that aren't saved: can their port be reached at all?
        for (phone in nearby.filter { n -> saved.none { it.id == n.id } }) {
            val port = phone.port.toIntOrNull() ?: continue
            noteReach(phone.id ?: phone.host, BridgeClient.reachable(phone.host, port))
        }
    }

    private fun noteReach(phone: String, reached: Boolean) {
        val now = isolation.attempt(phone, reached)
        if (now != isolated) { if (now) Log.add("A phone announces itself but can't be reached: the Wi-Fi keeps devices apart"); ui { isolated = now } }
    }

    /** Opens a saved phone now (the user clicked it): over the cable when it's there, else over Wi‑Fi. */
    fun connect(phone: SavedPhone) {
        onCable(phone)?.let { connectUsb(it); return }
        run {
            ui { status = S.pairing }
            val target = located(phone)
            if (target.host.isBlank()) throw BridgeException(S.notSharing)
            Log.add("Connecting over Wi-Fi to ${target.host}:${target.port}")
            val client = target.client()
            val top = try { client.list() } catch (e: BridgeException) { if (e.lost && isNearby(phone)) noteReach(phone.id, false); throw e }
            ui { remember(target) }
            opened(client, Transport.WIFI, phone.name, phone.id, phone, top)
        }
    }

    fun pairWith(phone: NearbyPhone) {
        val known = saved.firstOrNull { it.id == phone.id }
        if (known != null) { connect(known); return }
        host = phone.host; port = phone.port; pair(phone.name)
    }

    private fun wpdChanged(found: List<UsbPhone>) {
        if (found.map { it.id } != wpdPhones.map { it.id }) Log.add("USB file transfer: ${found.size} phone(s)")
        wpdPhones = found
        // The phone open in File transfer mode was unplugged: back to the start (a transfer fails by itself).
        if (source != null && transport == Transport.USB && !busy && found.none { "usb-" + sha(it.id) == openKey }) lose(S.cableOut)
    }

    /** What Fast USB found on the cable: connect a saved phone, prefer the cable for the open phone, notice it leave. */
    private fun usbChanged(links: List<UsbLink>) {
        usbLinks = links
        val open = source
        if (open != null && transport == Transport.FAST_USB && links.none { it.serial == usbSerial && it.ready }) { cableOut(); return }
        if (busy || !autoConnect) return
        val known = links.firstOrNull { it.ready && it.phone != null } ?: return
        when {
            open == null -> connectUsb(known)
            transport == Transport.WIFI && current != null -> links.firstOrNull { it.ready && it.phone == current }?.let { link ->
                switchTo(link.phone!!.overUsb(link.port), Transport.FAST_USB, link.serial)
            }
        }
    }

    /** The cable came out under a Fast USB connection: carry on over Wi‑Fi when the phone is there. */
    private fun cableOut() {
        if (busy) return   // the transfer fails by itself; reconnecting continues it
        val phone = current
        Log.add("Fast USB cable unplugged")
        usbSerial = null
        run {
            val target = phone?.let(::located)?.takeIf { it.host.isNotBlank() && BridgeClient.reachable(it.host, it.port) }
            if (target == null) { ui { lose(S.cableOut) }; return@run }
            val client = target.client()
            val (listed, trail) = follow(client, path.drop(1).map { it.second })
            connection++
            ui {
                source = client; transport = Transport.WIFI; entries = listed; path = trail; selection = emptySet(); cursor = null; results = null
                startReceiving(client)
                show(S.switchedTo(S.wifi))
            }
        }
    }

    fun disconnect() {
        if (busy) return
        autoConnect = false
        Log.add("Disconnected by the user")
        source = null; current = null; usbSerial = null; entries = emptyList(); path = listOf("" to S.phone); selection = emptySet(); thumbnails = emptyMap()
        phoneInfo = null; phoneOutdated = null
    }

    /** The phone is gone (stopped sharing, new identity, left the Wi‑Fi, cable out): back to the start, keeping unfinished transfers. */
    private fun lose(message: String) {
        if (source == null) { error = message; return }
        val hint = if (transport == Transport.WIFI) S.lostHint else S.usbLostHint
        Log.add("Lost the phone (${transport.english}): $message")
        source = null; current = null; usbSerial = null; entries = emptyList(); path = listOf("" to S.phone); selection = emptySet(); thumbnails = emptyMap()
        pendingPreviews.clear(); phoneInfo = null; phoneOutdated = null
        error = "$message $hint"
    }

    fun cancel() {
        cancelled = true
        runCatching { pairing?.close() }
    }

    fun mirror(wireless: Boolean = false) {
        if (mirroring) return
        mirroring = true
        show(if (wireless) S.mirrorWirelessStarting else S.mirrorStarting)
        // With several phones on the cable, the open one (or the only one that allows USB debugging).
        val serial = usbSerial ?: usbLinks.filter { it.state != UsbLink.State.UNAUTHORIZED }.singleOrNull()?.serial
        Log.add("Mirroring started${if (wireless) " (over Wi-Fi)" else ""}")
        Mirror.start(wireless, serial) { problem -> ui { mirroring = false; if (problem != null) error = problem } }
    }

    // Browsing

    val sorted: List<Entry> get() = entries.filter { entry ->
        (query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true)) &&
            (show == Show.ALL || Kind.of(entry).previewable)
    }.sortedWith(order)

    /** Search hits in the chosen order, with the photos-and-videos filter, never the same item twice. */
    val sortedResults: List<Found> get() = (results?.found ?: emptyList()).distinctBy { it.entry.id }
        .filter { show == Show.ALL || Kind.of(it.entry).previewable }
        .sortedWith(compareBy(order) { it.entry })

    /** Folders first, then by the chosen key and direction. */
    private val order: Comparator<Entry> get() = Comparator { a, b ->
        if (a.directory != b.directory) return@Comparator if (a.directory) -1 else 1
        val result = when (sortKey) {
            SortKey.NAME -> a.name.compareTo(b.name, ignoreCase = true)
            SortKey.SIZE -> sizeOf(a).compareTo(sizeOf(b)).takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
            SortKey.KIND -> Kind.of(a).compareTo(Kind.of(b)).takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
        }
        if (ascending) result else -result
    }

    /** A file's size, or a folder's measured size (0 until measured). */
    fun sizeOf(entry: Entry): Long = if (entry.directory) sizes[entry.id]?.bytes ?: 0 else entry.size

    private val trail get() = path.drop(1).map { it.second }

    fun open(entry: Entry) { if (entry.directory) enter(entry) else download(listOf(entry)) }

    fun enter(entry: Entry) {
        val phone = source ?: return
        run { val listed = phone.list(entry.id); ui { display(listed, path + (entry.id to entry.name)) } }
    }

    fun go(level: Int) {
        val phone = source ?: return
        if (level >= path.size - 1) return
        val target = path.take(level + 1)
        run { val listed = phone.list(target.last().first); ui { display(listed, target) } }
    }

    fun up() = go(path.size - 2)

    fun refresh() {
        val phone = source ?: return
        run { val listed = phone.list(path.last().first); ui { entries = listed } }
    }

    private fun display(listed: List<Entry>, at: List<Pair<String, String>>) {
        entries = listed; path = at; selection = emptySet(); anchor = null; cursor = null; query = ""; results = null
    }

    fun changeQuery(text: String) { query = text; if (text.isBlank()) results = null }

    /** Searches every folder below the open one for names containing the query. */
    fun searchEverywhere() {
        val phone = source ?: return
        val text = query.trim()
        if (text.isEmpty()) return
        val folder = path.last().first
        run {
            ui { status = S.searching }
            val found = phone.search(folder, text)
            ui { status = ""; results = found; resultsFor = text; selection = emptySet(); cursor = null }
        }
    }

    /** A folder hit opens; a file hit downloads. */
    fun openFound(hit: Found) { if (hit.entry.directory) reveal(hit, inside = true) else download(listOf(hit.entry)) }

    /** Goes to the folder holding a hit (or into it) and selects it. */
    fun reveal(hit: Found, inside: Boolean = false) {
        val phone = source ?: return
        val names = path.drop(1).map { it.second } + hit.location.split('/').filter { it.isNotEmpty() } + (if (inside) listOf(hit.entry.name) else emptyList())
        run {
            var trail = listOf("" to S.phone)
            var listed = phone.list("")
            for (name in names) {
                val next = listed.firstOrNull { it.directory && it.name == name } ?: throw BridgeException(S.folderGone)
                listed = phone.list(next.id); trail = trail + (next.id to next.name)
            }
            ui {
                display(listed, trail)
                if (!inside) listed.firstOrNull { it.name == hit.entry.name }?.let { selection = setOf(it.id); anchor = it.id; cursor = it.id }
            }
        }
    }

    /** Click selects, Ctrl-click adds or removes, Shift-click selects a range, like a file manager. */
    fun click(entry: Entry, ctrl: Boolean, shift: Boolean) {
        val list = sorted
        val from = list.indexOfFirst { it.id == anchor }
        val to = list.indexOfFirst { it.id == entry.id }
        selection = when {
            ctrl -> if (entry.id in selection) selection - entry.id else selection + entry.id
            shift && from >= 0 && to >= 0 -> list.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }.toSet()
            else -> setOf(entry.id)
        }
        if (!shift) anchor = entry.id
        cursor = entry.id
    }

    /** The rows the keyboard moves through: search hits while searching, else the folder. */
    private val rows: List<String> get() = if (results != null) sortedResults.map { it.entry.id } else sorted.map { it.id }

    /**
     * Arrow keys: moves the keyboard's row by `delta` (or to `index`, -1 for the last), selecting it; with Shift the
     * selection stretches from where it started.
     */
    fun move(delta: Int, extend: Boolean = false, index: Int? = null) {
        val ids = rows
        if (ids.isEmpty()) return
        val at = ids.indexOf(cursor)
        val next = when {
            index != null -> if (index < 0) ids.lastIndex else index
            at < 0 -> if (delta >= 0) 0 else ids.lastIndex
            else -> at + delta
        }.coerceIn(0, ids.lastIndex)
        cursor = ids[next]
        val from = ids.indexOf(anchor)
        if (extend && from >= 0) selection = ids.subList(minOf(from, next), maxOf(from, next) + 1).toSet()
        else { selection = setOf(ids[next]); anchor = ids[next] }
    }

    /** Enter: opens the keyboard's row (or the selection), a search hit like a double click. */
    fun openSelected() {
        if (results != null) { sortedResults.firstOrNull { it.entry.id == cursor } ?.let(::openFound) ?: sortedResults.firstOrNull { it.entry.id in selection }?.let(::openFound); return }
        val chosen = selected
        when {
            chosen.size > 1 -> download(chosen)
            chosen.size == 1 -> open(chosen[0])
        }
    }

    /** Esc: clears the search, then the selection. False when there was nothing to clear. */
    fun escape(): Boolean = when {
        query.isNotEmpty() || results != null -> { changeQuery(""); results = null; true }
        selection.isNotEmpty() -> { selection = emptySet(); true }
        else -> false
    }

    fun selectAll() { selection = rows.toSet() }
    val selected: List<Entry> get() = sorted.filter { it.id in selection }

    fun loadThumbnail(entry: Entry) {
        val phone = source ?: return
        if (!Kind.of(entry).previewable || entry.id in thumbnails || !pendingPreviews.add(entry.id)) return
        previews.execute {
            val image = if (source !== phone) null
                else runCatching { org.jetbrains.skia.Image.makeFromEncoded(phone.thumbnail(entry)).toComposeImageBitmap() }.getOrNull()
            // Without a preview the row keeps its icon; it asks again the next time the folder opens.
            ui { pendingPreviews.remove(entry.id); if (image != null && source === phone) thumbnails = thumbnails + (entry.id to image) }
        }
    }

    /** Measures a folder on the phone, one at a time behind previews; an old phone just shows no size. */
    fun loadSize(entry: Entry) {
        val phone = source ?: return
        if (!entry.directory || entry.id in sizes || !pendingSizes.add(entry.id)) return
        previews.execute {
            val size = if (source !== phone) null else runCatching { phone.measure(entry) }.getOrNull()
            ui { if (size != null && source === phone) sizes = sizes + (entry.id to size) }
        }
    }

    // Editing

    /** Deletes the selected items on the phone (the window asks first). */
    fun deleteSelected() {
        val phone = source ?: return
        val doomed = selected
        if (doomed.isEmpty()) return
        val directory = path.last().first
        run {
            for (entry in doomed) phone.delete(entry)
            val listed = phone.list(directory)
            ui { entries = listed; selection = emptySet(); cursor = null; sizes = emptyMap(); pendingSizes.clear(); show(S.deleted(doomed.size)) }
        }
    }

    fun rename(entry: Entry, name: String) {
        val phone = source ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) return
        val directory = path.last().first
        run {
            val renamed = phone.rename(entry, trimmed)
            val listed = phone.list(directory)
            ui { entries = listed; selection = setOf(renamed.id); anchor = renamed.id; cursor = renamed.id }
        }
    }

    fun newFolder(name: String) {
        val phone = source ?: return
        val directory = path.last().first
        run { phone.makeFolder(name.trim(), directory); val listed = phone.list(directory); ui { entries = listed } }
    }

    // Transfers

    /** Downloads files and folders into `folder`, keeping folder structure; name clashes get a new name. */
    fun download(items: List<Entry>, folder: File? = null) {
        val phone = source ?: return
        val destination = folder ?: Dialogs.chooseFolder(S.chooseFolder) ?: return
        val at = trail
        run {
            ui { transfer = Transfer("…", upload = false) }
            perform(planDownloads(phone, items, destination, at), phone, connection)
        }
    }

    private fun planDownloads(phone: PhoneSource, items: List<Entry>, folder: File, at: List<String>): List<Job> = items.flatMap { item ->
        val target = Names.free(File(folder, item.name))
        if (item.directory) { target.mkdirs(); planDownloads(phone, phone.list(item.id), target, at + item.name) }
        else listOf(Job.Download(item, target, at))
    }

    fun upload(files: List<File>) {
        val phone = source ?: return
        if (files.isEmpty()) return
        val directory = path.last().first
        val at = trail
        run {
            ui { transfer = Transfer("…", upload = true) }
            perform(planUploads(phone, files, directory, at), phone, connection)
        }
    }

    private fun planUploads(phone: PhoneSource, files: List<File>, directory: String, at: List<String>): List<Job> {
        val taken = phone.list(directory).map { it.name.lowercase() }.toMutableSet()
        return files.filter { !it.isHidden }.flatMap { file ->
            val name = Names.unique(file.name, taken).also { taken += it.lowercase() }
            if (file.isDirectory) {
                val made = phone.makeFolder(name, directory)
                planUploads(phone, file.listFiles()?.toList().orEmpty(), made.id, at + name)
            } else listOf(Job.Upload(file, directory, at, name))
        }
    }

    private fun perform(jobs: List<Job>, phone: PhoneSource, planned: Int) {
        if (jobs.isEmpty()) { ui { transfer = null }; return }
        val upload = jobs[0] is Job.Upload
        var last: File? = null
        val folders = HashMap<String, List<Entry>>()
        jobs.forEachIndexed { index, job ->
            if (cancelled) throw InterruptedException()
            ui { transfer = Transfer(job.name, upload, index + 1, jobs.size, 0f, remaining = jobs.drop(index), connection = planned) }
            val current = if (planned == connection) job else relocate(job, phone, folders)
            val progress = { done: Long, total: Long -> ui { transfer = transfer?.copy(fraction = if (total == 0L) 1f else done.toFloat() / total) } }
            when (current) {
                is Job.Download -> { phone.download(current.entry, current.target, { cancelled }, progress); last = current.target }
                is Job.Upload -> uploadNamed(phone, current, progress)
            }
        }
        val listed = runCatching { phone.list(path.last().first) }.getOrNull()
        ui {
            transfer = transfer?.copy(done = true, fraction = 1f, saved = last, remaining = emptyList())
            if (upload && listed != null) entries = listed
        }
    }

    /** Uploads under the job's name, which may differ from the file's own when the name was taken. */
    private fun uploadNamed(phone: PhoneSource, job: Job.Upload, progress: (Long, Long) -> Unit) {
        if (job.name == job.file.name) { phone.upload(job.file, job.into, { cancelled }, progress); return }
        val staged = File(System.getProperty("java.io.tmpdir"), "OpenBridge Uploads/${System.nanoTime()}").apply { mkdirs() }
        val renamed = File(staged, job.name)
        try {
            runCatching { java.nio.file.Files.createLink(renamed.toPath(), job.file.toPath()) }.onFailure { job.file.copyTo(renamed) }
            phone.upload(renamed, job.into, { cancelled }, progress)
        } finally { staged.deleteRecursively() }
    }

    /** After connecting again, IDs may be new: find the job's file or folder by its path. */
    private fun relocate(job: Job, phone: PhoneSource, cache: HashMap<String, List<Entry>>): Job {
        fun listing(id: String, names: List<String>) = cache.getOrPut(names.joinToString("/")) { phone.list(id) }
        fun folder(names: List<String>): Pair<String, List<Entry>> {
            var id = ""
            var entries = listing("", emptyList())
            names.forEachIndexed { depth, name ->
                val next = entries.firstOrNull { it.directory && it.name == name } ?: throw BridgeException("“${names.joinToString(" › ")}” is no longer on the phone.")
                id = next.id
                entries = listing(id, names.take(depth + 1))
            }
            return id to entries
        }
        return when (job) {
            is Job.Download -> {
                val file = folder(job.at).second.firstOrNull { !it.directory && it.name == job.entry.name }
                    ?: throw BridgeException("“${job.entry.name}” is no longer on the phone.")
                Job.Download(file, job.target, job.at)
            }
            is Job.Upload -> Job.Upload(job.file, folder(job.at).first, job.at, job.name)
        }
    }

    fun resume() {
        val phone = source ?: return
        val pending = transfer?.takeIf { it.remaining.isNotEmpty() } ?: return
        run { perform(pending.remaining, phone, pending.connection) }
    }

    fun dismissTransfer() { transfer = null }

    // Updates and settings

    /** Asks GitHub for a newer release; `manual` (from settings) says when there's none. */
    fun checkForUpdates(manual: Boolean) {
        if (manual) updating = S.checking
        daemon("openbridge-update-check") {
            val found = Updates.newerThan(AppVersion.current)
            Log.add("Update check: ${found?.version ?: "nothing newer found"}")
            ui {
                if (manual) { updating = null; if (found == null) show(S.upToDate) }
                if (found != null) update = found
            }
        }
    }

    /** Whether "Update" can download and install here (not in development runs). */
    val canInstallUpdate: Boolean get() = Updater.installedFrom != null

    /**
     * Downloads the new release for this computer, checks it against the published SHA-256 and installs it: on
     * Windows the setup runs and this app quits; on Linux the .deb opens in the system's installer, or the archive
     * is saved and shown. Anything going wrong opens the release page instead.
     */
    fun installUpdate(openPage: (String) -> Unit) {
        val release = update ?: return
        val kind = Updater.installedFrom ?: return openPage(release.page)
        if (updating != null) return
        updating = S.downloading(0); updateProgress = 0f
        Log.add("Updating to ${release.version} (${kind.name})")
        daemon("openbridge-update") {
            try {
                val folder = if (kind == Updater.Package.WINDOWS_SETUP) java.nio.file.Files.createTempDirectory("openbridge-update").toFile() else Platform.downloads
                val file = Updater.download(release.version, kind, folder) { fraction ->
                    ui { updating = S.downloading((fraction * 100).toInt()); updateProgress = fraction }
                }
                ui { updating = S.verifying }
                Log.add("Update ${release.version} downloaded and checked")
                when (kind) {
                    Updater.Package.WINDOWS_SETUP -> {
                        ui { updating = S.openingInstaller }
                        // The installer replaces files that mirroring and adb run from: close them first.
                        Mirror.stop()
                        quitting = true
                        runCatching { fastUsb?.close() }
                        runCatching { adb?.stopOwnServer() }
                        ProcessBuilder(file.path).start()
                        Thread.sleep(1_500)
                        exitProcess(0)
                    }
                    Updater.Package.LINUX_DEB -> { Platform.open(file); ui { updating = S.debOpened; updateProgress = null } }
                    Updater.Package.LINUX_ARCHIVE -> { Platform.reveal(file); ui { updating = S.archiveSaved(file.name); updateProgress = null } }
                }
            } catch (e: Exception) {
                Log.add("Update failed: ${e.message}")
                ui { updating = null; updateProgress = null; error = if (e.message == S.checksumFailed) S.checksumFailed else S.updateFailed }
                openPage(release.page)
            }
        }
    }

    fun dismissUpdateNote() { if (updateProgress == null) updating = null }

    fun changeLook(look: Look) {
        Palette.look = look
        preferences.put("look", look.id)
        Log.add("Look: ${look.label}")
    }

    companion object {
        /** The remembered look, applied before the window first draws. */
        fun rememberedLook(): Look = Look.of(java.util.prefs.Preferences.userRoot().node("org/openbridge").get("look", null))
    }
}

object Names {
    /** "photo.jpg" becomes "photo 2.jpg", "photo 3.jpg"…, skipping names in `taken` (compared ignoring case). */
    fun unique(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
        val stem = if (ext.isEmpty()) name else base
        return generateSequence(2) { it + 1 }.map { "$stem $it$ext" }.first { it.lowercase() !in taken }
    }

    /** A destination that doesn't exist yet: "photo 2.jpg" if "photo.jpg" is there. Never replaces a file. */
    fun free(file: File): File {
        if (!file.exists()) return file
        val taken = file.parentFile.list()?.map { it.lowercase() }?.toSet().orEmpty()
        return File(file.parentFile, unique(file.name, taken))
    }
}
