package org.openbridge.android

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect

/** Hosts the main screen and does what it asks: permissions, pickers, other apps, the clipboard. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
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
            OpenBridgeTheme { Home(this) }
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
        Updater.resumed(this)
    }

    override fun onPause() {
        model.onScreen = false
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

    override fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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

    override fun copyDiagnostics() = copy(Log.report(this))

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
