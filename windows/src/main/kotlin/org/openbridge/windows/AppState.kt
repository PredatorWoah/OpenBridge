package org.openbridge.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.openbridge.ui.Release
import org.openbridge.ui.Updates
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

enum class SortKey { NAME, SIZE, KIND }

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

/** The Windows app's state. Work runs on one background thread, one task at a time; the UI reads these values. */
class AppState {
    var nearby by mutableStateOf(emptyList<NearbyPhone>()); private set
    var usbPhones by mutableStateOf(emptyList<String>()); private set
    var host by mutableStateOf("")
    var port by mutableStateOf("")
    var pairingCode by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null); private set
    var client by mutableStateOf<BridgeClient?>(null); private set
    var deviceName by mutableStateOf(S.phone); private set
    var path by mutableStateOf(listOf("" to S.phone)); private set
    var entries by mutableStateOf(emptyList<Entry>()); private set
    var selection by mutableStateOf(emptySet<String>()); private set
    var sortKey by mutableStateOf(SortKey.NAME)
    var ascending by mutableStateOf(true)
    var thumbnails by mutableStateOf(emptyMap<String, ImageBitmap>()); private set
    var transfer by mutableStateOf<Transfer?>(null); private set
    var mirroring by mutableStateOf(false); private set
    /** Phones paired before, which reconnect by themselves. */
    var saved by mutableStateOf(Phones.load()); private set
    /** The saved phone connected now, if the connection came from one. */
    private var current: SavedPhone? = null
    /** Off after Disconnect, so the phone doesn't reconnect straight away; on again with the next connection. */
    @Volatile private var autoConnect = true
    var query by mutableStateOf("")
    /** Results of searching every folder below the open one; null while browsing. */
    var results by mutableStateOf<SearchResult?>(null); private set
    var resultsFor by mutableStateOf(""); private set
    var show by mutableStateOf(Show.ALL)
    /** Folder sizes measured on the phone, by entry ID. */
    var sizes by mutableStateOf(emptyMap<String, FolderSize>()); private set
    private val pendingSizes = HashSet<String>()
    /** A newer release, to offer. */
    var update by mutableStateOf<Release?>(null); private set
    /** The last file sent from the phone, to show in Explorer. */
    var received by mutableStateOf<File?>(null); private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "openbridge-work").apply { isDaemon = true } }
    // One preview at a time: the phone serves only a few connections at once, and browsing and transfers come first.
    private val previews = Executors.newSingleThreadExecutor { Thread(it, "openbridge-previews").apply { isDaemon = true } }
    private val discovery = Discovery { found -> ui { nearby = found } }
    @Volatile private var cancelled = false
    // Counts pairings, on the worker thread, so a transfer planned on an earlier connection finds its files again.
    @Volatile private var connection = 0
    private var lastHost: String? = null
    @Volatile private var pairing: java.io.Closeable? = null
    /** True once discovery has run a while without finding a phone, to explain why. */
    var searchedLong by mutableStateOf(false); private set
    private var anchor: String? = null
    private val pendingPreviews = HashSet<String>()

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    fun start() {
        // Discovery runs for the whole session: the phone may stop and start sharing at any time.
        discovery.start()
        // The installed app knows its version; a development run doesn't, and skips the check.
        System.getProperty("jpackage.app-version")?.let { version ->
            Thread { Updates.newerThan(version)?.let { found -> ui { update = found } } }.apply { isDaemon = true }.start()
        }
        Thread {
            while (true) {
                if (client == null && !busy && autoConnect) reconnectSaved()
                Thread.sleep(4_000)
            }
        }.apply { isDaemon = true }.start()
        Thread {
            Thread.sleep(10_000)
            ui { searchedLong = true }
            while (true) {
                // Asking PowerShell costs some CPU, so only look while the cable card is on screen.
                if (client == null) { val found = Usb.phones(); ui { usbPhones = found } }
                Thread.sleep(4_000)
            }
        }.apply { isDaemon = true }.start()
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
                when {
                    cancelled && client == null -> ui { status = "" }   // pairing cancelled
                    e is BridgeException && e.forgotten -> ui { current?.let(::forgetPhone); lose(S.forgotten) }
                    e is BridgeException && e.lost -> ui { lose(e.message ?: S.dropped) }
                    else -> ui { error = e.message ?: e.toString() }
                }
            }
            finally { pairing = null; ui { busy = false; pairingCode = null } }
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
        val paired = BridgeClient.pair(host.trim(), port.trim(), { pairing = it }) { code -> ui { pairingCode = code } }
        val top = paired.list()
        val phone = SavedPhone(name ?: "${S.phone} ${paired.host}", paired.fingerprint, paired.host, paired.port, paired.secret)
        ui { remember(phone) }
        opened(paired, phone, top)
    }

    /** Shows a connected phone, and continues an interrupted transfer when it's the same phone as before. */
    private fun opened(paired: BridgeClient, phone: SavedPhone, top: List<Entry>) {
        connection++
        val samePhone = lastHost == phone.id
        lastHost = phone.id
        autoConnect = true
        ui {
            client = paired; current = phone; deviceName = phone.name
            path = listOf("" to S.phone); entries = top; selection = emptySet(); status = ""; thumbnails = emptyMap(); sizes = emptyMap()
            pendingSizes.clear(); query = ""
            startReceiving(paired)
        }
        // An interrupted transfer continues by itself when the same phone pairs again.
        val pending = transfer?.takeIf { samePhone && !it.done && it.remaining.isNotEmpty() } ?: return
        ui { show(S.continuing(pending.remaining.first().name)) }
        perform(pending.remaining, paired, pending.connection)
    }

    // Files sent from the phone

    /** Collects files shared to OpenBridge on the phone while this connection lasts, into Downloads\OpenBridge. */
    private fun startReceiving(phone: BridgeClient) {
        Thread {
            while (client === phone) {
                val waiting = try { phone.outbox(20_000) } catch (e: BridgeException) {
                    // A phone app before 0.12.0 can't send: stop asking.
                    if (e.message?.let { "Unsupported operation" in it || "not available" in it } == true) return@Thread
                    Thread.sleep(3_000); continue
                } catch (_: Exception) { Thread.sleep(3_000); continue }
                for (item in waiting) {
                    if (client !== phone) break
                    val target = Names.free(File(receivedFolder.apply { mkdirs() }, item.name))
                    try {
                        phone.receive(item, target)
                        ui { received = target; show(S.received(item.name)) }
                    } catch (_: Exception) { Thread.sleep(2_000); break }
                }
            }
        }.apply { isDaemon = true; name = "openbridge-receive" }.start()
    }

    fun openReceived() {
        val file = received
        runCatching {
            if (file != null && file.exists()) ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            else ProcessBuilder("explorer.exe", receivedFolder.apply { mkdirs() }.absolutePath).start()
        }
    }

    // Importing photos

    /** Downloads the camera's photos and videos that weren't imported from this phone before. */
    fun importNewPhotos(chooseFolder: Boolean = false) {
        val phone = client ?: return
        val key = current?.id ?: return
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

    /** DCIM/Camera (or DCIM), looking inside the first storage when the phone lists storages first. */
    private fun cameraFolder(phone: BridgeClient): Pair<String, List<String>>? {
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

    /** Tries each saved phone quietly; the first that answers opens. Runs on its own thread, not the worker. */
    private fun reconnectSaved() {
        for (phone in saved.map(::located)) {
            if (!BridgeClient.reachable(phone.host, phone.port)) continue
            val client = phone.client()
            val top = try { client.list() } catch (e: BridgeException) {
                if (e.forgotten) ui { forgetPhone(phone); error = "${phone.name}: ${S.forgotten}" }
                continue
            } catch (_: Exception) { continue }
            ui {
                if (this.client == null && !busy && autoConnect) {
                    remember(phone)
                    run { opened(client, phone, top) }
                }
            }
            return
        }
    }

    /** Opens a saved phone now (the user clicked it). */
    fun connect(phone: SavedPhone) = run {
        ui { status = S.pairing }
        val target = located(phone)
        val client = target.client()
        val top = client.list()
        ui { remember(target) }
        opened(client, target, top)
    }

    fun pairWith(phone: NearbyPhone) {
        val known = saved.firstOrNull { it.id == phone.id }
        if (known != null) { connect(known); return }
        host = phone.host; port = phone.port; pair(phone.name)
    }

    fun disconnect() {
        if (busy) return
        autoConnect = false
        client = null; current = null; entries = emptyList(); path = listOf("" to S.phone); selection = emptySet(); thumbnails = emptyMap()
    }

    /** The phone is gone (stopped sharing, new identity, left the Wi‑Fi): back to pairing, keeping unfinished transfers. */
    private fun lose(message: String) {
        if (client == null) { error = message; return }
        client = null; current = null; entries = emptyList(); path = listOf("" to S.phone); selection = emptySet(); thumbnails = emptyMap()
        pendingPreviews.clear()
        error = "$message ${S.lostHint}"
    }

    fun cancel() {
        cancelled = true
        runCatching { pairing?.close() }
    }

    fun mirror(wireless: Boolean = false) {
        if (mirroring) return
        mirroring = true
        show(if (wireless) S.mirrorWirelessStarting else S.mirrorStarting)
        Mirror.start(wireless) { problem -> ui { mirroring = false; if (problem != null) error = problem } }
    }

    // Browsing

    val sorted: List<Entry> get() = entries.filter { entry ->
        (query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true)) &&
            (show == Show.ALL || Kind.of(entry).previewable)
    }.sortedWith { a, b ->
        if (a.directory != b.directory) return@sortedWith if (a.directory) -1 else 1
        val order = when (sortKey) {
            SortKey.NAME -> a.name.compareTo(b.name, ignoreCase = true)
            SortKey.SIZE -> sizeOf(a).compareTo(sizeOf(b)).takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
            SortKey.KIND -> Kind.of(a).compareTo(Kind.of(b)).takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true)
        }
        if (ascending) order else -order
    }

    /** A file's size, or a folder's measured size (0 until measured). */
    fun sizeOf(entry: Entry): Long = if (entry.directory) sizes[entry.id]?.bytes ?: 0 else entry.size

    private val trail get() = path.drop(1).map { it.second }

    fun open(entry: Entry) { if (entry.directory) enter(entry) else download(listOf(entry)) }

    fun enter(entry: Entry) {
        val phone = client ?: return
        run { val listed = phone.list(entry.id); ui { display(listed, path + (entry.id to entry.name)) } }
    }

    fun go(level: Int) {
        val phone = client ?: return
        if (level >= path.size - 1) return
        val target = path.take(level + 1)
        run { val listed = phone.list(target.last().first); ui { display(listed, target) } }
    }

    fun up() = go(path.size - 2)

    fun refresh() {
        val phone = client ?: return
        run { val listed = phone.list(path.last().first); ui { entries = listed } }
    }

    private fun display(listed: List<Entry>, at: List<Pair<String, String>>) {
        entries = listed; path = at; selection = emptySet(); anchor = null; query = ""; results = null
    }

    fun changeQuery(text: String) { query = text; if (text.isBlank()) results = null }

    /** Searches every folder below the open one for names containing the query. */
    fun searchEverywhere() {
        val phone = client ?: return
        val text = query.trim()
        if (text.isEmpty()) return
        val folder = path.last().first
        run {
            ui { status = S.searching }
            val found = phone.search(folder, text)
            ui { status = ""; results = found; resultsFor = text }
        }
    }

    /** A folder hit opens; a file hit downloads. */
    fun openFound(hit: Found) { if (hit.entry.directory) reveal(hit, inside = true) else download(listOf(hit.entry)) }

    /** Goes to the folder holding a hit (or into it) and selects it. */
    fun reveal(hit: Found, inside: Boolean = false) {
        val phone = client ?: return
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
                if (!inside) listed.firstOrNull { it.name == hit.entry.name }?.let { selection = setOf(it.id); anchor = it.id }
            }
        }
    }

    /** Click selects, Ctrl-click adds or removes, Shift-click selects a range, like File Explorer. */
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
    }

    fun selectAll() { selection = entries.map { it.id }.toSet() }
    val selected: List<Entry> get() = sorted.filter { it.id in selection }

    fun loadThumbnail(entry: Entry) {
        val phone = client ?: return
        if (!Kind.of(entry).previewable || entry.id in thumbnails || !pendingPreviews.add(entry.id)) return
        previews.execute {
            val image = if (client !== phone) null
                else runCatching { org.jetbrains.skia.Image.makeFromEncoded(phone.thumbnail(entry)).toComposeImageBitmap() }.getOrNull()
            // Without a preview the row keeps its icon; it asks again the next time the folder opens.
            ui { pendingPreviews.remove(entry.id); if (image != null && client === phone) thumbnails = thumbnails + (entry.id to image) }
        }
    }

    /** Measures a folder on the phone, one at a time behind previews; an old phone just shows no size. */
    fun loadSize(entry: Entry) {
        val phone = client ?: return
        if (!entry.directory || entry.id in sizes || !pendingSizes.add(entry.id)) return
        previews.execute {
            val size = if (client !== phone) null else runCatching { phone.measure(entry) }.getOrNull()
            ui { if (size != null && client === phone) sizes = sizes + (entry.id to size) }
        }
    }

    // Editing

    /** Deletes the selected items on the phone (the window asks first). */
    fun deleteSelected() {
        val phone = client ?: return
        val doomed = selected
        if (doomed.isEmpty()) return
        val directory = path.last().first
        run {
            for (entry in doomed) phone.delete(entry)
            val listed = phone.list(directory)
            ui { entries = listed; selection = emptySet(); sizes = emptyMap(); pendingSizes.clear(); show(S.deleted(doomed.size)) }
        }
    }

    fun rename(entry: Entry, name: String) {
        val phone = client ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) return
        val directory = path.last().first
        run {
            val renamed = phone.rename(entry, trimmed)
            val listed = phone.list(directory)
            ui { entries = listed; selection = setOf(renamed.id); anchor = renamed.id }
        }
    }

    // Clipboard and folders

    fun sendClipboard() {
        val phone = client ?: return
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
        if (text.isNullOrEmpty()) { show(S.emptyClipboard); return }
        run { phone.setClipboard(text); ui { show(S.sentClipboard) } }
    }

    fun fetchClipboard() {
        val phone = client ?: return
        run {
            val text = phone.clipboard()
            ui {
                if (text.isEmpty()) show(S.emptyClipboard)
                else { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); show(S.gotClipboard) }
            }
        }
    }

    fun newFolder(name: String) {
        val phone = client ?: return
        val directory = path.last().first
        run { phone.makeFolder(name.trim(), directory); val listed = phone.list(directory); ui { entries = listed } }
    }

    // Transfers

    /** Downloads files and folders into `folder`, keeping folder structure; name clashes get a new name. */
    fun download(items: List<Entry>, folder: File? = null) {
        val phone = client ?: return
        val destination = folder ?: Dialogs.chooseFolder(S.chooseFolder) ?: return
        val at = trail
        run {
            ui { transfer = Transfer("…", upload = false) }
            perform(planDownloads(phone, items, destination, at), phone, connection)
        }
    }

    private fun planDownloads(phone: BridgeClient, items: List<Entry>, folder: File, at: List<String>): List<Job> = items.flatMap { item ->
        val target = Names.free(File(folder, item.name))
        if (item.directory) { target.mkdirs(); planDownloads(phone, phone.list(item.id), target, at + item.name) }
        else listOf(Job.Download(item, target, at))
    }

    fun upload(files: List<File>) {
        val phone = client ?: return
        if (files.isEmpty()) return
        val directory = path.last().first
        val at = trail
        run {
            ui { transfer = Transfer("…", upload = true) }
            perform(planUploads(phone, files, directory, at), phone, connection)
        }
    }

    private fun planUploads(phone: BridgeClient, files: List<File>, directory: String, at: List<String>): List<Job> {
        val taken = phone.list(directory).map { it.name.lowercase() }.toMutableSet()
        return files.filter { !it.isHidden }.flatMap { file ->
            val name = Names.unique(file.name, taken).also { taken += it.lowercase() }
            if (file.isDirectory) {
                val made = phone.makeFolder(name, directory)
                planUploads(phone, file.listFiles()?.toList().orEmpty(), made.id, at + name)
            } else listOf(Job.Upload(file, directory, at, name))
        }
    }

    private fun perform(jobs: List<Job>, phone: BridgeClient, planned: Int) {
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
    private fun uploadNamed(phone: BridgeClient, job: Job.Upload, progress: (Long, Long) -> Unit) {
        if (job.name == job.file.name) { phone.upload(job.file, job.into, { cancelled }, progress); return }
        val staged = File(System.getProperty("java.io.tmpdir"), "OpenBridge Uploads/${System.nanoTime()}").apply { mkdirs() }
        val renamed = File(staged, job.name)
        try {
            runCatching { java.nio.file.Files.createLink(renamed.toPath(), job.file.toPath()) }.onFailure { job.file.copyTo(renamed) }
            phone.upload(renamed, job.into, { cancelled }, progress)
        } finally { staged.deleteRecursively() }
    }

    /** After pairing again, IDs are new: find the job's file or folder by its path. */
    private fun relocate(job: Job, phone: BridgeClient, cache: HashMap<String, List<Entry>>): Job {
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
        val phone = client ?: return
        val pending = transfer?.takeIf { it.remaining.isNotEmpty() } ?: return
        run { perform(pending.remaining, phone, pending.connection) }
    }

    fun dismissTransfer() { transfer = null }
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
