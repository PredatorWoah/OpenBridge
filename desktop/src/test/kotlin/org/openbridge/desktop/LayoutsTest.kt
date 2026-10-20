package org.openbridge.desktop

import org.openbridge.desktop.wpd.UsbPhone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The decisions behind the looks' layouts: folded paths, the queue's progress, and which phones the switchers show. */
class LayoutsTest {
    @Test
    fun shortPathsShowEveryFolder() {
        assertEquals(listOf<Int?>(0, 1, 2), foldPath(3, 5))
        assertEquals(listOf<Int?>(0), foldPath(1, 3))
        assertEquals(listOf<Int?>(0, 1), foldPath(2, 1))
    }

    @Test
    fun longPathsFoldTheMiddleAndKeepTheOpenFolder() {
        assertEquals(listOf(0, null, 5, 6, 7), foldPath(8, 5))
        assertEquals(listOf(1, 2, 3, 4), foldedOut(8, 5))
        assertEquals(listOf(0, null, 7), foldPath(8, 3))
        // Even with no room, the top and the open folder stay.
        assertEquals(listOf(0, null, 7), foldPath(8, 1))
        assertTrue(foldedOut(4, 5).isEmpty())
    }

    @Test
    fun overallProgressCountsFinishedFilesWhole() {
        assertEquals(0f, overallProgress(Transfer("…", upload = false)))
        assertEquals(0.5f, overallProgress(Transfer("a", false, index = 1, count = 1, fraction = 0.5f)))
        assertEquals((1 + 0.5f) / 4, overallProgress(Transfer("b", false, index = 2, count = 4, fraction = 0.5f)))
        assertEquals(1f, overallProgress(Transfer("d", false, index = 4, count = 4, fraction = 1f)))
        assertEquals(1f, overallProgress(Transfer("d", false, index = 2, count = 4, fraction = 0.1f, done = true)))
        // Odd values never escape 0..1.
        assertEquals(1f, overallProgress(Transfer("x", false, index = 9, count = 4, fraction = 3f)))
        assertEquals(0f, overallProgress(Transfer("x", false, index = 0, count = 0, fraction = -1f)))
    }

    @Test
    fun queueListsWhatComesNext() {
        val jobs = (1..7).map { Job.Upload(File("f$it"), "", emptyList()) }
        val running = queueOf(Transfer("f3", true, index = 3, count = 7, fraction = 0.25f, remaining = jobs.drop(2)), show = 3)
        assertEquals(2, running.finished)
        assertEquals("f3", running.current)
        assertEquals(listOf("f4", "f5", "f6"), running.upcoming)
        assertEquals(1, running.more)
        val done = queueOf(Transfer("f7", true, index = 7, count = 7, fraction = 1f, done = true))
        assertEquals(7, done.finished)
        assertTrue(done.upcoming.isEmpty())
        assertTrue(stalled(Transfer("f3", true, remaining = jobs.drop(2)), busy = false))
        assertTrue(!stalled(Transfer("f3", true, remaining = jobs.drop(2)), busy = true))
    }

    @Test
    fun switchersShowEachPhoneOnceWithTheOpenOneFirst() {
        val vivo = SavedPhone("vivo", ByteArray(32) { 1 }, "192.168.1.23", 41987, ByteArray(32))
        val pixel = SavedPhone("Pixel", ByteArray(32) { 2 }, "", 41987, ByteArray(32))
        val state = AppState().apply {
            saved = listOf(vivo, pixel)
            nearby = listOf(NearbyPhone("vivo", "192.168.1.23", "41987", vivo.id), NearbyPhone("Moto", "192.168.1.50", "41987", "ff"))
            usbLinks = listOf(UsbLink("A", "Pixel", UsbLink.State.READY, 5000, pixel.fingerprint, pixel), UsbLink("B", "SM-S918B", UsbLink.State.UNAUTHORIZED))
            wpdPhones = listOf(UsbPhone("w", "Galaxy", "Samsung"))
        }
        val stars = phoneStars(state)
        assertEquals(listOf("vivo", "Pixel", "Moto", "SM-S918B", "Galaxy"), stars.map { it.name })
        assertTrue(stars[0].here && stars[0].saved && !stars[0].cable)
        assertTrue(stars[1].cable && stars[1].here)
        assertTrue(!stars[3].here && stars[3].status == S.allowDebugging)
        assertTrue(stars.none { it.open })

        // A phone open in the browser that nothing else describes still gets its star, first.
        state.source = object : PhoneSource by NoPhone {}
        state.deviceName = "Old phone"
        val open = phoneStars(state)
        assertEquals("Old phone", open[0].name)
        assertTrue(open[0].open)
    }

    private object NoPhone : PhoneSource {
        override fun list(id: String) = emptyList<Entry>()
        override fun download(entry: Entry, destination: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) = Unit
        override fun upload(source: File, directoryId: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) = Unit
        override fun makeFolder(name: String, directoryId: String) = Entry("x", name, true, 0)
        override fun thumbnail(entry: Entry, size: Int): ByteArray = throw BridgeException("none")
        override fun delete(entry: Entry) = Unit
        override fun rename(entry: Entry, name: String) = entry
        override fun measure(folder: Entry): FolderSize = throw BridgeException("none")
        override fun search(folderId: String, query: String) = SearchResult(emptyList(), true)
    }
}
