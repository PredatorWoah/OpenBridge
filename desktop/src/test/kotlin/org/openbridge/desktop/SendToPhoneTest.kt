package org.openbridge.desktop

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendToPhoneTest {
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!check()) { if (System.currentTimeMillis() > until) throw AssertionError("Timed out: $what"); Thread.sleep(50) }
    }

    private fun <T> onUi(block: () -> T): T { var result: T? = null; SwingUtilities.invokeAndWait { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }

    @Test
    fun aSecondLaunchHandsItsFilesToTheFirst() {
        val folder = Files.createTempDirectory("openbridge-handoff").toFile()
        try {
            val file = File(folder, "photo one.jpg").apply { writeText("x") }
            val got = CopyOnWriteArrayList<File>()
            val first = Handoff(folder)
            assertTrue(first.claim(emptyList()) { got += it })
            val second = Handoff(folder)
            assertFalse(second.claim(listOf(file.path, File(folder, "missing.txt").path)) { throw AssertionError("not the running one") })
            waitFor("the files") { got.isNotEmpty() }
            assertEquals(listOf(file.absoluteFile), got)
            // Without the token nothing is taken.
            val port = File(folder, "instance").readText().trim().substringBefore(' ').toInt()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write("not-the-token\n${file.path}\n\n".toByteArray())
                assertNull(socket.getInputStream().bufferedReader().readLine())
            }
            assertEquals(1, got.size)
            if (!Platform.windows) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(File(folder, "instance").toPath())))
            first.close()
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun theLinuxEntryTakesFiles() {
        val entry = Integration.desktopFile("/opt/openbridge/bin/Open \"Bridge\"")
        assertTrue(entry.lines().contains("Exec=\"/opt/openbridge/bin/Open \\\"Bridge\\\"\" %F"), entry)
        assertTrue(entry.lines().contains("NoDisplay=true"))
        assertTrue(entry.lines().any { it.startsWith("MimeType=") && "image/jpeg;" in it })
    }

    @Test
    fun filesWaitForAPhoneThenGoWithProgress() {
        FakePhone().use { phone ->
            val state = onUi { AppState() }
            val one = File(phone.base, "trip.mp4").apply { writeBytes(ByteArray(2_500_000) { (it % 7).toByte() }) }
            val folder = File(phone.base, "album").apply { mkdirs() }
            File(folder, "a.jpg").writeText("a"); File(folder, ".hidden").writeText("h")
            onUi { state.sendToPhone(listOf(one, folder)) }
            assertEquals(listOf("trip.mp4", "a.jpg"), state.sendQueue.map { it.name }, "waits until a phone is connected")
            val client = phone.client()
            onUi { state.source = client; state.phoneInfo = client.info(); state.flushSends() }
            assertTrue(state.companion)
            waitFor("sent") { phone.received.size == 2 && state.transfer?.done == true }
            assertContentEquals(one.readBytes(), File(phone.sent, "trip.mp4").readBytes())
            assertEquals("a", File(phone.sent, "a.jpg").readText())
            assertTrue(state.sendQueue.isEmpty())
            assertEquals(2, state.transfer?.count)
            onUi { state.source = null }
        }
    }

    @Test
    fun eventsBecomeTheListAndTheBattery() {
        val state = onUi { AppState() }
        onUi {
            state.eventsArrived("p", listOf(PhoneEvent.Reset, PhoneEvent.BatteryChanged(Battery(14, false)),
                PhoneEvent.Notified(PhoneNotification("k", "Messages", "m", "Anna", "Hi", 5, true, true, ""))))
        }
        assertEquals(Battery(14, false), state.battery)
        assertEquals(listOf("k"), state.phoneStatus.items.map { it.key })
        onUi { state.eventsArrived("p", listOf(PhoneEvent.Removed("k"))) }
        assertTrue(state.phoneStatus.items.isEmpty())
    }
}
