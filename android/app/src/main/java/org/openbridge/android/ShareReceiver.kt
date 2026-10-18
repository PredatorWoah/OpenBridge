package org.openbridge.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbridge.ui.Palette

/**
 * "Share › OpenBridge" from the gallery or any app: copies the files into the outbox, where a paired computer
 * collects them. Starts sharing when it can (whole phone, permission granted); otherwise opens the app to tap the sun.
 */
class ShareReceiver : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sharing.attach(this)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, S.shareFilesOnly, Toast.LENGTH_LONG).show()
            finish(); return
        }
        var done by mutableIntStateOf(0)
        setContent {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xF23C2878), Color(0xF7140E32))))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S.sendingToComputer, color = Palette.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(S.ofCount(minOf(done + 1, uris.size), uris.size), color = Palette.muted, fontSize = 13.sp)
                    LinearProgressIndicator(progress = { done.toFloat() / uris.size }, Modifier.fillMaxWidth(),
                        color = Palette.amber, trackColor = Color.White.copy(alpha = 0.12f))
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
        when {
            Sharing.server != null -> Toast.makeText(this, S.queued(added, Sharing.computer), Toast.LENGTH_SHORT).show()
            Sharing.wholePhone && AllFiles.granted(this) -> {
                // Background sharing keeps it going after this screen closes.
                Sharing.start()
                Toast.makeText(this, S.queued(added, null), Toast.LENGTH_SHORT).show()
            }
            else -> {
                Sharing.waitingNote(S.tapToSend(added))
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
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
