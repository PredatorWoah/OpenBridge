package org.openbridge.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import org.openbridge.protocol.FileStore
import java.io.File

/** "Share whole phone": All files access on Android 11 and later, the storage permission before that. */
object AllFiles {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Internal storage and any mounted SD cards. */
    fun roots(context: Context): List<FileStore.Root> {
        val roots = mutableListOf<FileStore.Root>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (volume in context.getSystemService(StorageManager::class.java).storageVolumes) {
                val directory = volume.directory ?: continue
                if (volume.state != Environment.MEDIA_MOUNTED) continue
                roots += FileStore.Root(if (volume.isPrimary) "Internal storage" else volume.getDescription(context), directory)
            }
        } else {
            @Suppress("DEPRECATION")
            roots += FileStore.Root("Internal storage", Environment.getExternalStorageDirectory())
            // Before Android 11, an SD card's root is the part of its app folder before /Android/data.
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appFolder ->
                val root = appFolder.path.substringBefore("/Android/data", missingDelimiterValue = "")
                if (root.isNotEmpty()) roots += FileStore.Root("SD card", File(root))
            }
        }
        return roots
    }
}
