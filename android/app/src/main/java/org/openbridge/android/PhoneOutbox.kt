package org.openbridge.android

import android.content.Context
import android.net.Uri
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
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * Files and texts sent from the phone with Share › OpenBridge, copied into app-private storage until a paired computer
 * collects them. The copies are deleted once the computer has them, or when the user clears the list.
 */
object PhoneOutbox : Outbox {
    data class Item(val id: String, val name: String, val size: Long, val kind: Int = Outbox.FILE)

    private lateinit var folder: File
    private val items = mutableListOf<Item>()
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val arrived = lock.newCondition()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** For the screen: files waiting for a computer. */
    var waiting by mutableStateOf(emptyList<Item>()); private set
    /** Told when a computer collected a file (on a server thread). */
    @Volatile var onCollected: (String) -> Unit = {}

    fun attach(context: Context) {
        if (::folder.isInitialized) return
        folder = File(context.filesDir, "outbox").apply { mkdirs() }
        val saved = runCatching { JSONArray(File(folder, "index.json").readText()) }.getOrDefault(JSONArray())
        lock.withLock {
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                val entry = Item(item.getString("id"), item.getString("name"), item.getLong("size"), item.optInt("kind", Outbox.FILE))
                if (File(folder, entry.id).length() == entry.size) items += entry
            }
            // Copies the index doesn't list (an interrupted share) go.
            folder.listFiles()?.filter { it.name != "index.json" && items.none { item -> item.id == it.name } }?.forEach { it.delete() }
        }
        publish()
    }

    /** Copies shared files in. Returns how many arrived; `progress` gets (done, total). Blocking: call off the main thread. */
    fun add(context: Context, uris: List<Uri>, progress: (Int, Int) -> Unit): Int {
        var added = 0
        uris.forEachIndexed { index, uri ->
            progress(index, uris.size)
            runCatching {
                val name = displayName(context, uri)
                val id = UUID.randomUUID().toString()
                val target = File(folder, id)
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } }
                    ?: throw IOException("Can't read the file")
                lock.withLock {
                    items += Item(id, uniqueName(name), target.length())
                    save()
                    arrived.signalAll()   // wakes a computer waiting for files
                }
                added++
            }
        }
        progress(uris.size, uris.size)
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

    fun clear() {
        lock.withLock {
            items.forEach { File(folder, it.id).delete() }
            items.clear()
            save()
        }
        publish()
    }

    override fun waitFor(millis: Long, texts: Boolean): List<Outbox.Item> {
        val deadline = System.currentTimeMillis() + millis
        fun matching() = items.filter { texts || it.kind == Outbox.FILE }
        lock.withLock {
            while (matching().isEmpty()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                arrived.await(left, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            return matching().map { Outbox.Item(it.id, it.name, it.size, it.kind) }
        }
    }

    override fun open(id: String): Store.Read {
        val item = lock.withLock { items.firstOrNull { it.id == id } } ?: throw IOException("That file was already collected")
        val input = File(folder, item.id).inputStream()
        return object : Store.Read {
            override fun size() = item.size
            override fun stream() = input
            override fun close() = input.close()
        }
    }

    override fun done(id: String) {
        val item = lock.withLock {
            val found = items.firstOrNull { it.id == id } ?: return   // withLock is inline: this returns from done()
            items.remove(found)
            File(folder, found.id).delete()
            save()
            found
        }
        publish()
        onCollected(item.name)
    }

    /** A name the computer can save: the shared file's own when it's valid, otherwise a plain one. */
    private fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        return name?.takeIf { runCatching { Wire.validateName(it) }.isSuccess } ?: "Shared file"
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
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("kind", it.kind)) }
        File(folder, "index.json").writeText(array.toString())
    }

    private fun publish() {
        val list = lock.withLock { items.toList() }
        main.post { waiting = list }
    }
}
