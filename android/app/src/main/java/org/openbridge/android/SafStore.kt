package org.openbridge.android

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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

    @Synchronized
    private fun document(id: String, directory: Boolean): DocumentFile {
        val item = documents[id] ?: throw IOException("Unknown document; refresh the folder")
        if (!item.exists() || item.isDirectory != directory) throw IOException("Document is unavailable")
        return item
    }

    @Synchronized
    override fun list(directoryId: String): List<Store.Entry> = storage {
        val children = document(directoryId, true).listFiles().filter { it.uri !in temporary }
        if (children.size > Wire.MAX_ENTRIES) throw IOException("Folder has too many entries")
        children.map { child ->
            val id = ids[child.uri] ?: run {
                if (documents.size >= 50_000) throw IOException("Restart sharing to refresh document IDs")
                UUID.randomUUID().toString().also {
                    ids[child.uri] = it
                    documents[it] = child
                }
            }
            val name = child.name ?: throw IOException("Document has no name")
            Wire.validateName(name)
            Store.Entry(id, name, child.isDirectory, if (child.isDirectory) 0 else child.length())
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
        Wire.validateName(name)
        val parent = document(directoryId, true)
        val reservation = parent.uri to name
        if (reservation in reserved || parent.findFile(name) != null) throw IOException("Filename already exists")
        if (!parent.canWrite()) throw IOException("Folder is read-only")
        val part = parent.createFile("application/octet-stream", ".openbridge-part-${UUID.randomUUID()}")
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
                            if (parent.findFile(name) != null) throw IOException("Filename already exists")
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

    private fun <T> storage(block: () -> T): T = try { block() }
    catch (e: SecurityException) { throw IOException("Storage permission denied; select the folder again") }
    catch (e: java.io.FileNotFoundException) { throw IOException("Document is unavailable") }
}
