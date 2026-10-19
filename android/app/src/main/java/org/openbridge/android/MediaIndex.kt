package org.openbridge.android

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import org.openbridge.protocol.FileStore
import java.io.File

/**
 * Android's media index (MediaStore) as the whole phone's search index: it knows most files by name at once, while
 * walking a whole phone takes seconds. FileStore checks every hit on disk and still walks for files it doesn't know.
 */
class MediaIndex(private val context: Context) : FileStore.Index {
    override fun find(query: String, folder: File?, limit: Int): List<File> {
        @Suppress("DEPRECATION") val data = MediaStore.MediaColumns.DATA
        // Before Android 10 many rows have no display name; the path ends with it, and FileStore rechecks the name.
        val name = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.DISPLAY_NAME else data
        val selection = StringBuilder("$name LIKE ? ESCAPE '\\'")
        val arguments = mutableListOf("%${like(query)}%")
        if (folder != null) { selection.append(" AND $data LIKE ? ESCAPE '\\'"); arguments += "${like(folder.path.trimEnd('/'))}/%" }
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context) else setOf("external")
        val found = mutableListOf<File>()
        for (volume in volumes) {
            if (found.size >= limit) break
            context.contentResolver.query(MediaStore.Files.getContentUri(volume), arrayOf(data), selection.toString(),
                arguments.toTypedArray(), null)?.use { cursor ->
                while (found.size < limit && cursor.moveToNext()) cursor.getString(0)?.let { found += File(it) }
            }
        }
        return found
    }

    /** The text as a literal inside LIKE: its wildcards and the escape character escaped. */
    private fun like(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
