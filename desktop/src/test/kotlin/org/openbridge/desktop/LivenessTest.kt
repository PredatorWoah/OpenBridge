package org.openbridge.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivenessTest {
    private val phone = Any()

    @Test
    fun checksEveryFiveSecondsWhileWatching() {
        val live = Liveness()
        assertFalse(live.due(0))   // nothing open
        live.watch(phone, 1_000)
        assertFalse(live.due(5_999))
        assertTrue(live.due(6_000))
        assertFalse(live.record(phone, true, 6_100))
        assertFalse(live.due(11_000))
        assertTrue(live.due(11_100))
    }

    @Test
    fun twoMissesInARowLoseThePhone() {
        val live = Liveness()
        live.watch(phone, 0)
        assertFalse(live.record(phone, false, 5_000))
        assertEquals(1, live.failures())
        // The second check comes sooner than the usual interval.
        assertTrue(live.due(6_500))
        assertTrue(live.record(phone, false, 6_500))
        assertEquals(0, live.failures())
    }

    @Test
    fun anAnswerInBetweenStartsTheCountOver() {
        val live = Liveness()
        live.watch(phone, 0)
        assertFalse(live.record(phone, false, 5_000))
        assertFalse(live.record(phone, true, 6_500))
        assertFalse(live.record(phone, false, 11_500))
        // Any other request that worked counts as an answer too.
        live.alive(12_000)
        assertFalse(live.record(phone, false, 17_000))
    }

    @Test
    fun aNetworkErrorElsewhereChecksAtOnce() {
        val live = Liveness()
        live.watch(phone, 0)
        assertFalse(live.due(1))
        live.suspect()
        assertTrue(live.due(1))
        // A request that worked meanwhile doesn't cancel the check that was asked for.
        live.alive(2)
        assertTrue(live.due(3))
    }

    @Test
    fun anotherPhoneOrNoneStartsOver() {
        val live = Liveness()
        live.watch(phone, 0)
        assertFalse(live.record(phone, false, 5_000))
        val other = Any()
        live.watch(other, 6_000)
        assertEquals(0, live.failures())
        // A late result for the phone that was open before counts for nothing.
        assertFalse(live.record(phone, false, 6_100))
        assertFalse(live.record(phone, false, 6_200))
        assertEquals(0, live.failures())
        live.watch(null, 7_000)
        live.suspect()
        assertFalse(live.due(Long.MAX_VALUE - 1))
    }

    @Test
    fun speedIsAveragedOverAboutTwoSeconds() {
        val meter = SpeedMeter()
        assertEquals(0.0, meter.rate(0))
        meter.add(0, 0)
        assertEquals(0.0, meter.rate(0))
        // 10 MB/s for three seconds, sampled every 100 ms.
        for (step in 1..30) meter.add(step * 100L, step * 1_000_000L)
        assertEquals(10_000_000.0, meter.rate(3_000), 1.0)
        // Then twice as fast: after two more seconds the old pace has gone from the average.
        for (step in 1..20) meter.add(3_000 + step * 100L, 30_000_000L + step * 2_000_000L)
        assertEquals(20_000_000.0, meter.rate(5_000), 1_000_000.0)
        // Nothing moves for a while: 0.
        assertEquals(0.0, meter.rate(10_000))
        // A new queue counts from 0 again.
        meter.add(10_000, 0)
        assertEquals(0.0, meter.rate(10_000))
    }

    @Test
    fun timeLeft() {
        assertEquals(10L, secondsLeft(100_000_000, 10_000_000.0))
        assertEquals(1L, secondsLeft(1, 10_000_000.0))
        assertNull(secondsLeft(100, 0.0))
    }
}
