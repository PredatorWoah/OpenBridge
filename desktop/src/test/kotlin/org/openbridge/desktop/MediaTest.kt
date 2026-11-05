package org.openbridge.desktop

import org.openbridge.desktop.FakePhone.Companion.battery
import org.openbridge.desktop.FakePhone.Companion.bytes
import org.openbridge.desktop.FakePhone.Companion.media
import org.openbridge.desktop.FakePhone.Companion.reply
import org.openbridge.desktop.FakePhone.Companion.reset
import org.openbridge.protocol.Wire
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MEDIA events and MEDIA CONTROL (operation 24) against a phone that speaks them as docs/protocol.md says. */
class MediaTest {
    private val phone = FakePhone()

    @AfterTest fun stop() = phone.close()

    private fun read(bytes: ByteArray) = PhoneEvents.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun readsWhatPlays() {
        phone.eventReplies += reply(5, reset, battery(50, false), media("Spotify", "Bohemian Rhapsody", "Queen", 354_000, 61_000, 1, 15))
        val events = phone.client().events(0, 0).events
        val playing = (events[2] as PhoneEvent.MediaChanged).media
        assertEquals("Spotify", playing.app); assertEquals("Bohemian Rhapsody", playing.title); assertEquals("Queen", playing.artist)
        assertEquals(354_000, playing.duration); assertEquals(61_000, playing.position)
        assertTrue(playing.playing && playing.canPlayPause && playing.canNext && playing.canPrevious && playing.canSeek)
        val list = NotificationList().apply(events).list
        assertEquals("Bohemian Rhapsody", list.media?.title)
        // Nothing playing any more: the card goes; a RESET forgets it too.
        assertNull(list.apply(listOf(PhoneEvent.MediaChanged(playing.copy(state = PhoneMedia.State.NONE)))).list.media)
        assertNull(list.apply(listOf(PhoneEvent.Reset)).list.media)
    }

    @Test
    fun unknownTimesAndLimits() {
        val radio = (read(reply(1, media("Radio", "Live", "", -1, -1, 2, 1))).events.single() as PhoneEvent.MediaChanged).media
        assertEquals(-1, radio.positionAt(10_000)); assertFalse(radio.canSeek); assertFalse(radio.playing)
        assertFailsWith<BridgeException> { read(reply(1, media("A", "T", "", -2, 0, 1, 0))) }
        assertFailsWith<BridgeException> { read(reply(1, media("A", "T", "", 10, -5, 1, 0))) }
        assertFailsWith<BridgeException> { read(reply(1, media("A", "T", "", 10, 0, 3, 0))) }
        assertFailsWith<BridgeException> { read(reply(1, media("A".repeat(129), "T", "", 10, 0, 1, 0))) }
        // Cut short.
        assertFailsWith<BridgeException> { read(reply(1, 4 to bytes { Wire.writeString(this, "A"); Wire.writeString(this, "T") })) }
        // Seek needs a known duration.
        assertFalse(PhoneMedia("A", "T", "", -1, 5, PhoneMedia.State.PLAYING, 8).canSeek)
    }

    @Test
    fun thePositionAdvancesWhilePlaying() {
        val song = PhoneMedia("A", "T", "R", 200_000, 10_000, PhoneMedia.State.PLAYING, 15, at = 1_000)
        assertEquals(15_000, song.positionAt(6_000))
        assertEquals(200_000, song.positionAt(999_999))   // never past the end
        val paused = song.withState(false, now = 6_000)
        assertEquals(15_000, paused.positionAt(60_000))
        assertEquals(15_000, paused.withState(true, now = 60_000).positionAt(60_000))
        assertEquals(17_000, paused.withState(true, now = 60_000).positionAt(62_000))
        assertEquals(200_000, song.seekedTo(500_000, now = 0).position)
        assertEquals("3:07", clock(187_000)); assertEquals("1:02:03", clock(3_723_000)); assertEquals("0:00", clock(0))
    }

    @Test
    fun controlsWhatPlays() {
        val client = phone.client()
        client.mediaControl(MediaAction.PLAY_PAUSE)
        client.mediaControl(MediaAction.NEXT)
        client.mediaControl(MediaAction.PREVIOUS)
        client.mediaControl(MediaAction.PLAY); client.mediaControl(MediaAction.PAUSE)
        client.mediaControl(MediaAction.SEEK, 93_500)
        assertEquals(listOf("media 3", "media 4", "media 5", "media 1", "media 2", "media 6 93500"), phone.received)
        phone.nothingPlaying = true
        assertEquals("Nothing is playing.", assertFailsWith<BridgeException> { client.mediaControl(MediaAction.NEXT) }.message)
    }

    @Test
    fun anOlderPhoneSaysToUpdate() {
        FakePhone(revision = 23).use { old ->
            assertTrue(assertFailsWith<BridgeException> { old.client().mediaControl(MediaAction.PLAY_PAUSE) }.unsupported)
            assertEquals(23, old.client().info().revision)
        }
    }
}
