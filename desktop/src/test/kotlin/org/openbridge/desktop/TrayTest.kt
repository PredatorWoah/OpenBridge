package org.openbridge.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayTest {
    @Test
    fun theTooltipIsOneShortLine() {
        val moving = TrayStatus(true, "Pixel 8", S.wifi, moving = true, fraction = 0.42f, rate = 18.3 * 1024 * 1024)
        assertEquals("OpenBridge · Pixel 8 · 42% · 18.3 MB/s", moving.tooltip())
        assertEquals("OpenBridge · Pixel 8", TrayStatus(true, "Pixel 8", S.wifi).tooltip())
        // No speed until there's enough to tell.
        assertEquals("OpenBridge · Pixel 8 · 0%", moving.copy(fraction = 0f, rate = 0.0).tooltip())
        assertEquals("OpenBridge · ${S.notConnected}", TrayStatus(false, "Pixel 8", S.wifi).tooltip())
        val long = TrayStatus(true, "x".repeat(300), S.wifi, moving = true, fraction = 0.5f, rate = 1e6).tooltip()
        assertTrue(long.length <= 120 && '\n' !in long, long)
        assertTrue(long.endsWith("/s"), long)
    }

    @Test
    fun namesShortenInTheMiddle() {
        assertEquals("abc", shorten("abc", 5))
        assertEquals("ab…yz", shorten("abcdefghijklmnopqrstuvwxyz", 5))
        // Ten pixels a character: the start and the end of the name both stay.
        val fitted = middleFit("IMG_20261019_101500_final.jpg", 150) { it.length * 10 }
        assertEquals("IMG_202…nal.jpg", fitted)
        assertEquals("short.jpg", middleFit("short.jpg", 150) { it.length * 10 })
        assertEquals("…", middleFit("anything", 5) { it.length * 10 })
    }

    @Test
    fun theIconChangesWithTheState() {
        fun pixels(status: TrayStatus) = trayImage(32, status).let { image -> IntArray(32 * 32) { image.getRGB(it % 32, it / 32) } }
        val off = pixels(TrayStatus(false, "", ""))
        val on = pixels(TrayStatus(true, "Pixel 8", S.wifi))
        val moving = pixels(TrayStatus(true, "Pixel 8", S.wifi, moving = true, fraction = 0.3f))
        val further = pixels(TrayStatus(true, "Pixel 8", S.wifi, moving = true, fraction = 0.8f))
        assertFalse(off.contentEquals(on))
        assertFalse(on.contentEquals(moving))
        assertFalse(moving.contentEquals(further))
        // The middle of the outline sun is see-through; the connected one is filled.
        assertEquals(0, off[16 * 32 + 16] ushr 24)
        assertEquals(255, on[16 * 32 + 16] ushr 24)
    }

    @Test
    fun timeLeftReads() {
        if (S.cancel == "Cancel") {   // in English
            assertEquals("42 s left", S.timeLeft(42))
            assertEquals("3 min left", S.timeLeft(125))
            assertEquals("3 h 20 min left", S.timeLeft(3 * 3600 + 1234))
        }
    }
}
