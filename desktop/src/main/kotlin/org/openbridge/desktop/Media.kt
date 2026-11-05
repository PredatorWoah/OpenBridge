package org.openbridge.desktop

/** What the phone plays, as EVENTS describes it (kind 4 MEDIA, from 1.3.0). Times in milliseconds, -1 when unknown. */
data class PhoneMedia(
    val app: String, val title: String, val artist: String, val duration: Long, val position: Long,
    val state: State, val actions: Int,
    /** When this computer got it, to advance the position while it plays. */
    val at: Long = System.currentTimeMillis(),
) {
    enum class State { NONE, PLAYING, PAUSED }

    val playing get() = state == State.PLAYING
    val canPlayPause get() = actions and 1 != 0
    val canNext get() = actions and 2 != 0
    val canPrevious get() = actions and 4 != 0
    /** Seeking needs a known duration too, to know where on the bar is where. */
    val canSeek get() = actions and 8 != 0 && duration > 0
    /** Something to show: the phone said something plays or is paused. */
    val shown get() = state != State.NONE

    /** The position now: advancing from when it arrived while it plays, never past the end. -1 when unknown. */
    fun positionAt(now: Long): Long {
        if (position < 0) return -1
        val moved = if (playing) position + (now - at).coerceAtLeast(0) else position
        return if (duration > 0) moved.coerceAtMost(duration) else moved
    }

    /** As it is now, with `state` (play and pause on this computer, before the phone confirms). */
    fun withState(playing: Boolean, now: Long = System.currentTimeMillis()) =
        copy(position = positionAt(now), at = now, state = if (playing) State.PLAYING else State.PAUSED)

    fun seekedTo(position: Long, now: Long = System.currentTimeMillis()) = copy(position = position.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE), at = now)
}

/** MEDIA CONTROL's actions (operation 24). */
enum class MediaAction(val code: Int) { PLAY(1), PAUSE(2), PLAY_PAUSE(3), NEXT(4), PREVIOUS(5), SEEK(6) }

/** "3:07", or "1:02:03" past an hour. */
fun clock(millis: Long): String {
    if (millis < 0) return "–:––"
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
