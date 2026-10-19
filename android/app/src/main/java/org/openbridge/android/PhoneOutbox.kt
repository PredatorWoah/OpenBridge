package org.openbridge.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.openbridge.protocol.Outbox
import org.openbridge.protocol.Store
import org.openbridge.protocol.Wire
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock

/**
 * Files and texts sent from the phone (Share › OpenBridge, Send clipboard), waiting until a paired computer collects
 * them. Most are copied into app-private storage and deleted once collected or cleared. With All files access, a
 * shared file that is a real file in shared storage isn't copied: the outbox keeps a reference (its path, size and
 * modification time) and serves the original, dropping it if the file changed or went away.
 */
object PhoneOutbox : Outbox {
    /** A waiting item: a copy named [id] in the outbox folder, or the original at [path] when it's a reference. */
    data class Item(val id: String, val name: String, val size: Long, val kind: Int = Outbox.FILE,
                    val path: String? = null, val modified: Long = 0)

    private lateinit var folder: File
    private val items = mutableListOf<Item>()
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val arrived = lock.newCondition()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** For the screen: items waiting for a computer. */
    var waiting by mutableStateOf(emptyList<Item>()); private set
    /** For the screen: how much of each item a computer has collected so far (0 to 1), by ID. */
    var progress by mutableStateOf(emptyMap<String, Float>()); private set
    private val sending = ConcurrentHashMap<String, Float>()
    @Volatile private var progressShown = 0L
    /** Told when a computer collected an item (on a server thread). */
    @Volatile var onCollected: (Item) -> Unit = {}

