package org.openbridge.desktop.wpd

import com.sun.jna.Memory
import org.openbridge.desktop.BridgeException
import org.openbridge.desktop.Entry
import org.openbridge.desktop.FolderSize
import org.openbridge.desktop.Partial
import org.openbridge.desktop.PhoneSource
import org.openbridge.desktop.S
import org.openbridge.desktop.SearchResult
import org.openbridge.protocol.Wire
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * One phone over USB through Windows Portable Devices. Entry IDs are WPD object IDs ("s10001" for a storage,
 * "o2A" for a file on Android); "" is the top, which lists the storages. Every WPD call runs on [ComThread].
 */
internal class WpdSource(private val phone: UsbPhone) : PhoneSource {
    /** The open device, made and used on the COM thread only. */
    private var connection: Connection? = null
    /** Previews that failed because the phone stopped answering; after two, previews stay off until reopened. */
    private var previewFailures = 0

    private class Connection(val device: Device, val content: Content, val properties: Properties, val resources: Resources) : AutoCloseable {
        override fun close() {
            resources.close(); properties.close(); content.close()
            runCatching { device.disconnect() }
            device.close()
        }
    }

    /** One child as WPD describes it, before filtering. */
    private class Item(val id: String, val name: String?, val directory: Boolean, val size: Long, val hidden: Boolean)

    // MARK: Connection

    /** Opens the phone now, so a locked phone says so at once. */
    fun connect(): Unit = guard { session { } }

    private fun open(): Connection {
        val device = Devices.open(phone.id)
        try {
            val content = device.content()
            try {
                val properties = content.properties()
                try {
                    return Connection(device, content, properties, content.transfer()).also { connection = it; previewFailures = 0 }
                } catch (e: Throwable) { properties.close(); throw e }
            } catch (e: Throwable) { content.close(); throw e }
        } catch (e: Throwable) { runCatching { device.disconnect() }; device.close(); throw e }
    }

    private fun drop() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    /**
     * Runs `work` on the COM thread with the open phone. When the phone went away (unplugged and plugged in again),
     * the connection is dropped and, if `retry`, reopened once and the work repeated: only for work that is safe
     * to repeat.
     */
    private fun <T> session(retry: Boolean = true, work: (Connection) -> T): T = ComThread.run {
        try { work(connection ?: open()) }
        catch (e: ComException) {
            if (!e.lost) throw e
            drop()
            if (!retry) throw e
            work(open())
        }
    }

    /** A call on an object from the current connection (a stream): never repeated, but a lost phone is dropped. */
    private fun <T> com(work: () -> T): T = ComThread.run {
        try { work() } catch (e: ComException) { if (e.lost) drop(); throw e }
    }

    /** Failures as messages to show: COM errors, local file errors, a missing WPD. Cancelling stays an InterruptedException. */
    private inline fun <T> guard(work: () -> T): T = try { work() }
        catch (e: BridgeException) { throw e }
        catch (e: ComException) { throw Errors.bridge(e.hr) }
        catch (e: IOException) { throw BridgeException(e.message ?: T.failed(Errors.E_FAIL)) }
        catch (e: LinkageError) { throw BridgeException(T.unavailable) }

    // MARK: Listing

