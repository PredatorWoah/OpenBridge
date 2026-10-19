package org.openbridge.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Sends the phone's clipboard to the computer: from the "Send clipboard" Quick Settings tile and the sharing
 * notification. Android lets only the app in front read the clipboard, so this invisible screen opens for a moment,
 * reads it once its window has focus, queues it for the computer and closes.
 */
class SendClipboard : Activity() {
    private var read = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sharing.attach(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || read) return
        read = true
        val text = PhoneClipboard.text(this)
        when {
            text.isBlank() -> done(S.clipboardEmpty)
            !Sharing.sendText(text) -> done(S.textTooLong)
            else -> Sharing.handOver { sharing ->
                if (!sharing) Sharing.waitingNote(S.startToSend)
                done(if (sharing && Sharing.paired) S.sentTo(Sharing.computer) else if (sharing) S.waitingForComputer else S.openToSend)
            }
        }
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SendClipboard::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The "Send clipboard" Quick Settings tile. */
class ClipboardTile : TileService() {
    override fun onStartListening() {
        qsTile?.run { label = S.sendClipboard; state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        // Never read the clipboard over the lock screen.
        if (isLocked) unlockAndRun(::open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")   // the Intent form only runs below Android 14, where it's the only one
    private fun open() {
        val intent = SendClipboard.intent(this)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
