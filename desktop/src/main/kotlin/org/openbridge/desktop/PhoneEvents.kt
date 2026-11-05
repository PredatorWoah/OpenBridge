package org.openbridge.desktop

import org.openbridge.protocol.Wire
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/** A notification on the phone's screen, as EVENTS describes it (docs/protocol.md, EVENTS (19)). */
data class PhoneNotification(
    val key: String, val app: String, val packageName: String, val title: String, val text: String, val time: Long,
    val canReply: Boolean, val canDismiss: Boolean,
    /** A one-time code the phone found in the title or text, or empty. */
    val code: String,
)

/** The phone's battery: 0 to 100, and whether it's charging. */
data class Battery(val level: Int, val charging: Boolean)

/** One thing that happened on the phone. Kinds this computer doesn't know are skipped while reading. */
sealed class PhoneEvent {
    /** Forget every notification shown from this phone: the current state follows. */
    object Reset : PhoneEvent()
    data class Notified(val notification: PhoneNotification) : PhoneEvent()
    data class Removed(val key: String) : PhoneEvent()
    data class BatteryChanged(val battery: Battery) : PhoneEvent()
}

/** What one EVENTS request returned: the cursor for the next one, and the events after the one sent. */
data class EventBatch(val cursor: Long, val events: List<PhoneEvent>)

/** Reads EVENTS replies, holding the phone to the limits in the protocol. */
object PhoneEvents {
    const val MAX_EVENTS = 200
    const val MAX_BODY = 65_536
    /** The first revision (INFO) that answers EVENTS, NOTIFICATION ACTION, RING, OPEN URL and SEND. */
    const val REVISION = 23

    private fun invalid(): Nothing = throw BridgeException("The phone sent an invalid reply.")

    /** The reply after its status: cursor, count, then each event's kind, body length and body. */
    fun read(input: DataInputStream): EventBatch {
        val cursor = input.readLong()
        val count = input.readInt()
        if (count !in 0..MAX_EVENTS) invalid()
        val events = ArrayList<PhoneEvent>(count)
        repeat(count) {
            val kind = input.readUnsignedByte()
            val length = input.readInt()
            if (length !in 0..MAX_BODY) invalid()
            val body = ByteArray(length).also(input::readFully)
            parse(kind, body)?.let(events::add)
        }
        return EventBatch(cursor, events)
    }

    /** One event from its kind and body; null for a kind this computer doesn't know. */
    fun parse(kind: Int, body: ByteArray): PhoneEvent? {
        val input = DataInputStream(ByteArrayInputStream(body))
        try {
            return when (kind) {
                0 -> PhoneEvent.Reset
                1 -> {
                    val key = Wire.readString(input, 256)
                    val app = Wire.readString(input, 128)
                    val packageName = Wire.readString(input, 128)
                    val title = Wire.readString(input, 512)
                    val text = Wire.readString(input, 4_096)
                    val time = input.readLong()
                    val flags = input.readUnsignedByte()
                    val code = Wire.readString(input, 16)
                    if (key.isEmpty()) invalid()
                    PhoneEvent.Notified(PhoneNotification(key, app, packageName, title, text, time, flags and 1 != 0, flags and 2 != 0, code))
                }
                2 -> PhoneEvent.Removed(Wire.readString(input, 256).ifEmpty { invalid() })
                3 -> {
                    val level = input.readUnsignedByte()
                    val charging = input.readUnsignedByte()
                    if (level > 100 || charging > 1) invalid()
                    PhoneEvent.BatteryChanged(Battery(level, charging == 1))
                }
                else -> null
            }
        } catch (e: BridgeException) { throw e } catch (e: IOException) { invalid() }
    }
}

/**
 * The notifications shown from one phone, newest first, after applying events. `fresh` are the ones to announce on
 * this computer: new, or changed in what they say. The state a phone sends after a RESET is not announced: those
 * notifications were already on its screen.
 */
data class NotificationList(val items: List<PhoneNotification> = emptyList(), val battery: Battery? = null) {
    data class Applied(val list: NotificationList, val fresh: List<PhoneNotification>)

    fun apply(events: List<PhoneEvent>): Applied {
        var items = items
        var battery = battery
        val fresh = LinkedHashMap<String, PhoneNotification>()
        var replaying = false
        for (event in events) when (event) {
            PhoneEvent.Reset -> { items = emptyList(); fresh.clear(); replaying = true }
            is PhoneEvent.Notified -> {
                val n = event.notification
                val before = items.firstOrNull { it.key == n.key }
                items = listOf(n) + items.filter { it.key != n.key }
                if (!replaying && (before == null || before.title != n.title || before.text != n.text)) fresh[n.key] = n
            }
            is PhoneEvent.Removed -> { items = items.filter { it.key != event.key }; fresh.remove(event.key) }
            is PhoneEvent.BatteryChanged -> battery = event.battery
        }
        return Applied(NotificationList(items.sortedByDescending { it.time }.take(KEEP), battery), fresh.values.toList())
    }

    fun without(key: String) = copy(items = items.filter { it.key != key })

    companion object { const val KEEP = 200 }
}

/** The battery alert: once when the level falls to 15% while not charging; again only after charging or rising above 20%. */
class BatteryAlert {
    private var warned = false
    fun check(battery: Battery): Boolean {
        if (battery.charging || battery.level > 20) { warned = false; return false }
        if (battery.level <= 15 && !warned) { warned = true; return true }
        return false
    }
}

/** A text that is only a web link (http or https), to offer opening it in the browser. */
fun singleUrl(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || trimmed.length > 2_048) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https" || uri.host.isNullOrEmpty()) return null
    return trimmed
}