    fun attach(context: Context) {
        if (::folder.isInitialized) return
        folder = File(context.filesDir, "outbox").apply { mkdirs() }
        val saved = runCatching { JSONArray(File(folder, "index.json").readText()) }.getOrDefault(JSONArray())
        lock.withLock {
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                val entry = Item(item.getString("id"), item.getString("name"), item.getLong("size"), item.optInt("kind", Outbox.FILE),
                    item.optString("path").ifEmpty { null }, item.optLong("modified"))
                if (intact(entry)) items += entry
            }
            // Copies the index doesn't list (an interrupted share) go.
            folder.listFiles()?.filter { it.name != "index.json" && items.none { item -> item.path == null && item.id == it.name } }?.forEach { it.delete() }
            save()
        }
        publish()
    }

    /** The item's bytes are still what was shared: the copy is complete, or the original is unchanged. */
    private fun intact(item: Item): Boolean {
        val file = item.path?.let(::File) ?: return File(folder, item.id).length() == item.size
        return runCatching {
            !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isFile && file.canRead() &&
                file.length() == item.size && file.lastModified() == item.modified
        }.getOrDefault(false)
    }

    /** Adds shared files. Returns how many arrived; `progress` gets (done, total). Blocking: call off the main thread. */
    fun add(context: Context, uris: List<Uri>, progress: (Int, Int) -> Unit): Int {
        var added = 0
        var referenced = 0
        uris.forEachIndexed { index, uri ->
            progress(index, uris.size)
            runCatching {
                val (shown, declared) = describe(context, uri)
                val name = shown?.takeIf { runCatching { Wire.validateName(it) }.isSuccess } ?: "Shared file"
                val original = original(context, uri, shown, declared)
                val id = UUID.randomUUID().toString()
                val item = if (original != null) {
                    Item(id, name, original.length(), Outbox.FILE, original.path, original.lastModified()).also { referenced++ }
                } else {
                    val target = File(folder, id)
                    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } }
                        ?: throw IOException("Can't read the file")
                    Item(id, name, target.length())
                }
                lock.withLock {
                    items += item.copy(name = uniqueName(item.name))
                    save()
                    arrived.signalAll()   // wakes a computer waiting for files
                }
                added++
            }
        }
        progress(uris.size, uris.size)
        Log.add("Queued $added of ${uris.size} shared files ($referenced without copying)")
        publish()
        return added
    }

    /** Queues a text for the computer's clipboard. Returns false when it's empty or too long. */
    fun addText(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (text.isBlank() || bytes.size > Wire.MAX_CLIPBOARD) return false
        val id = UUID.randomUUID().toString()
        File(folder, id).writeBytes(bytes)
        lock.withLock {
            items += Item(id, "Text", bytes.size.toLong(), Outbox.TEXT)
            save()
            arrived.signalAll()
        }
        publish()
        return true
    }

    /** Drops everything waiting. Originals that were only referenced stay where they are. */
    fun clear() {
        lock.withLock {
            items.forEach { if (it.path == null) File(folder, it.id).delete() }
            items.clear()
            save()
        }
        Log.add("Cleared what was waiting to be sent")
        publish()
    }

    override fun waitFor(millis: Long, texts: Boolean): List<Outbox.Item> {
        val deadline = System.currentTimeMillis() + millis
        fun matching() = items.filter { texts || it.kind == Outbox.FILE }
        lock.withLock {
            prune()
            while (matching().isEmpty()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                arrived.await(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                prune()
            }
            return matching().map { Outbox.Item(it.id, it.name, it.size, it.kind) }
        }
    }

    /** Drops references whose original changed or went away (with the lock held). */
    private fun prune() {
        val stale = items.filter { it.path != null && !intact(it) }
        if (stale.isEmpty()) return
        items.removeAll(stale)
        save()
        Log.add("Dropped ${stale.size} shared files that changed or were removed")
        publish()
    }

    override fun open(id: String): Store.Read {
        val item = lock.withLock {
            val found = items.firstOrNull { it.id == id } ?: throw IOException("That file was already collected")
            if (!intact(found)) { prune(); throw IOException("That file changed or was removed on the phone") }
            found
        }
        val input = counting(item, (item.path?.let(::File) ?: File(folder, item.id)).inputStream())
        return object : Store.Read {
            override fun size() = item.size
            override fun stream() = input
            override fun close() = input.close()
        }
    }

    /** Reports how far the computer got (skipped bytes count: a resumed collection continues from there). */
    private fun counting(item: Item, input: InputStream): InputStream = object : FilterInputStream(input) {
        private var count = 0L
        private fun moved(bytes: Long) {
            if (bytes <= 0 || item.size <= 0) return
            count += bytes
            sending[item.id] = (count.toFloat() / item.size).coerceIn(0f, 1f)
            val now = SystemClock.uptimeMillis()
            if (now - progressShown > 150) { progressShown = now; showProgress() }
        }
        override fun read(): Int = super.read().also { if (it >= 0) moved(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { moved(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { moved(it) }
        override fun close() { super.close(); sending.remove(item.id); showProgress() }
    }

    private fun showProgress() {
        val now = HashMap(sending)
        main.post { progress = now }
    }

    override fun done(id: String) {
        val item = lock.withLock {
            val found = items.firstOrNull { it.id == id } ?: return   // withLock is inline: this returns from done()
            items.remove(found)
            if (found.path == null) File(folder, found.id).delete()
            save()
            found
        }
        Log.add("A computer collected a ${if (item.kind == Outbox.TEXT) "text" else "file"}")
        publish()
        onCollected(item)
    }

    /** The name and size the sharing app gives, when it does. */
    private fun describe(context: Context, uri: Uri): Pair<String?, Long?> {
        val row = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else (if (cursor.isNull(0)) null else cursor.getString(0)) to (if (cursor.isNull(1)) null else cursor.getLong(1))
            }
        }.getOrNull()
        return (row?.first ?: uri.lastPathSegment?.substringAfterLast('/')) to row?.second
    }

    /**
     * With All files access: the real file behind a shared item, from MediaStore's path column, or its volume, relative
     * path and name. Only a readable regular file in shared storage, with the name and size the sharing app reported.
     */
    private fun original(context: Context, uri: Uri, name: String?, size: Long?): File? {
        if (uri.scheme != "content" || !AllFiles.granted(context)) return null
        val candidate = runCatching { mediaFile(context, uri) }.getOrNull() ?: return null
        return runCatching {
            if (java.nio.file.Files.isSymbolicLink(candidate.toPath())) return null
            val file = candidate.canonicalFile
            val roots = AllFiles.directories(context).map { it.canonicalPath }
            val inside = roots.any { file.path.startsWith("$it/") }
            if (!inside || !file.isFile || !file.canRead()) return null
            if (name != null && file.name != name) return null
            if (size != null && file.length() != size) return null
            file
        }.getOrNull()
    }

    private fun mediaFile(context: Context, uri: Uri): File? {
        @Suppress("DEPRECATION") val data = MediaStore.MediaColumns.DATA
        runCatching {
            context.contentResolver.query(uri, arrayOf(data), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return File(cursor.getString(0))
            }
        }
        if (Build.VERSION.SDK_INT < 30 || uri.authority != MediaStore.AUTHORITY) return null
        val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || (0..2).any { cursor.isNull(it) }) return null
            val volume = context.getSystemService(android.os.storage.StorageManager::class.java)
                .getStorageVolume(MediaStore.Files.getContentUri(cursor.getString(0))).directory ?: return null
            return File(File(volume, cursor.getString(1)), cursor.getString(2))
        }
        return null
    }

    /** Two waiting files never share a name: "photo.jpg" then "photo 2.jpg". */
    private fun uniqueName(name: String): String {
        val taken = items.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return generateSequence(2) { it + 1 }.map { "${name.substring(0, dot)} $it${name.substring(dot)}" }.first { it.lowercase() !in taken }
    }

    private fun save() {
        val array = JSONArray()
        items.forEach {
            val entry = JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("kind", it.kind)
            if (it.path != null) entry.put("path", it.path).put("modified", it.modified)
            array.put(entry)
        }
        File(folder, "index.json").writeText(array.toString())
    }

    private fun publish() {
        val list = lock.withLock { items.toList() }
        main.post { waiting = list }
    }
}