    override fun list(id: String): List<Entry> = guard {
        if (id.isEmpty()) return@guard storages().map { it.entry }
        session { c -> children(c, id) }.mapNotNull { item ->
            if (item.hidden) return@mapNotNull null
            val name = item.name?.takeIf { valid(it) } ?: return@mapNotNull null
            Entry(item.id, name, item.directory, if (item.directory) 0 else item.size)
        }.sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }))
    }

    private class Storage(val entry: Entry, val capacity: Long, val free: Long)

    /** The phone's storages: functional objects of the storage category under the device object. */
    private fun storages(): List<Storage> {
        val found = session { c ->
            KeyCollection.of(Keys.OBJECT_CONTENT_TYPE, Keys.FUNCTIONAL_OBJECT_CATEGORY, Keys.OBJECT_NAME, Keys.STORAGE_DESCRIPTION,
                Keys.STORAGE_CAPACITY, Keys.STORAGE_FREE_SPACE_IN_BYTES).use { keys ->
                ids(c, Keys.DEVICE_OBJECT_ID).mapNotNull { id -> storage(c, keys, id) }
            }
        }
        // A locked Android phone shows up with no storages at all.
        if (found.isEmpty()) throw BridgeException(T.locked)
        return found
    }

    private fun storage(c: Connection, keys: KeyCollection, id: String): Storage? = values(c, id, keys)?.use { v ->
        if (v.guid(Keys.FUNCTIONAL_OBJECT_CATEGORY) != Keys.FUNCTIONAL_CATEGORY_STORAGE) return null
        val name = listOf(v.string(Keys.STORAGE_DESCRIPTION), v.string(Keys.OBJECT_NAME)).firstOrNull { !it.isNullOrBlank() && valid(it) } ?: T.storage
        val capacity = v.number(Keys.STORAGE_CAPACITY) ?: 0
        val free = v.number(Keys.STORAGE_FREE_SPACE_IN_BYTES) ?: 0
        val used = if (capacity > 0) (capacity - free).coerceAtLeast(0) else 0
        Storage(Entry(id, name, true, used), capacity, free)
    }

    /** A child's properties, or null when it vanished meanwhile; a phone that went away still fails. */
    private fun values(c: Connection, id: String, keys: KeyCollection): Values? =
        try { c.properties.values(id, keys) } catch (e: ComException) { if (e.lost) throw e; null }

    /** Object IDs directly inside `parent`, never `parent` itself and each once (Android's MTP lists both oddly). */
    private fun ids(c: Connection, parent: String): List<String> {
        val ids = LinkedHashSet<String>()
        c.content.children(parent).use { enumeration ->
            while (ids.size < MAX_CHILDREN) {
                val (batch, done) = enumeration.next(BATCH)
                batch.filterTo(ids) { it.isNotEmpty() && it != parent }
                if (done) break
            }
        }
        return ids.toList()
    }

    private fun children(c: Connection, parent: String): List<Item> =
        KeyCollection.of(Keys.OBJECT_CONTENT_TYPE, Keys.OBJECT_ORIGINAL_FILE_NAME, Keys.OBJECT_NAME, Keys.OBJECT_SIZE,
            Keys.OBJECT_ISHIDDEN, Keys.OBJECT_ISSYSTEM).use { keys ->
            ids(c, parent).mapNotNull { id ->
                values(c, id, keys)?.use { v ->
                    val type = v.guid(Keys.OBJECT_CONTENT_TYPE)
                    val name = v.string(Keys.OBJECT_ORIGINAL_FILE_NAME)?.takeIf { it.isNotEmpty() } ?: v.string(Keys.OBJECT_NAME)
                    Item(id, name, type == Keys.CONTENT_TYPE_FOLDER || type == Keys.CONTENT_TYPE_FUNCTIONAL_OBJECT,
                        (v.number(Keys.OBJECT_SIZE) ?: 0).coerceAtLeast(0),
                        (v.number(Keys.OBJECT_ISHIDDEN) ?: 0) != 0L || (v.number(Keys.OBJECT_ISSYSTEM) ?: 0) != 0L)
                }
            }
        }

    /** The content type and parent of one object, to tell storages apart and find where it sits. */
    private fun describe(id: String): Pair<Guid?, String?> = session { c ->
        KeyCollection.of(Keys.OBJECT_CONTENT_TYPE, Keys.OBJECT_PARENT_ID).use { keys ->
            c.properties.values(id, keys).use { v -> v.guid(Keys.OBJECT_CONTENT_TYPE) to v.string(Keys.OBJECT_PARENT_ID) }
        }
    }

    // MARK: Transfers

    override fun download(entry: Entry, destination: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Unit = guard {
        if (entry.directory || entry.id.isEmpty()) throw BridgeException(T.chooseFile)
        if (destination.exists()) throw BridgeException(T.existsHere(destination.name))
        // No resuming over WPD: the copy goes to a temporary file beside the destination, renamed when complete.
        val partial = Partial.location(destination, entry.size)
        try {
            partial.delete()
            var received = 0L
            FileOutputStream(partial).use { out ->
                var reported = 0L
                progress(0, entry.size)
                read(entry.id, Keys.RESOURCE_DEFAULT, cancelled) { bytes, count ->
                    out.write(bytes, 0, count)
                    received += count
                    if (received - reported >= PROGRESS_STEP) { reported = received; progress(received, maxOf(entry.size, received)) }
                }
                out.fd.sync()
            }
            if (entry.size > 0 && received != entry.size) throw BridgeException(T.short(received, entry.size))
            progress(received, maxOf(entry.size, received))
            // Never replaces: without REPLACE_EXISTING the move fails if the destination appeared meanwhile.
            Files.move(partial.toPath(), destination.toPath())
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    /**
     * Streams one resource of an object to `sink`, a chunk per COM call so other work can run in between. Stops
     * after `limit` bytes.
     */
    private fun read(id: String, key: PropertyKey, cancelled: () -> Boolean = { false }, limit: Long = Long.MAX_VALUE,
                     sink: (ByteArray, Int) -> Unit) {
        val (stream, optimal) = session { c -> c.resources.stream(id, key, Resources.STGM_READ) }
        try {
            val size = chunk(optimal)
            val buffer = Memory(size.toLong())
            val bytes = ByteArray(size)
            var total = 0L
            while (total < limit) {
                if (cancelled()) throw InterruptedException()
                val count = com { stream.read(buffer, size) }
                if (count <= 0) break
                buffer.read(0, bytes, 0, count)
                sink(bytes, count)
                total += count
            }
        } finally { ComThread.run { stream.close() } }
    }

    override fun upload(source: File, directoryId: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Unit = guard {
        val name = source.name
        checkName(name)
        if (directoryId.isEmpty() || directoryId == Keys.DEVICE_OBJECT_ID) throw BridgeException(T.openStorage)
        if (!source.isFile) throw BridgeException(T.regularFile)
        val size = source.length()
        // Phones' shared storage ignores letter case, so compare names that way. Never replace a file.
        if (taken(directoryId, name)) throw BridgeException(T.exists(name))

        val (stream, optimal) = session(retry = false) { c ->
            Values.create().use { v ->
                v.setString(Keys.OBJECT_PARENT_ID, directoryId)
                v.setUnsignedLarge(Keys.OBJECT_SIZE, size)
                v.setString(Keys.OBJECT_ORIGINAL_FILE_NAME, name)
                v.setString(Keys.OBJECT_NAME, name)
                v.setGuid(Keys.OBJECT_CONTENT_TYPE, Keys.CONTENT_TYPE_GENERIC_FILE)
                v.setGuid(Keys.OBJECT_FORMAT, Keys.OBJECT_FORMAT_UNSPECIFIED)
                c.content.createWithData(v)
            }
        }
        try {
            source.inputStream().use { input ->
                val chunk = chunk(optimal)
                val bytes = ByteArray(chunk)
                val buffer = Memory(chunk.toLong())
                var sent = 0L; var reported = 0L
                progress(0, size)
                while (sent < size) {
                    if (cancelled()) throw InterruptedException()
                    val read = input.read(bytes, 0, minOf(chunk.toLong(), size - sent).toInt())
                    if (read < 0) throw BridgeException(T.changed(name))
                    buffer.write(0, bytes, 0, read)
                    com { stream.write(buffer, read) }
                    sent += read
                    if (sent - reported >= PROGRESS_STEP || sent == size) { reported = sent; progress(sent, size) }
                }
            }
            // The object only exists once committed.
            com { stream.commit() }
        } catch (e: Throwable) {
            ComThread.run { runCatching { stream.revert() } }
            if (!removeLeftover(directoryId, name)) {
                if (e is InterruptedException) throw BridgeException(T.partialLeft(name))
                val reason = when (e) { is ComException -> Errors.message(e.hr); else -> e.message ?: "" }
                throw BridgeException("$reason ${T.partialLeft(name)}".trim(), lost = e is ComException && e.lost)
            }
            throw e
        } finally { ComThread.run { stream.close() } }
    }

    /**
     * After a failed or cancelled upload: Revert should have left nothing, but a phone may keep what arrived. The
     * name was free before, so anything by that name now is the broken copy. False when that can't be checked.
     */
    private fun removeLeftover(directoryId: String, name: String): Boolean = runCatching {
        session { c ->
            children(c, directoryId).filter { it.name.equals(name, ignoreCase = true) }.forEach { item ->
                PropVariantCollection.of(item.id).use { ids -> check(c.content.delete(Keys.DELETE_NO_RECURSION, ids), "Delete") }
            }
        }
    }.isSuccess

    // MARK: Changes

    override fun makeFolder(name: String, directoryId: String): Entry = guard {
        checkName(name)
        if (directoryId.isEmpty() || directoryId == Keys.DEVICE_OBJECT_ID) throw BridgeException(T.openStorage)
        if (taken(directoryId, name)) throw BridgeException(T.exists(name))
        val id = session(retry = false) { c ->
            Values.create().use { v ->
                v.setString(Keys.OBJECT_PARENT_ID, directoryId)
                v.setString(Keys.OBJECT_ORIGINAL_FILE_NAME, name)
                v.setString(Keys.OBJECT_NAME, name)
                v.setGuid(Keys.OBJECT_CONTENT_TYPE, Keys.CONTENT_TYPE_FOLDER)
                c.content.createWithPropertiesOnly(v)
            }
        }
        Entry(id, name, true, 0)
    }

    override fun delete(entry: Entry): Unit = guard {
        if (entry.id.isEmpty() || entry.id == Keys.DEVICE_OBJECT_ID) throw BridgeException(T.storageFixed)
        if (describe(entry.id).first == Keys.CONTENT_TYPE_FUNCTIONAL_OBJECT) throw BridgeException(T.storageFixed)
        session(retry = false) { c ->
            PropVariantCollection.of(entry.id).use { ids ->
                val hr = c.content.delete(Keys.DELETE_WITH_RECURSION, ids)
                // Android deletes a folder with everything in it even when asked not to recurse.
                if (Errors.noRecursion(hr)) check(c.content.delete(Keys.DELETE_NO_RECURSION, ids), "Delete")
                else check(hr, "Delete")
            }
        }
    }

    override fun rename(entry: Entry, name: String): Entry = guard {
        checkName(name)
        if (entry.id.isEmpty() || entry.id == Keys.DEVICE_OBJECT_ID) throw BridgeException(T.storageFixed)
        if (name == entry.name) return@guard entry
        val (type, parent) = describe(entry.id)
        if (type == Keys.CONTENT_TYPE_FUNCTIONAL_OBJECT || parent.isNullOrEmpty()) throw BridgeException(T.storageFixed)
        // Never take a name that exists; a change of letter case of its own name is fine.
        if (list(parent).any { it.id != entry.id && it.name.equals(name, ignoreCase = true) }) throw BridgeException(T.exists(name))
        session(retry = false) { c ->
            // The file name is what Android renames; a device that won't take it may take the object name instead.
            val first = set(c, entry.id, Keys.OBJECT_ORIGINAL_FILE_NAME, name)
            if (first < 0 && set(c, entry.id, Keys.OBJECT_NAME, name) < 0) throw ComException(first, "SetValues")
        }
        entry.copy(name = name)
    }

    /** Sets one property and returns its own result from the per-property results. */
    private fun set(c: Connection, id: String, key: PropertyKey, value: String): Int = Values.create().use { v ->
        v.setString(key, value)
        val (hr, results) = c.properties.setValues(id, v)
        results.use { it?.error(key) } ?: if (hr == 0) 0 else Errors.E_FAIL
    }

    // MARK: Previews, sizes, search

    override fun thumbnail(entry: Entry, size: Int): ByteArray = guard {
        if (entry.directory || entry.id.isEmpty()) throw BridgeException(T.noPreview)
        val target = size.coerceIn(32, 1024)
        if (ComThread.run { previewFailures } >= 2) throw BridgeException(T.noPreview)
        val own = try { readAll(entry.id, Keys.RESOURCE_THUMBNAIL, Wire.MAX_THUMBNAIL.toLong()) }
        catch (e: ComException) {
            if (e.lost) { ComThread.run { previewFailures++ }; throw e }
            null
        }
        own?.takeIf { it.isNotEmpty() }?.let { bytes -> Previews.fit(bytes, target)?.let { return@guard it } }
        // No preview from the phone: make one from the photo itself when it's small enough to fetch.
        if (!Previews.decodable(entry.name) || entry.size !in 1..Previews.MAX_SOURCE) throw BridgeException(T.noPreview)
        Previews.scale(readAll(entry.id, Keys.RESOURCE_DEFAULT, Previews.MAX_SOURCE), target) ?: throw BridgeException(T.noPreview)
    }

    private fun readAll(id: String, key: PropertyKey, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        read(id, key, limit = limit + 1) { bytes, count -> out.write(bytes, 0, count) }
        if (out.size() > limit) throw BridgeException(T.noPreview)
        return out.toByteArray()
    }

    override fun measure(folder: Entry): FolderSize = guard {
        if (folder.id.isEmpty()) throw BridgeException(T.chooseFolder)
        // A whole storage: its used space, straight from the phone.
        storages().firstOrNull { it.entry.id == folder.id }?.let { return@guard FolderSize(it.entry.size, 0, true) }
        Walk.measure(folder.id, ::list)
    }

    override fun search(folderId: String, query: String): SearchResult = guard {
        val text = query.trim()
        if (text.isEmpty() || text.toByteArray().size > 255) throw BridgeException(S.typeToSearch)
        val starts = if (folderId.isEmpty()) storages().map { it.entry.id to it.entry.name } else listOf(folderId to "")
        Walk.search(starts, text, ::list)
    }

    // MARK: Helpers

    private fun taken(directoryId: String, name: String) = list(directoryId).any { it.name.equals(name, ignoreCase = true) }

    private fun checkName(name: String) {
        if (!valid(name)) throw BridgeException(T.invalidName)
        if (name.length >= 255) throw BridgeException(T.tooLong)
    }

    companion object {
        private const val BATCH = 256
        private const val MAX_CHILDREN = 200_000
        private const val PROGRESS_STEP = 1L shl 20

        fun valid(name: String) = runCatching { Wire.validateName(name) }.isSuccess

        /** The driver's preferred transfer size, kept between 64 KiB and 4 MiB (256 KiB when it has none). */
        fun chunk(optimal: Int) = if (optimal <= 0) 256 shl 10 else optimal.coerceIn(64 shl 10, 4 shl 20)
    }
}
