package org.openbridge.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Palette

/**
 * "Share › OpenBridge" from the gallery or any app: puts the files (or the text) in the outbox, where a paired computer
 * collects them. Starts sharing when it can (whole phone, permission granted); otherwise opens the app to start it.
 */
class ShareReceiver : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sharing.attach(this)
        val uris = sharedUris(intent)
        val text = if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        if (text != null) { sendText(text); return }
        if (uris.isEmpty()) {
            Toast.makeText(this, S.shareFilesOnly, Toast.LENGTH_LONG).show()
            finish(); return
        }
        var done by mutableIntStateOf(0)
        setContent {
            Box(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().glass(sheetShape, solid = true).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S.sendingToComputer, color = Palette.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(S.ofCount(minOf(done + 1, uris.size), uris.size), color = Palette.muted, fontSize = 13.sp)
                    LinearProgressIndicator(progress = { done.toFloat() / uris.size }, Modifier.fillMaxWidth(),
                        color = Palette.amber, trackColor = Palette.fg.copy(alpha = 0.12f))
                }
            }
        }
        // The copy runs while this screen is open: the share grants read access to it.
        Thread {
            val added = PhoneOutbox.add(this, uris) { finished, _ -> runOnUiThread { done = finished } }
            runOnUiThread { handOver(added) }
        }.start()
    }

    private fun handOver(added: Int) {
        if (added == 0) { Toast.makeText(this, S.couldNotRead, Toast.LENGTH_LONG).show(); finish(); return }
        Sharing.handOver { sharing ->
            if (sharing) Toast.makeText(this, S.queued(added, if (Sharing.paired) Sharing.computer else null), Toast.LENGTH_SHORT).show()
            else {
                Sharing.waitingNote(S.tapToSend(added))
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
    }

    /** Text shared to OpenBridge goes to the computer's clipboard (the subject, if any, is left out). */
    private fun sendText(text: String) {
        if (!Sharing.sendText(text)) {
            Toast.makeText(this, if (text.isBlank()) S.shareFilesOnly else S.textTooLong, Toast.LENGTH_LONG).show()
            finish(); return
        }
        Sharing.handOver { sharing ->
            if (!sharing) Sharing.waitingNote(S.startToSend)
            Toast.makeText(this, if (sharing && Sharing.paired) S.sentTo(Sharing.computer) else if (sharing) S.waitingForComputer else S.openToSend,
                Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}
