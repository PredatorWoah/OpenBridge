package org.openbridge.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.openbridge.protocol.Clipboard
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The phone's clipboard for a paired computer. Android only lets an app read it while the app is on screen. */
class PhoneClipboard(private val context: Context) : Clipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        val result = CompletableFuture<T>()
        main.post { try { result.complete(block()) } catch (e: Exception) { result.completeExceptionally(e) } }
        return try { result.get(5, TimeUnit.SECONDS) }
        catch (e: ExecutionException) { throw e.cause as? IOException ?: IOException("The phone's clipboard is unavailable") }
        catch (e: TimeoutException) { throw IOException("The phone's clipboard is unavailable") }
    }

    override fun read(): String = onMain {
        if (!Sharing.onScreen) throw IOException("Open OpenBridge on the phone to share its clipboard")
        text(context)
    }

    /** A text from the computer: onto the clipboard, into Texts, and a short notice when the app isn't in front. */
    override fun write(text: String) {
        val computer = Sharing.lastComputer
        onMain {
            manager.setPrimaryClip(ClipData.newPlainText("From your computer", text))
            Texts.received(text, computer)
            if (!Sharing.onScreen) ShareService.textReceived(context, computer)
        }
    }

    companion object {
        /** The clipboard's text, or "" (only while the app has focus). */
        fun text(context: Context): String {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            return if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        }
    }
}
