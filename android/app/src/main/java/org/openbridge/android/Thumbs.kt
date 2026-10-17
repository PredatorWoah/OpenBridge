package org.openbridge.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Small JPEG previews of photos and videos for the Mac's file list. */
object Thumbs {
    private val images = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")
    private val videos = setOf("mp4", "mov", "mkv", "3gp", "webm", "m4v", "avi")

    private fun previewable(name: String) = name.substringAfterLast('.', "").lowercase().let { it in images || it in videos }

    /** Whole-phone mode: files on disk. */
    fun ofFile(file: File, size: Int): ByteArray {
        val ext = file.extension.lowercase()
        val bitmap = try {
            when {
                ext in videos -> if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(file, Size(size, size), null)
                    else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(file.path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
                ext in images -> if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createImageThumbnail(file, Size(size, size), null)
                    else sampled(file, size)
                else -> null
            }
        } catch (e: Exception) { null }
        return jpeg(bitmap ?: throw IOException("No preview"))
    }

    /** One-folder mode: the storage provider makes the preview. */
    fun ofDocument(resolver: ContentResolver, uri: Uri, name: String, size: Int): ByteArray {
        if (!previewable(name)) throw IOException("No preview")
        val bitmap = try { DocumentsContract.getDocumentThumbnail(resolver, uri, Point(size, size), null) } catch (e: Exception) { null }
        return jpeg(bitmap ?: throw IOException("No preview"))
    }

    private fun sampled(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        out.toByteArray()
    }
}
