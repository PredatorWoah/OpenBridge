package org.openbridge.desktop

import org.openbridge.desktop.FakePhone.Companion.battery
import org.openbridge.desktop.FakePhone.Companion.bytes
import org.openbridge.desktop.FakePhone.Companion.notification
import org.openbridge.desktop.FakePhone.Companion.removed
import org.openbridge.desktop.FakePhone.Companion.reply
import org.openbridge.desktop.FakePhone.Companion.reset
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EVENTS, NOTIFICATION ACTION, RING, OPEN URL and SEND against a phone that speaks them exactly as the protocol says. */
class PhoneEventsTest {
    private val phone = FakePhone()

    @AfterTest fun stop() = phone.close()

    @Test
    fun readsEveryKindAndSkipsUnknownOnes() {
        phone.eventReplies += reply(42, reset, battery(87, true), notification("k1", "Anna", "See you at 7", code = "482913"),
            9 to ByteArray(300) { 7 }, removed("k0"), notification("k2", "Bank", "Your code", flags = 0))
        val batch = phone.client().events(0, 0)
        assertEquals(42, batch.cursor)
        assertEquals(listOf(0L to 0), phone.eventRequests)
        assertEquals(5, batch.events.size)   // the unknown kind 9 is skipped by its length
        assertEquals(PhoneEvent.Reset, batch.events[0])
        assertEquals(PhoneEvent.BatteryChanged(Battery(87, true)), batch.events[1])
        val first = (batch.events[2] as PhoneEvent.Notified).notification
        assertEquals(PhoneNotification("k1", "Messages", "com.example.messages", "Anna", "See you at 7", 1_000, true, true, "482913"), first)
        assertEquals(PhoneEvent.Removed("k0"), batch.events[3])
        val second = (batch.events[4] as PhoneEvent.Notified).notification
        assertFalse(second.canReply); assertFalse(second.canDismiss)
    }

    @Test
    fun theWaitIsKeptInRange() {
        phone.client().events(7, 99_999)
        assertEquals(7L to 25_000, phone.eventRequests.single())
    }

    @Test
    fun refusesRepliesOutsideTheLimits() {
        fun read(bytes: ByteArray) = PhoneEvents.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertFailsWith<BridgeException> { read(bytes { writeLong(1); writeInt(201) }) }
        assertFailsWith<BridgeException> { read(bytes { writeLong(1); writeInt(-1) }) }
        assertFailsWith<BridgeException> { read(bytes { writeLong(1); writeInt(1); writeByte(9); writeInt(65_537) }) }
        assertFailsWith<BridgeException> { read(reply(1, battery(101, false))) }
        assertFailsWith<BridgeException> { read(reply(1, 3 to byteArrayOf(50, 2))) }
        // A body cut short, a string longer than its limit, or an empty key.
        assertFailsWith<BridgeException> { read(reply(1, 1 to byteArrayOf(0, 0, 0, 5, 65))) }
        assertFailsWith<BridgeException> { read(reply(1, notification("k", "t", "x", code = "12345678901234567"))) }
        assertFailsWith<BridgeException> { read(reply(1, removed(""))) }
        assertFailsWith<BridgeException> { read(reply(1, notification("k".repeat(257), "t", "x"))) }
        // Exactly at the limits is fine: 200 events, a 65,536-byte body of an unknown kind.
        assertEquals(200, read(reply(1, *Array(200) { battery(50, false) })).events.size)
        assertEquals(0, read(reply(1, 77 to ByteArray(65_536))).events.size)
        // Text at its limit, cut at a character boundary by the phone.
        val long = "长".repeat(1_365)
        assertEquals(long, ((read(reply(1, notification("k", "t", long))).events.single()) as PhoneEvent.Notified).notification.text)
    }

    @Test
    fun answersAndDismissesRingsAndOpensLinks() {
        val client = phone.client()
        client.notificationAction("k1")
        client.notificationAction("k1", "On my way ✓")
        client.ring(true); client.ring(false)
        client.openUrl("https://example.com/a?b=c")
        assertEquals(listOf("dismiss k1", "reply k1 On my way ✓", "ring 1", "ring 0", "open https://example.com/a?b=c"), phone.received)
        assertFailsWith<BridgeException> { client.notificationAction("k1", "") }
        assertFailsWith<BridgeException> { client.notificationAction("k1", "x".repeat(4_097)) }
        assertFailsWith<BridgeException> { client.openUrl("file:///etc/passwd") }
        assertFailsWith<BridgeException> { client.openUrl("javascript:alert(1)") }
        assertFailsWith<BridgeException> { client.openUrl("https://example.com/" + "a".repeat(2_048)) }
        val gone = assertFailsWith<BridgeException> { client.notificationAction("gone") }
        assertEquals("That notification is gone.", gone.message)
        assertFalse(gone.lost)
    }

