package org.openbridge.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        // Frames a tenth of a second apart for two seconds, so things that arrive with a motion have arrived.
        for (frame in 0 until 20) scene.render(frame * 100_000_000L)
        val image = scene.render(2_000_000_000)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** The tray's status window, with names long enough that they must shorten, and the tray icons, in every look. */
    @Test fun tray() {
        val out = File(System.getenv("PREVIEW_DIR") ?: return).apply { mkdirs() }
        val language = java.util.Locale.getDefault().language
        val longPhone = "Galaxy S24 Ultra of Alexandra Konstantinopoulou-Papadimitriou (work phone)"
        val longFile = "IMG_20261019_101500_HDR_portrait_mode_with_a_very_long_descriptive_name_final_v2.jpg"
        val cases = listOf(
            "moving" to TrayStatus(true, "Pixel 8", S.wifi, moving = true, file = "VID_20261018_220000.mp4", fraction = 0.42f, index = 3, count = 7,
                rate = 18.3 * 1024 * 1024, secondsLeft = 125),
            "moving-long" to TrayStatus(true, longPhone, S.fastUsb, moving = true, upload = true, file = longFile, fraction = 0.873f, index = 1234, count = 5678,
                rate = 1023.9 * 1024 * 1024, secondsLeft = 3 * 3600 + 1234),
            "idle-long" to TrayStatus(true, longPhone, S.wifi),
            "disconnected" to TrayStatus(false, "", ""),
        )
        for (look in Look.entries) {
            Palette.look = look
            for ((name, status) in cases) {
                val scene = ImageComposeScene(360, 300, Density(1f)) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(20.dp)) { StatusCard(status, {}, {}, {}) }
                }
                for (frame in 0 until 10) scene.render(frame * 100_000_000L)
                File(out, "tray-$language-${look.id}-$name.png").writeBytes(scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
                println("tooltip ($language, $name): ${status.tooltip()}")
            }
            // The icons as the tray gets them (32 pixels), each also shown four times as large.
            val states = listOf(TrayStatus(false, "", ""), TrayStatus(true, "Pixel 8", S.wifi), TrayStatus(true, "Pixel 8", S.wifi, moving = true, fraction = 0.42f))
            val sheet = java.awt.image.BufferedImage(states.size * 150, 150, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = sheet.createGraphics()
            states.forEachIndexed { i, status ->
                g.color = if (i % 2 == 0) java.awt.Color(0x20, 0x20, 0x28) else java.awt.Color(0xEE, 0xEE, 0xF2)
                g.fillRect(i * 150, 0, 150, 150)
                val icon = trayImage(32, status)
                g.drawImage(icon, i * 150 + 8, 8, null)
                g.drawImage(icon, i * 150 + 14, 14, 128, 128, null)
            }
            g.dispose()
            javax.imageio.ImageIO.write(sheet, "png", File(out, "tray-icons-${look.id}.png"))
        }
        Palette.look = Look.DUSK
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

    /** Pairing by QR code in every look: counting down, with an old phone's note, expired, and paired; also narrow. */
    @Test fun qr() {
        val out = File(System.getenv("PREVIEW_DIR") ?: return).apply { mkdirs() }
        val language = java.util.Locale.getDefault().language
        val now = System.currentTimeMillis()
        for (look in Look.entries) {
            Palette.look = look
            val state = AppState().apply {
                nearby = listOf(NearbyPhone("Pixel 8", "192.168.1.40", "41987", "aa"))
                saved = listOf(SavedPhone("vivo X300 Pro", ByteArray(32) { it.toByte() }, "192.168.1.23", 41987, ByteArray(32)))
                qr = QrCode.fresh("Alexandra's ThinkPad X1 Carbon (work)", now - 35_000)
            }
            render(out, "qr-$language-${look.id}-connect", state)
            render(out, "qr-$language-${look.id}-connect-compact", state, width = 740, height = 500)
            state.qrOld = listOf("Galaxy S21")
            render(out, "qr-$language-${look.id}-old", state)
            state.qrOld = emptyList()
            state.qr = QrCode.fresh("PC", now - 125_000)
            render(out, "qr-$language-${look.id}-expired", state)
            state.qr = QrCode.fresh("PC", now)
            state.qrPaired = "Galaxy S24 Ultra of Alexandra Konstantinopoulou-Papadimitriou"
            render(out, "qr-$language-${look.id}-paired", state)
        }
        Palette.look = Look.DUSK
    }

    /** Now playing in the tray's status window, the phone's sheet and the main window, with names long enough to shorten. */
    @Test fun media() {
        val out = File(System.getenv("PREVIEW_DIR") ?: return).apply { mkdirs() }
        val language = java.util.Locale.getDefault().language
        val client = BridgeClient("127.0.0.1", 1, ByteArray(32), ByteArray(32), Transport.FAST_USB)
        val now = System.currentTimeMillis()
        val songs = listOf(
            "long" to PhoneMedia("YouTube Music", "Symphony No. 9 in D minor, Op. 125 \"Choral\": IV. Presto – Allegro assai (Live at the Royal Albert Hall, 2026 remaster)",
                "Berliner Philharmoniker, Herbert von Karajan, Wiener Singverein, Gundula Janowitz", 1_472_000, 512_000, PhoneMedia.State.PLAYING, 15, now),
            "zh" to PhoneMedia("网易云音乐", "晴天（周杰伦 2003 年专辑《叶惠美》中的经典歌曲，现场版完整收录）", "周杰伦", 269_000, 61_000, PhoneMedia.State.PAUSED, 7, now),
        )
        for (look in Look.entries) {
            Palette.look = look
            for ((name, song) in songs) {
                val state = AppState().apply {
                    source = client; transport = Transport.WIFI; deviceName = "vivo X300 Pro"
                    phoneInfo = PhoneInfo("1.3.0", 24, deviceName)
                    phoneStatus = NotificationList(emptyList(), Battery(64, true), song)
                    entries = listOf(Entry("1", "DCIM", true, 0), Entry("2", "Music", true, 0), Entry("3", "VID_20261018_220000.mp4", false, 812_000_000))
                    selection = setOf("3"); cursor = "3"
                    grid = false
                }
                render(out, "media-$language-${look.id}-$name-browser", state)
                if (name == "long") render(out, "media-$language-${look.id}-$name-compact", state, width = 740, height = 500)
                state.panel = Panel.NOTIFICATIONS
                render(out, "media-$language-${look.id}-$name-sheet", state)
                val status = trayStatus(state)
                val scene = ImageComposeScene(360, 520, Density(1f)) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(20.dp)) { StatusCard(status, {}, {}, {}) { TrayPhone(state, false) } }
                }
                for (frame in 0 until 10) scene.render(frame * 100_000_000L)
                File(out, "media-$language-${look.id}-$name-tray.png").writeBytes(scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
        }
        Palette.look = Look.DUSK
    }

    /** Notifications, battery and the phone's actions, with texts long enough that every row must shorten. */
    @Test fun phone() {
        val out = File(System.getenv("PREVIEW_DIR") ?: return).apply { mkdirs() }
        val language = java.util.Locale.getDefault().language
        val now = 1_762_300_000_000L
        val notes = listOf(
            PhoneNotification("1", "Messages", "m", "Alexandra Konstantinopoulou-Papadimitriou", "Running about ten minutes late, the train stopped between stations again. Order for me please!", now, true, true, ""),
            PhoneNotification("2", "微信", "w", "家人群（爸爸、妈妈、姐姐和小明）", "妈妈：今天晚上回家吃饭吗？我做了你最喜欢的红烧肉，还有糖醋排骨和清蒸鱼，记得早点回来。", now - 60_000, true, true, ""),
            PhoneNotification("3", "Bank of Somewhere International", "b", "Your verification code", "Use 482913 to sign in. Never share this code with anyone, including our staff.", now - 120_000, false, true, "482913"),
            PhoneNotification("4", "支付宝", "a", "验证码", "您的验证码为 735102，5 分钟内有效，请勿泄露给他人。", now - 180_000, false, false, "735102"),
            PhoneNotification("5", "Calendar", "c", "Quarterly planning review with the extended product and design teams", "Tomorrow 09:30 – 11:00 · Conference room Aurora on the fourth floor", now - 240_000, false, true, ""),
            PhoneNotification("6", "Gmail", "g", "Weekly digest", "Fifteen new messages", now - 300_000, true, true, ""),
            PhoneNotification("7", "Slack", "s", "#general", "Hello", now - 360_000, true, true, ""),
        )
        val client = BridgeClient("127.0.0.1", 1, ByteArray(32), ByteArray(32), Transport.FAST_USB)
        fun phoneState(longName: Boolean) = AppState().apply {
            source = client; transport = Transport.FAST_USB
            deviceName = if (longName) "Galaxy S24 Ultra of Alexandra Konstantinopoulou-Papadimitriou (work phone)" else "vivo X300 Pro"
            phoneInfo = PhoneInfo("1.2.0", 23, deviceName)
            phoneStatus = NotificationList(notes, Battery(14, false))
            entries = listOf(Entry("1", "DCIM", true, 0), Entry("2", "Download", true, 0), Entry("3", "notes.txt", false, 1200))
            saved = listOf(SavedPhone(deviceName, ByteArray(32) { it.toByte() }, "192.168.1.23", 41987, ByteArray(32)))
            grid = false
        }
        for (look in Look.entries) {
            Palette.look = look
            val state = phoneState(longName = false)
            render(out, "phone-$language-${look.id}-browser", state)
            state.panel = Panel.NOTIFICATIONS
            render(out, "phone-$language-${look.id}-notifications", state)
            render(out, "phone-$language-${look.id}-notifications-compact", state, width = 740, height = 500)
            state.panel = Panel.SETTINGS
            render(out, "phone-$language-${look.id}-settings", state, height = 1400)
            for ((name, long) in listOf("tray" to false, "tray-long" to true)) {
                val tray = phoneState(long)
                val status = trayStatus(tray)
                val scene = ImageComposeScene(360, 760, Density(1f)) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(20.dp)) {
                        StatusCard(status, {}, {}, {}) { TrayPhone(tray, dropping = name == "tray-long") }
                    }
                }
                for (frame in 0 until 10) scene.render(frame * 100_000_000L)
                File(out, "phone-$language-${look.id}-$name.png").writeBytes(scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
                println("tooltip ($language, $name): ${status.tooltip()}")
            }
        }
        Palette.look = Look.DUSK
    }
}
