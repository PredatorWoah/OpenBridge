package org.openbridge.desktop

import org.openbridge.protocol.Pairing
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing by QR code (OBP3, from 1.3.0; see docs/protocol.md): this computer shows a code holding a fresh key `K`
 * and its name; the phone scans it and keeps `K` for two minutes. The computer then tries each phone it can reach.
 * Both prove they hold `K` over the certificate `F` the computer saw, so neither a phone that didn't scan the code nor
 * anything in between can pair. Scanning is the user's approval: nothing is compared by eye.
 */
object QrPairing {
    val MAGIC = byteArrayOf('O'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(), '3'.code.toByte())
    /** Bytes in `K`. */
    const val KEY = 16
    /** How long a code (and its key) is good for. */
    const val LIFETIME = 120_000L
    /** The longest name the code carries, in UTF-8 bytes. */
    const val NAME = 128

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY).also(random::nextBytes)

    /** The text in the QR code: `openbridge://pair?k=<K>&n=<name>`. */
    fun link(key: ByteArray, name: String): String {
        require(key.size == KEY)
        return "openbridge://pair?k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(key) + "&n=" + percent(cut(name))
    }

    /** `name` cut at a character boundary to at most [NAME] UTF-8 bytes, as in the OBP2 name block. */
    fun cut(name: String): String {
        val block = Pairing.nameBlock(name)
        var length = block.size
        while (length > 0 && block[length - 1] == 0.toByte()) length--
        return String(block, 0, length, Charsets.UTF_8)
    }

    /** Percent-encoded UTF-8: everything but letters, digits and `-._~` becomes `%XX` (a space is `%20`, never `+`). */
    fun percent(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code)
                append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    /** `Ps`: what the phone sends to prove it holds `K`, over the certificate `F` this computer saw and both nonces. */
    fun phoneProof(key: ByteArray, fingerprint: ByteArray, computerNonce: ByteArray, phoneNonce: ByteArray) =
        hmac(key, "OpenBridge QR phone", fingerprint, computerNonce, phoneNonce)

    /** `Pc`: this computer's answer, sent only after `Ps` checked out. */
    fun computerProof(key: ByteArray, fingerprint: ByteArray, computerNonce: ByteArray, phoneNonce: ByteArray) =
        hmac(key, "OpenBridge QR computer", fingerprint, computerNonce, phoneNonce)

    private fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        parts.forEach(mac::update)
        return mac.doFinal()
    }
}

/** A QR code on screen: its key, the text it shows, and when it stops working. */
class QrCode(val key: ByteArray, val text: String, val created: Long) {
    val expires: Long get() = created + QrPairing.LIFETIME
    fun expired(now: Long) = now >= expires
    /** Whole seconds left, counting down from 120. */
    fun secondsLeft(now: Long) = ((expires - now + 999) / 1000).coerceAtLeast(0)

    companion object {
        fun fresh(name: String = Platform.computerName, now: Long = System.currentTimeMillis()): QrCode {
            val key = QrPairing.newKey()
            return QrCode(key, QrPairing.link(key, name), now)
        }
    }
}

/** Somewhere a phone that scanned the code may answer: announced on the Wi‑Fi, at the default gateway, or over Fast USB. */
data class QrTarget(val host: String, val port: Int, val transport: Transport, val name: String?, val serial: String? = null) {
    val key get() = "$host:$port"
}

/**
 * Where to look for the phone that scanned the code, without repeats: phones announcing themselves, the default
 * gateway on both ports (this computer on the phone's hotspot), and phones on the cable whose Fast USB answers.
 */
fun qrTargets(nearby: List<NearbyPhone>, gateway: String?, links: List<UsbLink>): List<QrTarget> {
    val found = LinkedHashMap<String, QrTarget>()
    fun add(target: QrTarget) { found.putIfAbsent(target.key, target) }
    for (link in links) if (link.ready && link.port > 0) add(QrTarget("127.0.0.1", link.port, Transport.FAST_USB, link.name, link.serial))
    for (phone in nearby) {
        val port = phone.port.toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
        if (privateIPv4(phone.host)) add(QrTarget(phone.host, port, Transport.WIFI, phone.name.takeIf { it != S.hotspotPhone }))
    }
    if (gateway != null && privateIPv4(gateway)) for (port in listOf(41987, HOTSPOT_PORT)) add(QrTarget(gateway, port, Transport.WIFI, null))
    return found.values.toList()
}
