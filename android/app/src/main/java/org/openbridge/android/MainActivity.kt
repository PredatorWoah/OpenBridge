package org.openbridge.android

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openbridge.ui.Look
import java.io.File

/** Hosts the app's screens and does what they ask: permissions, pickers, other apps, the clipboard. */
class MainActivity : ComponentActivity(), Actions {
    private val model = Sharing
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) model.select(uri) else model.folderCancelled()
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.wholePhoneResult() }
    private val nearby = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Build.VERSION.SDK_INT >= 33) Hotspot.start(this) else Hotspot.denied()
    }
    /** Send files: the system picker, then the same path as Share › OpenBridge. */
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) queue(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every look is dark: light status and navigation bar icons over a transparent bar.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        Sharing.attach(this)
        Updater.attach(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val sharing = model.server != null
            // Without background sharing, leaving the app stops sharing, so stop the screen timeout from ending transfers.
            LaunchedEffect(sharing) {
                if (sharing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // One quiet check per launch for a newer release (see Updates).
            LaunchedEffect(Unit) { Updater.check() }
            CompositionLocalProvider(LocalMotion provides systemMotion()) {
                OpenBridgeTheme { App(LiveModel, this) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A computer asked over USB debugging to share over the cable (see Sharing.SHARE_USB); handled once. */
    private fun handle(intent: Intent?) {
        if (intent?.action != Sharing.SHARE_USB) return
        intent.action = Intent.ACTION_MAIN
        model.shareOverCable()
    }

    override fun onResume() {
        super.onResume()
        model.onScreen = true
        model.wholePhoneResult()
        Mirror.refreshAccess()
        Updater.resumed(this)
        LiveModel.resume()
    }

    override fun onPause() {
        model.onScreen = false
        LiveModel.pause()
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            // Without background sharing, leaving the app stops sharing, as before; a hotspot nobody shares on goes too.
            if (!model.inBackground) model.stop()
            if (model.server == null) Hotspot.stop()
        }
        super.onStop()
    }

    override fun toggleSharing() {
        if (model.server != null || model.busy) { model.stop(); return }
        // The background notification (Stop button, pairing requests) needs permission on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 && model.inBackground && !granted(Manifest.permission.POST_NOTIFICATIONS))
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        when {
            model.wholePhone && !AllFiles.granted(this) -> requestWholePhone(true)
            model.wholePhone -> model.start()
            model.folder == null -> { model.startAfterFolderPicked(); chooseFolder() }
            else -> model.start()
        }
    }

    override fun chooseWholePhone() {
        model.chooseWholePhone()
        if (!AllFiles.granted(this)) requestWholePhone(false)
    }

    override fun chooseFolder() {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        })
    }

    private fun requestWholePhone(thenStart: Boolean) {
        model.awaitWholePhone(thenStart)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: the user turns on "Allow access to manage all files" for this app.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun keepInBackground(on: Boolean) = model.keepInBackground(on)

    override fun hotspot(on: Boolean) {
        when {
            !on -> Hotspot.stop()
            Build.VERSION.SDK_INT < 33 -> Hotspot.openSystemSettings(this)
            granted(Manifest.permission.NEARBY_WIFI_DEVICES) -> Hotspot.start(this)
            else -> nearby.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    override fun hotspotSettings() = Hotspot.openSystemSettings(this)

    override fun update() = Updater.update(this)

    override fun checkForUpdates() = Updater.check(manual = true)

    override fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, S.cantOpen, Toast.LENGTH_SHORT).show() }
    }

    override fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("OpenBridge", text)
        // Android 13+ hides sensitive clips in its copy preview, and shows its own "Copied" notice.
        if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, S.copied, Toast.LENGTH_SHORT).show()
    }

    /** The app is in front, so it can read the clipboard directly. */
    override fun sendClipboard() {
        val text = PhoneClipboard.text(this)
        val message = when {
            text.isBlank() -> S.clipboardEmpty
            !model.sendText(text) -> S.textTooLong
            model.paired -> S.sentTo(model.computer)
            else -> S.waitingForComputer
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun sendText(text: String): Boolean = model.sendText(text)

    override fun sendFiles() {
        runCatching { files.launch(arrayOf("*/*")) }.onFailure { Toast.makeText(this, S.cantOpen, Toast.LENGTH_SHORT).show() }
    }

    /** Copies the picked files into the outbox off the main thread, then hands over as Share › OpenBridge does. */
    private fun queue(uris: List<Uri>) {
        Thread {
            val added = PhoneOutbox.add(this, uris) { _, _ -> }
            runOnUiThread {
                if (added == 0) { Toast.makeText(this, S.couldNotRead, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                Sharing.handOver { sharing ->
                    if (sharing) Toast.makeText(this, S.queued(added, if (Sharing.paired) Sharing.computer else null), Toast.LENGTH_SHORT).show()
                    else Sharing.waitingNote(S.tapToSend(added))
                }
            }
        }.start()
    }

    override fun clearOutbox() = PhoneOutbox.clear()
    override fun clearTexts() = Texts.clear()
    override fun copyDiagnostics() = copy(Log.report(this))

    override fun notificationAccess() {
        runCatching { startActivity(Mirror.accessSettings()) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
    }

    override fun mirror(on: Boolean) = Mirror.enable(on)
    override fun showApp(packageName: String, shown: Boolean) = Mirror.include(packageName, shown)
    override fun forget(id: String) = Trusted.forget(id)
    override fun decidePairing(approve: Boolean) = model.decidePairing(approve)
    override fun dismissNote() = model.dismissNote()
    override fun chooseLook(look: Look) = model.chooseLook(look)
    override fun removeEvent(id: Long) = Feed.remove(id)
    override fun clearEvents() = Feed.clear()

    /** A received file opens in an app that can, with read access to just that file; a link opens in the browser. */
    override fun openEvent(event: Feed.Event) {
        if (event.kind == Feed.Kind.LINK) { open(event.name); return }
        val place = event.location ?: return
        val uri = if (place.startsWith("content://")) Uri.parse(place)
            else runCatching { FileProvider.getUriForFile(this, "$packageName.files", File(place)) }.getOrNull()
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(event.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        if (uri == null) { Toast.makeText(this, S.cantOpen, Toast.LENGTH_SHORT).show(); return }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(this, S.cantOpen, Toast.LENGTH_SHORT).show() }
    }

    private val icons = LruCache<String, ImageBitmap>(64)
    private val thumbnails = LruCache<String, ImageBitmap>(48)

    override fun appIcon(packageName: String): ImageBitmap? = icons.get(packageName)
        ?: runCatching { Mirror.icon(packageName)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()?.also { icons.put(packageName, it) }

    override suspend fun thumbnail(location: String): ImageBitmap? {
        thumbnails.get(location)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val size = Size(192, 192)
                if (location.startsWith("content://")) {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(Uri.parse(location), size, null) else null
                } else {
                    val file = File(location)
                    val video = file.extension.lowercase() in setOf("mp4", "mov", "mkv", "3gp", "webm", "m4v")
                    when {
                        !file.isFile -> null
                        Build.VERSION.SDK_INT >= 29 -> if (video) ThumbnailUtils.createVideoThumbnail(file, size, null) else ThumbnailUtils.createImageThumbnail(file, size, null)
                        video -> null
                        else -> sampled(file, 192)
                    }
                }
            }.getOrNull()
        } ?: return null
        return bitmap.asImageBitmap().also { thumbnails.put(location, it) }
    }

    private fun sampled(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
