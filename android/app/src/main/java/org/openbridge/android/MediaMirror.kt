package org.openbridge.android

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openbridge.protocol.Companion
import org.openbridge.protocol.EventLog
import java.io.IOException
import kotlin.math.abs

/**
 * Media controls on computers: what's playing on the phone (from Android's media sessions, which the notification
 * listener may read) goes to paired computers as MEDIA events, and they can play, pause, skip and seek (MEDIA CONTROL).
 * Only while Notifications on computers is on (with its access) and the switch for media, on by default, is on.
 * The playing app's name, the title and the artist go only into the in-memory event log, never into diagnostics.
 */
object MediaMirror {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    /** The user's switch, remembered; on unless turned off. */
    var enabled by mutableStateOf(true); private set
    /** Sessions are being followed (notifications on, access, the switch on, and the listener connected). */
    @Volatile var active = false; private set
    private var manager: MediaSessionManager? = null
    private val followed = HashMap<MediaSession.Token, MediaController.Callback>()
    /** The session whose state computers see and control, or null when nothing plays. */
    @Volatile private var current: MediaController? = null
    /** What was last sent: the session's token, title, and where its position should be by now. */
    private var sentToken: Any? = null
    private var sentTitle: String? = null
    private var sentPosition = -1L
    private var sentAt = 0L
    private var sentSpeed = 1f
    private var sentPlaying = false

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        enabled = app.getSharedPreferences("mirror", Context.MODE_PRIVATE).getBoolean("media", true)
    }

    fun enable(on: Boolean) {
        if (on == enabled) return
        enabled = on
        app.getSharedPreferences("mirror", Context.MODE_PRIVATE).edit().putBoolean("media", on).apply()
        Log.add("Media controls on computers turned ${if (on) "on" else "off"}")
        refresh()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> follow(controllers.orEmpty()) }

    /** Starts or stops following sessions to match the switches and the listener. On the main thread. */
    fun refresh() {
        if (!::app.isInitialized) return
        val listener = Mirror.listener
        val want = enabled && Mirror.enabled && listener != null
        if (want == active) { if (want) choose() ; return }
        if (want) {
            val sessions = app.getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(app, NotificationMirror::class.java)
            try {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
                manager = sessions
                active = true
                follow(sessions.getActiveSessions(component).orEmpty())
                Log.add("Following media sessions for computers")
            } catch (e: SecurityException) {
                Log.add("Media sessions not available: ${e.javaClass.simpleName}")
                stopFollowing()
            }
        } else stopFollowing()
    }

    private fun stopFollowing() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        manager = null
        for ((token, callback) in followed) runCatching { controllers[token]?.unregisterCallback(callback) }
        controllers.clear()
        followed.clear()
        val wasActive = active
        active = false
        current = null
        sentToken = null
        // Computers forget what was playing.
        PhoneCompanion.log.media("", "", "", -1, -1, EventLog.NOTHING, 0, false)
        if (wasActive) Log.add("Stopped following media sessions")
    }

    private val controllers = HashMap<MediaSession.Token, MediaController>()

    /** Follows exactly these sessions (Android lists the most recently active first), then picks one. */
    private fun follow(list: List<MediaController>) {
        if (!active) return
        val tokens = list.map { it.sessionToken }.toSet()
        for (token in followed.keys.toList()) if (token !in tokens) {
            runCatching { controllers[token]?.unregisterCallback(followed.getValue(token)) }
            followed.remove(token); controllers.remove(token)
        }
        order = list.map { it.sessionToken }
        for (controller in list) {
            val token = controller.sessionToken
            if (token in followed) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
                override fun onMetadataChanged(metadata: MediaMetadata?) = choose()
                override fun onSessionDestroyed() { followed.remove(token); controllers.remove(token); choose() }
            }
            runCatching { controller.registerCallback(callback, main) }.onSuccess { followed[token] = callback; controllers[token] = controller }
        }
        choose()
    }

    /** Android's order of the sessions, most recently active first. */
    private var order: List<MediaSession.Token> = emptyList()

    private fun playing(state: Int) = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_CONNECTING -> EventLog.PLAYING
        PlaybackState.STATE_PAUSED -> EventLog.PAUSED
        else -> EventLog.NOTHING
    }

    /**
     * The session computers see: the most recent one that is playing, else the most recent paused one with something
     * loaded. Sends a MEDIA event when what's playing changed, or the position jumped (a seek). On the main thread.
     */
    private fun choose() {
        if (!active) return
        val sessions = order.mapNotNull { controllers[it] }
        val pick = sessions.firstOrNull { playing(it.playbackState?.state ?: 0) == EventLog.PLAYING }
            ?: sessions.firstOrNull { playing(it.playbackState?.state ?: 0) == EventLog.PAUSED && it.metadata != null }
        current = pick
        if (pick == null) {
            sentToken = null
            PhoneCompanion.log.media("", "", "", -1, -1, EventLog.NOTHING, 0, false)
            return
        }
        val state = pick.playbackState
        val metadata = pick.metadata
        val wire = playing(state?.state ?: 0)
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1L
        val now = SystemClock.elapsedRealtime()
        val position = state?.let { position(it, now) } ?: -1L
        val offered = state?.actions ?: 0L
        var actions = 0
        if (offered and (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) != 0L) actions = actions or EventLog.CAN_PLAY_PAUSE
        if (offered and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) actions = actions or EventLog.CAN_NEXT
        if (offered and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) actions = actions or EventLog.CAN_PREVIOUS
        if (offered and PlaybackState.ACTION_SEEK_TO != 0L && duration > 0) actions = actions or EventLog.CAN_SEEK
        // A seek: the same item, but the position isn't where it would have moved to by itself.
        val expected = if (sentPlaying) sentPosition + ((now - sentAt) * sentSpeed).toLong() else sentPosition
        val seeked = sentToken == pick.sessionToken && sentTitle == title && position >= 0 && sentPosition >= 0 && abs(position - expected) > 2_000
        PhoneCompanion.log.media(Mirror.label(pick.packageName), title, artist, duration, position, wire, actions, seeked)
        sentToken = pick.sessionToken; sentTitle = title; sentPosition = position; sentAt = now
        sentSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        sentPlaying = wire == EventLog.PLAYING
    }

    /** Where the session is now: its last reported position, moved on by the time since at its speed while playing. */
    private fun position(state: PlaybackState, now: Long): Long {
        val reported = state.position
        if (reported < 0) return -1
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0) return reported
        return reported + ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong().coerceAtLeast(0)
    }

    /** MEDIA CONTROL, on a server thread. */
    fun control(action: Int, position: Long) {
        if (!enabled || !Mirror.active) throw IOException("Media controls on computers are off on the phone")
        val controller = current ?: throw IOException("Nothing is playing on the phone")
        val offered = controller.playbackState?.actions ?: 0L
        fun require(vararg flags: Long) {
            if (flags.none { offered and it != 0L }) throw IOException("The playing app doesn't offer that")
        }
        val controls = controller.transportControls
        val playingNow = playing(controller.playbackState?.state ?: 0) == EventLog.PLAYING
        when (action) {
            Companion.PLAY -> { require(PlaybackState.ACTION_PLAY, PlaybackState.ACTION_PLAY_PAUSE); controls.play() }
            Companion.PAUSE -> { require(PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY_PAUSE); controls.pause() }
            Companion.PLAY_PAUSE -> {
                require(PlaybackState.ACTION_PLAY_PAUSE, PlaybackState.ACTION_PLAY, PlaybackState.ACTION_PAUSE)
                if (playingNow) controls.pause() else controls.play()
            }
            Companion.NEXT -> { require(PlaybackState.ACTION_SKIP_TO_NEXT); controls.skipToNext() }
            Companion.PREVIOUS -> { require(PlaybackState.ACTION_SKIP_TO_PREVIOUS); controls.skipToPrevious() }
            Companion.SEEK -> {
                require(PlaybackState.ACTION_SEEK_TO)
                val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
                controls.seekTo(if (duration != null) position.coerceAtMost(duration) else position)
            }
            else -> throw IOException("Invalid media action")
        }
        Log.add("A computer controlled what's playing")
    }
}
