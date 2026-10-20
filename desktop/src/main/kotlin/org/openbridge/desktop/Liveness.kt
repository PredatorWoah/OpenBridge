package org.openbridge.desktop

/**
 * Whether the open phone still answers while nothing else is talking to it. A phone that leaves the Wi‑Fi or its
 * hotspot sends no goodbye, so while connected and idle a cheap request goes out about every [interval] ms;
 * [limit] failures in a row mean it's gone. After a failure the next check comes sooner ([retry]), and a network
 * error anywhere else asks for one at once ([suspect]). Only the decisions live here; times are passed in, so the
 * tests can drive it.
 */
class Liveness(private val interval: Long = 5_000, private val retry: Long = 1_500, private val limit: Int = 2) {
    private var watching: Any? = null
    private var failures = 0
    private var next = Long.MAX_VALUE

    /** Watches `phone` (null: nothing is open), starting over when it's another one: the first check one interval from now. */
    @Synchronized fun watch(phone: Any?, now: Long) {
        if (phone === watching) return
        watching = phone; failures = 0
        next = if (phone == null) Long.MAX_VALUE else now + interval
    }

    /** Something failed on the network: check at the next chance. */
    @Synchronized fun suspect() { if (watching != null) next = Long.MIN_VALUE }

    /** Whether a check is due now. */
    @Synchronized fun due(now: Long): Boolean = watching != null && now >= next

    /** The phone answered (a check, or any other request): the count starts over. */
    @Synchronized fun alive(now: Long) {
        failures = 0
        if (watching != null && next != Long.MIN_VALUE) next = now + interval
    }

    /**
     * Records a check of `phone`: true when the phone is lost (the [limit]th failure in a row). A result for a phone
     * no longer watched counts for nothing.
     */
    @Synchronized fun record(phone: Any, ok: Boolean, now: Long): Boolean {
        if (phone !== watching) return false
        if (ok) { failures = 0; next = now + interval; return false }
        failures++
        if (failures >= limit) { failures = 0; next = now + interval; return true }
        next = now + retry
        return false
    }

    /** Failures in a row so far, for the log and the tests. */
    @Synchronized fun failures() = failures
}

/**
 * How fast a transfer moves: bytes per second over about the last [window] ms, from the running count of bytes
 * moved. A count that goes down (a new queue) starts over; one that stops moving for a while reads as 0.
 */
class SpeedMeter(private val window: Long = 2_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized fun reset() = samples.clear()

    @Synchronized fun add(now: Long, bytes: Long) {
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
        samples.addLast(now to bytes)
        // Keeps one sample from before the window, so the span covers the whole window.
        while (samples.size > 2 && samples[1].first <= now - window) samples.removeFirst()
    }

    /** Bytes per second as of `now`; 0 until there's enough to tell, and after nothing moved for a while. */
    @Synchronized fun rate(now: Long): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first(); val last = samples.last()
        if (now - last.first > 2 * window) return 0.0
        val span = last.first - first.first
        if (span < 200) return 0.0
        return (last.second - first.second) * 1000.0 / span
    }
}

/** Seconds left at `rate` bytes per second, or null when it can't be told yet. */
fun secondsLeft(remaining: Long, rate: Double): Long? = if (rate < 1.0 || remaining < 0) null else Math.ceil(remaining / rate).toLong()
