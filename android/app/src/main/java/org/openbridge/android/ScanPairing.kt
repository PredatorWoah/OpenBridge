package org.openbridge.android

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openbridge.protocol.QrPairing

/** Why pairing by QR code didn't finish, for the screen to explain. */
enum class QrProblem {
    /** Two minutes passed and no computer proved it had the code. */
    EXPIRED,
    /** Three wrong proofs: something that wasn't the computer tried the code, so it was cancelled. */
    REFUSED,
    /** Sharing stopped (or couldn't start), so the computer couldn't reach the phone. */
    NOT_SHARING,
    /** The camera permission was refused. */
    NO_CAMERA,
}

/** Pairing by QR code on the screen: the scanner, the code waiting for its computer, and how it ended. */
@Immutable
data class QrUi(
    val scanning: Boolean = false,
    /** The computer whose code was scanned, while its key waits. */
    val waitingFor: String? = null,
    /** When the key expires, in wall-clock milliseconds. */
    val expiresAt: Long = 0,
    /** The computer that just paired, for the success card. */
    val paired: String? = null,
    val problem: QrProblem? = null,
    /** The computer the problem is about, when known. */
    val problemComputer: String? = null,
)

/**
 * Pairing by QR code (OBP3): the computer shows a code, the phone scans it and keeps its key for two minutes, and the
 * computer that proves it holds the key is trusted (scanning is the approval). The key lives in [keys] (memory only),
 * which every sharing session uses. Diagnostics note that a code was scanned and how it ended, never the key or a name.
 */
object ScanPairing {
    val keys = QrPairing()
    private val main = Handler(Looper.getMainLooper())

    var ui by mutableStateOf(QrUi()); private set

    private val expiry = Runnable {
        if (ui.waitingFor != null && keys.waitingFor() == null) {
            Log.add("A scanned pairing code expired")
            ui = QrUi(problem = QrProblem.EXPIRED, problemComputer = ui.waitingFor)
        }
    }

    init {
        keys.listen(object : QrPairing.Listener {
            override fun paired(computer: String) {
                Log.add("Paired by QR code")
                main.post {
                    main.removeCallbacks(expiry)
                    ui = QrUi(paired = computer)
                    Feed.paired(computer)
                    Sharing.pairedByCode(computer)
                }
            }

            override fun refused() {
                Log.add("A scanned pairing code was cancelled after three wrong attempts")
                main.post {
                    main.removeCallbacks(expiry)
                    ui = QrUi(problem = QrProblem.REFUSED, problemComputer = ui.waitingFor)
                }
            }
        })
    }

    /** Opens the scanner (the camera permission is already granted). */
    fun open() { ui = ui.copy(scanning = true, problem = null, paired = null) }

    fun close() { ui = ui.copy(scanning = false) }

    /** What the scanner made of a code it read. */
    enum class Read { PAIRING, OTHER, DAMAGED }

    /**
     * The scanner read [text]. An OpenBridge pairing code is accepted: its key waits for the computer and the scanner
     * closes. Another QR code, or a damaged one, keeps the scanner open with a hint. On the main thread.
     */
    fun scanned(text: String): Read {
        if (!QrPairing.isPairingCode(text)) return Read.OTHER
        val code = try { QrPairing.parse(text) } catch (e: IllegalArgumentException) {
            Log.add("Scanned a pairing code that couldn't be read: ${e.message}")
            return Read.DAMAGED
        }
        val expires = keys.offer(code)
        Log.add("Scanned a pairing code; waiting up to 2 minutes for the computer")
        // The key's clock is the wall clock: the screen counts down to the same moment.
        ui = QrUi(waitingFor = code.computer, expiresAt = expires)
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, (expires - System.currentTimeMillis()).coerceAtLeast(0) + 50)
        return Read.PAIRING
    }

    /** The user cancelled while the code waited. */
    fun cancel() {
        if (ui.waitingFor != null) Log.add("Pairing by QR code cancelled")
        keys.cancel()
        main.removeCallbacks(expiry)
        ui = QrUi()
    }

    /** Sharing stopped, or couldn't start, while a code waited: nothing can reach the phone now. */
    fun sharingEnded() {
        val waiting = ui.waitingFor ?: return
        keys.cancel()
        main.removeCallbacks(expiry)
        Log.add("Pairing by QR code cancelled: sharing stopped")
        ui = QrUi(problem = QrProblem.NOT_SHARING, problemComputer = waiting)
    }

    /** The camera permission was refused. */
    fun noCamera() { ui = QrUi(problem = QrProblem.NO_CAMERA) }

    /** Closes the success or problem card. */
    fun dismiss() { ui = ui.copy(paired = null, problem = null, problemComputer = null) }

    /** For diagnostics. */
    val summary get() = when {
        ui.waitingFor != null -> "a scanned code is waiting"
        ui.scanning -> "scanner open"
        else -> "none"
    }
}
