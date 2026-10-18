package org.openbridge.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import org.openbridge.protocol.Names
import org.openbridge.protocol.Store
import org.openbridge.protocol.Wire
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Only the built-in local storage provider and descendants of a user-granted tree. */
class SafStore(private val resolver: ContentResolver, private val root: DocumentFile) : Store {
    private val documents = mutableMapOf("" to root)
    private val ids = mutableMapOf(root.uri to "")
    private val reserved = mutableSetOf<Pair<Uri, String>>()
    private val temporary = mutableSetOf<Uri>()

    private class Metadata(val name: String?, val directory: Boolean, val size: Long)

    /** Names, types and sizes of all children in one provider query, instead of several queries per child. */
    private fun children(parent: DocumentFile): Map<Uri, Metadata> {
        val tree = root.uri
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        return resolver.query(query, columns, null, null, null)?.use { cursor ->
            val result = HashMap<Uri, Metadata>(cursor.count)
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                result[DocumentsContract.buildDocumentUriUsingTree(tree, id)] = Metadata(cursor.getString(1),
                    cursor.getString(2) == Document.MIME_TYPE_DIR, if (cursor.isNull(3)) 0 else cursor.getLong(3))
            }
            result
        } ?: throw IOException("Cannot read folder")
    }

    private fun exists(parent: DocumentFile, name: String): Boolean {
        val key = Names.collisionKey(name)
        return children(parent).values.any { it.name != null && Names.collisionKey(it.name) == key }
    }

    @Synchronized
    private fun document(id: String, directory: Boolean): DocumentFile {
        val item = documents[id] ?: throw IOException("Unknown document; refresh the folder")
        if (!item.exists() || item.isDirectory != directory) throw IOException("Document is unavailable")
        return item
    }

    @Synchronized
    override fun list(directoryId: String): List<Store.Entry> = storage {
        val parent = document(directoryId, true)
        val metadata = children(parent)
        val listed = parent.listFiles().mapNotNull { child ->
            val info = metadata[child.uri] ?: return@mapNotNull null
            val name = info.name ?: return@mapNotNull null
            // Skip in-progress or orphaned uploads and names the protocol cannot carry, rather than failing the folder.
            if (child.uri in temporary || Names.isTemporary(name)) return@mapNotNull null
            if (runCatching { Wire.validateName(name) }.isFailure) return@mapNotNull null
            child to info
        }
        if (listed.size > Wire.MAX_ENTRIES) throw IOException("Folder has too many entries")
        listed.map { (child, info) ->
            val id = ids[child.uri] ?: run {
                if (documents.size >= 50_000) throw IOException("Restart sharing to refresh document IDs")
                UUID.randomUUID().toString().also {
                    ids[child.uri] = it
                    documents[it] = child
                }
            }
            Store.Entry(id, info.name!!, info.directory, if (info.directory) 0 else info.size)
        }.sortedWith(compareBy<Store.Entry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    @Synchronized
    override fun open(fileId: String): Store.Read = storage {
        val file = document(fileId, false)
        val descriptor = resolver.openAssetFileDescriptor(file.uri, "r")
            ?: throw IOException("Cannot open document")
        try {
            val length = descriptor.length
            if (length < 0 || length > Wire.MAX_FILE) throw IOException("Unsupported file size")
            val input = descriptor.createInputStream()
            object : Store.Read {
                override fun size() = length
                override fun stream(): InputStream = input
                override fun close() { input.close() }
            }
        } catch (e: Exception) { descriptor.close(); throw e }
    }

    @Synchronized
    override fun create(directoryId: String, name: String): Store.Upload = storage {
        Names.requireStorable(name)
        val parent = document(directoryId, true)
        val reservation = parent.uri to Names.collisionKey(name)
        if (reservation in reserved || exists(parent, name)) throw IOException("Filename already exists")
        if (!parent.canWrite()) throw IOException("Folder is read-only")
        val part = parent.createFile("application/octet-stream", "${Names.TEMPORARY_PREFIX}${UUID.randomUUID()}")
            ?: throw IOException("Cannot create upload")
        val temporaryUri = part.uri
        reserved.add(reservation)
        temporary.add(part.uri)
        try {
            val output = resolver.openOutputStream(part.uri, "w") ?: throw IOException("Cannot open upload")
            object : Store.Upload {
                var committed = false
                override fun stream(): OutputStream = output
                override fun commit() {
                    synchronized(this@SafStore) {
                        storage {
                            if (exists(parent, name)) throw IOException("Filename already exists")
                            if (!part.renameTo(name) || part.name != name) throw IOException("Provider could not publish filename")
                            temporary.remove(temporaryUri)
                            committed = true
                        }
                    }
                }
                override fun close() {
                    synchronized(this@SafStore) {
                        try { output.close() } finally {
                            if (!committed) part.delete()
                            temporary.remove(temporaryUri)
                            reserved.remove(reservation)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete(); temporary.remove(temporaryUri); reserved.remove(reservation)
            throw e
        }
    }

    /** Like [create], keeping an interrupted upload (under a hidden name derived from the file) so it can continue. */
    @Synchronized
    override fun resume(directoryId: String, name: String, size: Long, key: ByteArray): Store.Upload = storage {
        Names.requireStorable(name)
        val parent = document(directoryId, true)
        val reservation = parent.uri to Names.collisionKey(name)
        if (reservation in reserved || exists(parent, name)) throw IOException("Filename already exists")
        if (!parent.canWrite()) throw IOException("Folder is read-only")
        val partName = Names.resumable(name, size, key)
        val cutoff = System.currentTimeMillis() - Store.ABANDONED_MILLIS
        val kept = parent.listFiles().filter { it.name?.startsWith(Names.RESUMABLE_PREFIX) == true }
        // Interrupted uploads nobody continued for a week go.
        kept.filter { it.name != partName && it.lastModified() in 1 until cutoff }.forEach { it.delete() }
        var found = kept.firstOrNull { it.name == partName && it.isFile }
        var have = found?.length() ?: 0L
        if (found != null && have > size) { found.delete(); found = null; have = 0 }
        val part = found ?: parent.createFile("application/octet-stream", partName) ?: throw IOException("Cannot create upload")
        if (part.name != partName) { part.delete(); throw IOException("Cannot create upload") }
        val partUri = part.uri
        reserved.add(reservation)
        temporary.add(partUri)
        val existing = have
        try {
            // "wa": append, so the upload continues after the bytes already kept.
            val output = resolver.openOutputStream(partUri, if (existing > 0) "wa" else "w") ?: throw IOException("Cannot open upload")
            object : Store.Upload {
                var committed = false
                override fun stream(): OutputStream = output
                override fun existing() = existing
                override fun commit() {
                    synchronized(this@SafStore) {
                        storage {
                            if (exists(parent, name)) throw IOException("Filename already exists")
                            if (!part.renameTo(name) || part.name != name) throw IOException("Provider could not publish filename")
                            temporary.remove(partUri)
                            committed = true
                        }
                    }
                }
                override fun close() {
                    synchronized(this@SafStore) {
                        try { output.close() } finally {
                            // Not committed: keep what arrived, for the next attempt.
                            temporary.remove(partUri)
                            reserved.remove(reservation)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            temporary.remove(partUri); reserved.remove(reservation)
            throw e
        }
    }

    @Synchronized
    override fun createDirectory(parentId: String, name: String): String = storage {
        Names.requireStorable(name)
        val parent = document(parentId, true)
        if (reserved.any { it.first == parent.uri && it.second == Names.collisionKey(name) } || exists(parent, name))
            throw IOException("A file or folder with that name already exists")
        if (!parent.canWrite()) throw IOException("Folder is read-only")
        val made = parent.createDirectory(name) ?: throw IOException("Cannot create the folder")
        if (made.name != name) { made.delete(); throw IOException("Provider could not create that folder name") }
        if (documents.size >= 50_000) throw IOException("Restart sharing to refresh document IDs")
        UUID.randomUUID().toString().also { ids[made.uri] = it; documents[it] = made }
    }

    // Not synchronized: decoding a preview can take a while and must not hold up other requests.
    override fun thumbnail(fileId: String, size: Int): ByteArray = storage {
        val file = synchronized(this) { document(fileId, false) }
        Thumbs.ofDocument(resolver, file.uri, file.name ?: "", size)
    }

    @Synchronized
    override fun delete(id: String) = storage {
        if (id.isEmpty()) throw IOException("The shared folder itself can't be deleted")
        val item = documents[id] ?: throw IOException("Unknown document; refresh the folder")
        if (!item.exists()) throw IOException("Document is unavailable")
        // The storage provider deletes a folder with everything in it.
        if (!DocumentsContract.deleteDocument(resolver, item.uri)) throw IOException("Couldn't delete that")
        forget(item.uri)
    }

    @Synchronized
    override fun rename(id: String, name: String): String = storage {
        Names.requireStorable(name)
        if (id.isEmpty()) throw IOException("The shared folder itself can't be renamed")
        val item = documents[id] ?: throw IOException("Unknown document; refresh the folder")
        val current = item.name ?: throw IOException("Document is unavailable")
        if (current == name) return@storage id
        val parent = item.parentFile ?: throw IOException("Document is unavailable")
        val caseOnly = Names.collisionKey(current) == Names.collisionKey(name)
        if (!caseOnly && (reserved.any { it.first == parent.uri && it.second == Names.collisionKey(name) } || exists(parent, name)))
            throw IOException("A file or folder with that name already exists")
        val old = item.uri
        if (!item.renameTo(name) || item.name != name) throw IOException("Couldn't rename that")
        forget(old)
        ids[item.uri] = id
        documents[id] = item
        id
    }

    /** Counts with direct provider queries by document ID, so walking a big folder hands out no IDs. */
    override fun measure(directoryId: String, deadline: Long): Store.Size = storage {
        val start = synchronized(this) { DocumentsContract.getDocumentId(document(directoryId, true).uri) }
        val tree = root.uri
        val pending = ArrayDeque<String>().apply { add(start) }
        var bytes = 0L; var files = 0L; var seen = 0
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME)
        while (pending.isNotEmpty()) {
            val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            resolver.query(query, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (++seen > Store.MEASURE_LIMIT || System.currentTimeMillis() > deadline) return@storage Store.Size(bytes, files, false)
                    val child = cursor.getString(0) ?: continue
                    if (cursor.getString(1) == Document.MIME_TYPE_DIR) pending.add(child)
                    else if (!Names.isTemporary(cursor.getString(3) ?: "")) { bytes += if (cursor.isNull(2)) 0 else cursor.getLong(2); files++ }
                }
            }
        }
        Store.Size(bytes, files, true)
    }

    /** Drops IDs for a document and everything below it, after it was deleted or renamed. */
    private fun forget(uri: Uri) {
        val gone = DocumentsContract.getDocumentId(uri)
        val stale = ids.keys.filter { known ->
            val document = runCatching { DocumentsContract.getDocumentId(known) }.getOrNull() ?: return@filter false
            known != root.uri && (document == gone || document.startsWith("$gone/"))
        }
        for (known in stale) ids.remove(known)?.let { documents.remove(it) }
    }

    private fun <T> storage(block: () -> T): T = try { block() }
    catch (e: SecurityException) { throw IOException("Storage permission denied; select the folder again") }
    catch (e: java.io.FileNotFoundException) { throw IOException("Document is unavailable") }
}
