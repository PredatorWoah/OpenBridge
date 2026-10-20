package org.openbridge.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.openbridge.desktop.wpd.UsbPhone
import org.openbridge.ui.Look
import org.openbridge.ui.Palette
import org.openbridge.ui.Release
import java.io.File
import kotlin.test.Test

/** Draws the main screens to PNGs in $PREVIEW_DIR, to look at layouts without a display. Skipped without it. */
class UiPreview {
    private object Nothing : PhoneSource {
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

    private fun render(out: File, name: String, state: AppState, width: Int = 1060, height: Int = 700) {
        val scene = ImageComposeScene(width, height, Density(1f)) { App(state, false, FocusRequester()) }
        scene.render(0)
        scene.render(400_000_000)
        val image = scene.render(1_600_000_000)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test fun screens() {
        val out = File(System.getenv("PREVIEW_DIR") ?: return).apply { mkdirs() }
        val fingerprint = ByteArray(32) { it.toByte() }
        val vivo = SavedPhone("vivo X300 Pro", fingerprint, "192.168.1.23", 41987, ByteArray(32))
        for (look in Look.entries) {
            Palette.look = look
            val start = AppState().apply {
                saved = listOf(vivo)
                nearby = listOf(NearbyPhone("Pixel 8", "192.168.1.40", "41987", "aa"))
                usbLinks = listOf(UsbLink("A", "V2405A", UsbLink.State.READY, 5000, ByteArray(32)), UsbLink("B", "SM-S918B", UsbLink.State.UNAUTHORIZED))
                wpdPhones = listOf(UsbPhone("id", "Galaxy S23", "Samsung"))
                isolated = true
                update = Release("0.13.1", "https://example.invalid")
            }
            render(out, "${look.id}-connect", start)

            val browse = AppState().apply {
                source = Nothing; transport = Transport.FAST_USB; deviceName = "vivo X300 Pro"
                path = listOf("" to S.phone, "d" to "DCIM", "c" to "Camera")
                entries = listOf(Entry("1", "Screenshots", true, 0), Entry("2", "IMG_20261019_101500.jpg", false, 3_400_000),
                    Entry("3", "VID_20261018_220000.mp4", false, 812_000_000), Entry("4", "notes.txt", false, 1200), Entry("5", "report.pdf", false, 2_100_000))
                selection = setOf("2", "3"); cursor = "3"
                sizes = mapOf("1" to FolderSize(48_000_000, 31, true))
                transfer = Transfer("VID_20261018_220000.mp4", upload = false, index = 2, count = 3, fraction = 0.42f)
                phoneOutdated = S.phoneOlder("0.12.2", "0.13.0")
                update = Release("0.13.1", "https://example.invalid")
                texts = listOf(TextItem("Meet at 7 by the north entrance", true, 1_760_000_000_000), TextItem("https://example.com/a/very/long/link", false, 1_759_990_000_000))
            }
            browse.saved = listOf(vivo); browse.nearby = start.nearby; browse.usbLinks = start.usbLinks
            browse.transfer = null; browse.update = null; browse.phoneOutdated = null
            browse.grid = false
            render(out, "${look.id}-browser-list", browse)
            browse.grid = true
            render(out, "${look.id}-browser-grid", browse)
            browse.grid = null
            // A queue of seven, the third file 42% of the way.
            val jobs = (1..7).map { Job.Upload(File("IMG_2026101${it}_0900.jpg"), "", emptyList()) }
            browse.transfer = Transfer(jobs[2].name, upload = true, index = 3, count = 7, fraction = 0.42f, remaining = jobs.drop(2))
            browse.busy = true
            browse.update = Release("0.13.1", "https://example.invalid")
            browse.phoneOutdated = S.phoneOlder("0.12.2", "0.13.0")
            render(out, "${look.id}-transfers", browse)
            browse.busy = false
            render(out, "${look.id}-transfers-stopped", browse)
            browse.update = null; browse.phoneOutdated = null
            browse.path = listOf("" to S.phone, "i" to "Internal storage", "d" to "DCIM", "c" to "Camera", "2" to "2026", "o" to "October trip")
            render(out, "${look.id}-compact", browse, width = 740, height = 500)
            render(out, "${look.id}-connect-compact", start, width = 740, height = 500)
            browse.panel = Panel.SETTINGS
            render(out, "${look.id}-settings", browse)
            browse.panel = Panel.TEXTS
            render(out, "${look.id}-texts", browse)
            browse.panel = null
            render(out, "${look.id}-browser-narrow", browse, width = 820, height = 620)
        }
        Palette.look = Look.DUSK
    }
}
