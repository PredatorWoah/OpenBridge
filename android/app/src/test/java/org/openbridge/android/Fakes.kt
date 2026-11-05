package org.openbridge.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.openbridge.protocol.Outbox
import org.openbridge.ui.Look
import java.util.Calendar

/** A fixed moment of the app for the screenshots: every value is plain data. */
data class FakeModel(
    override val share: ShareUi = ShareUi(),
    override val pairing: PairingUi? = null,
    override val outbox: OutboxUi = OutboxUi(),
    override val texts: List<Texts.Entry> = emptyList(),
    override val trusted: List<Computer> = emptyList(),
    override val mirror: MirrorUi = MirrorUi(),
    override val hotspot: HotspotUi = HotspotUi(),
    override val update: UpdateUi = UpdateUi(),
    override val events: List<Feed.Event> = emptyList(),
    override val collecting: Float = 0f,
    override val now: Long = Fixtures.now,
    override val version: String = "1.1.1",
    override val look: Look = Look.DUSK,
) : AppModel

/** Does nothing; thumbnails are painted. */
object FakeActions : Actions {
    override fun toggleSharing() {}
    override fun chooseWholePhone() {}
    override fun chooseFolder() {}
    override fun keepInBackground(on: Boolean) {}
    override fun hotspot(on: Boolean) {}
    override fun hotspotSettings() {}
    override fun update() {}
    override fun checkForUpdates() {}
    override fun open(url: String) {}
    override fun copy(text: String, sensitive: Boolean) {}
    override fun sendClipboard() {}
    override fun sendText(text: String) = true
    override fun sendFiles() {}
    override fun clearOutbox() {}
    override fun clearTexts() {}
    override fun copyDiagnostics() {}
    override fun notificationAccess() {}
    override fun mirror(on: Boolean) {}
    override fun showApp(packageName: String, shown: Boolean) {}
    override fun forget(id: String) {}
    override fun decidePairing(approve: Boolean) {}
    override fun dismissNote() {}
    override fun chooseLook(look: Look) {}
    override fun removeEvent(id: Long) {}
    override fun clearEvents() {}
    override fun openEvent(event: Feed.Event) {}
    override fun appIcon(packageName: String): ImageBitmap? = null
    override suspend fun thumbnail(location: String): ImageBitmap = photo(location.hashCode())

    /** A little painted "photo": a sky, a sun and hills, coloured by [seed]. */
    private fun photo(seed: Int): ImageBitmap {
        val image = ImageBitmap(160, 160)
        val canvas = androidx.compose.ui.graphics.Canvas(image)
        val warm = seed % 2 == 0
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(160f, 160f)) {
            drawRect(Brush.verticalGradient(if (warm) listOf(Color(0xFFFF8A65), Color(0xFFFFD180)) else listOf(Color(0xFF4FC3F7), Color(0xFFB3E5FC))))
            drawCircle(Color(0xFFFFF8E1), 22f, Offset(110f, 55f))
            drawCircle(if (warm) Color(0xFF6D4C41) else Color(0xFF2E7D32), 120f, Offset(40f, 230f))
            drawCircle(if (warm) Color(0xFF8D6E63) else Color(0xFF43A047), 110f, Offset(150f, 250f))
        }
        return image
    }
}

/** The people, computers, files and texts in the pictures. */
object Fixtures {
    val now: Long = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 5, 14, 30, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    const val MAC = "Maya's MacBook Pro"

    val endpoints = listOf(Endpoint(Via.WIFI, "192.168.1.42", 41986), Endpoint(Via.USB, "127.0.0.1", 41987))

    val trusted = listOf(
        Computer("1", MAC, now - 40 * DAY, now - 5 * MIN),
        Computer("2", "Studio iMac", now - 90 * DAY, now - 3 * DAY),
        Computer("3", "DESKTOP-7Q2LK (Windows)", now - 20 * DAY, now - 12 * DAY))

    val events = listOf(
        Feed.Event(12, Feed.Kind.FILE_RECEIVED, "Quarterly report.pdf", MAC, "/storage/emulated/0/Download/OpenBridge/Quarterly report.pdf", now - 3 * MIN),
        Feed.Event(11, Feed.Kind.FILE_SENT, "IMG_2048.jpg", MAC, "/storage/emulated/0/DCIM/Camera/IMG_2048.jpg", now - 12 * MIN),
        Feed.Event(10, Feed.Kind.TEXT_RECEIVED, "The studio Wi‑Fi password is lighthouse-42", MAC, null, now - 26 * MIN),
        Feed.Event(9, Feed.Kind.LINK, "https://github.com/PredatorWoah/OpenBridge/releases", MAC, null, now - 70 * MIN),
        Feed.Event(8, Feed.Kind.CONNECTED, "WIFI", MAC, null, now - 2 * HOUR - 5 * MIN),
        Feed.Event(7, Feed.Kind.FILE_RECEIVED, "Sunset over the bay.heic", "Studio iMac", "/storage/emulated/0/Download/OpenBridge/Sunset over the bay.heic", now - DAY - 2 * HOUR),
        Feed.Event(6, Feed.Kind.RING, "", MAC, null, now - DAY - 4 * HOUR),
        Feed.Event(5, Feed.Kind.PAIRED, "", "Studio iMac", null, now - DAY - 5 * HOUR),
        Feed.Event(4, Feed.Kind.FILE_SENT, "Holiday.mp4", "Studio iMac", "/storage/emulated/0/DCIM/Camera/Holiday.mp4", now - DAY - 6 * HOUR),
        Feed.Event(3, Feed.Kind.TEXT_SENT, "Running 10 minutes late, start without me", null, null, now - 3 * DAY),
    )

    val texts = listOf(
        Texts.Entry("Thanks! Got it 🙌", false, null, now - 2 * MIN),
        Texts.Entry("The studio Wi‑Fi password is lighthouse-42", true, MAC, now - 26 * MIN),
        Texts.Entry("What's the Wi‑Fi password at the studio?", false, null, now - 28 * MIN),
        Texts.Entry("https://maps.app.goo.gl/k2Lr7pQ", true, MAC, now - 3 * HOUR),
        Texts.Entry("Order #4471-AB, pick up after 5", false, null, now - DAY),
    )

    val apps = listOf("Bank", "Calendar", "Gmail", "Instagram", "Messages", "Slack", "Spotify", "Telegram", "WhatsApp")
        .map { AppEntry("com.example.${it.lowercase()}", it) }

    val outbox = OutboxUi(listOf(
        PhoneOutbox.Item("a", "Presentation.key", 48_200_000),
        PhoneOutbox.Item("b", "Text", 120, Outbox.TEXT),
        PhoneOutbox.Item("c", "Scan 2026-11-05.pdf", 2_400_000)), mapOf("a" to 0.62f))

    fun model(look: Look) = FakeModel(trusted = trusted, events = events, texts = texts, look = look,
        mirror = MirrorUi(enabled = true, access = true, excluded = setOf("com.example.bank", "com.example.instagram"), apps = apps))
}
