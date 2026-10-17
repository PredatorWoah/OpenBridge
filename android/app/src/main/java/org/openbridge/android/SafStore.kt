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

    private fun <T> storage(block: () -> T): T = try { block() }
    catch (e: SecurityException) { throw IOException("Storage permission denied; select the folder again") }
    catch (e: java.io.FileNotFoundException) { throw IOException("Document is unavailable") }
}