    @Test
    fun sendsFilesAndContinuesInterruptedOnes() {
        val client = phone.client()
        val file = File(phone.base, "holiday.jpg").apply { writeBytes(ByteArray(300_000) { (it % 241).toByte() }) }
        var last = 0L
        assertEquals("holiday.jpg", client.sendFile(file) { done, _ -> last = done })
        assertEquals(300_000L, last)
        assertContentEquals(file.readBytes(), File(phone.sent, "holiday.jpg").readBytes())
        // The same name again: the phone picks a free one and says which.
        phone.partial["holiday.jpg"] = file.readBytes().copyOf(100_000)
        var first = -1L
        assertEquals("holiday (2).jpg", client.sendFile(file) { done, _ -> if (first < 0) first = done })
        assertEquals(100_000L, first)
        assertContentEquals(file.readBytes(), File(phone.sent, "holiday (2).jpg").readBytes())
        assertFailsWith<BridgeException> { client.sendFile(phone.base) }
    }

    @Test
    fun anOlderPhoneSaysSo() {
        FakePhone(revision = 18).use { old ->
            val client = old.client()
            assertEquals(18, client.info().revision)
            assertTrue(assertFailsWith<BridgeException> { client.events(0, 0) }.unsupported)
            assertTrue(assertFailsWith<BridgeException> { client.ring(true) }.unsupported)
        }
    }

    @Test
    fun theListFollowsEventsAndAnnouncesOnlyNewOnes() {
        val start = NotificationList().apply(listOf(PhoneEvent.Reset, PhoneEvent.Notified(n("a", "hi", 1)), PhoneEvent.Notified(n("b", "yo", 2)),
            PhoneEvent.BatteryChanged(Battery(40, false))))
        assertEquals(listOf("b", "a"), start.list.items.map { it.key })
        assertTrue(start.fresh.isEmpty(), "what was already on the phone isn't announced")
        assertEquals(Battery(40, false), start.list.battery)
        val next = start.list.apply(listOf(PhoneEvent.Notified(n("c", "new", 3)), PhoneEvent.Notified(n("a", "hi", 4)), PhoneEvent.Removed("b")))
        assertEquals(listOf("c"), next.fresh.map { it.key }, "an unchanged update isn't announced again")
        assertEquals(listOf("a", "c"), next.list.items.map { it.key })
        val changed = next.list.apply(listOf(PhoneEvent.Notified(n("a", "hi again", 5)), PhoneEvent.Notified(n("d", "gone soon", 6)), PhoneEvent.Removed("d")))
        assertEquals(listOf("a"), changed.fresh.map { it.key })
        assertEquals(emptyList(), changed.list.apply(listOf(PhoneEvent.Reset)).list.items)
    }

    @Test
    fun theBatteryWarnsOnce() {
        val alert = BatteryAlert()
        assertFalse(alert.check(Battery(16, false)))
        assertTrue(alert.check(Battery(15, false)))
        assertFalse(alert.check(Battery(14, false)))
        assertFalse(alert.check(Battery(12, true)))
        assertTrue(alert.check(Battery(12, false)))
        assertFalse(alert.check(Battery(18, false)))
        assertFalse(alert.check(Battery(21, false)))
        assertTrue(alert.check(Battery(15, false)))
    }

    @Test
    fun recognizesASingleLink() {
        assertEquals("https://example.com/x", singleUrl("  https://example.com/x\n"))
        assertEquals("http://192.168.1.2:8080/", singleUrl("http://192.168.1.2:8080/"))
        assertNull(singleUrl("see https://example.com"))
        assertNull(singleUrl("ftp://example.com"))
        assertNull(singleUrl("https://"))
        assertNull(singleUrl("example.com"))
    }

    private fun n(key: String, text: String, time: Long) = PhoneNotification(key, "App", "app", "Title", text, time, false, true, "")
}
