@file:Suppress("FunctionName", "LocalVariableName")

package org.openbridge.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/*
 * Linux: what plays on the phone, as a media player on the session bus (MPRIS, org.mpris.MediaPlayer2.openbridge),
 * so the desktop's media keys, its sound menu and widgets show it and control the phone. The player is there only
 * while something plays on a connected phone that allows media controls.
 */

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(Offset: Long)
    fun SetPosition(TrackId: DBusPath, Position: Long)
    fun OpenUri(Uri: String)

    /** The position jumped (a seek on the phone or here), in microseconds. */
    class Seeked(path: String, val Position: Long) : DBusSignal(path, Position)
}

/** What the bus sees, from a playing phone: properties in MPRIS's names and units (microseconds). */
object MprisProperties {
    const val ROOT = "org.mpris.MediaPlayer2"
    const val PLAYER = "org.mpris.MediaPlayer2.Player"

    fun root(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(false), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
        "Identity" to Variant("OpenBridge"), "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
        "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
    )

    /** The track's ID changes with what plays, so widgets know a new one started. */
    fun trackId(media: PhoneMedia) = DBusPath("/org/openbridge/track/" + Integer.toHexString((media.app + "\u0000" + media.title + "\u0000" + media.artist).hashCode()))

    fun metadata(media: PhoneMedia): Map<String, Variant<*>> = buildMap {
        put("mpris:trackid", Variant(trackId(media)))
        put("xesam:title", Variant(media.title.ifBlank { media.app }))
        if (media.artist.isNotBlank()) put("xesam:artist", Variant(arrayOf(media.artist), "as"))
        if (media.app.isNotBlank()) put("xesam:album", Variant(media.app))
        if (media.duration > 0) put("mpris:length", Variant(media.duration * 1000))
    }

    fun status(media: PhoneMedia) = when (media.state) { PhoneMedia.State.PLAYING -> "Playing"; PhoneMedia.State.PAUSED -> "Paused"; PhoneMedia.State.NONE -> "Stopped" }

    fun player(media: PhoneMedia, now: Long = System.currentTimeMillis()): Map<String, Variant<*>> = mapOf(
        "PlaybackStatus" to Variant(status(media)),
        "Rate" to Variant(1.0), "MinimumRate" to Variant(1.0), "MaximumRate" to Variant(1.0), "Volume" to Variant(1.0),
        "Metadata" to Variant(metadata(media), "a{sv}"),
        "Position" to Variant(media.positionAt(now).coerceAtLeast(0) * 1000),
        "CanGoNext" to Variant(media.canNext), "CanGoPrevious" to Variant(media.canPrevious),
        "CanPlay" to Variant(media.canPlayPause), "CanPause" to Variant(media.canPlayPause),
        "CanSeek" to Variant(media.canSeek), "CanControl" to Variant(true),
    )
}

/**
 * The player on the bus. `media` is read twice a second; `act` controls the phone (it runs on the UI thread);
 * `raise` brings the window to the front. Anything that fails (no session bus) leaves it quietly off.
 */
class Mpris(private val media: () -> PhoneMedia?, private val act: (MediaAction, Long) -> Unit, private val raise: () -> Unit,
            /** Another bus than the session's, for tests. */ private val address: String? = null) {
    companion object {
        const val NAME = "org.mpris.MediaPlayer2.openbridge"
        const val PATH = "/org/mpris/MediaPlayer2"
    }

    private var connection: DBusConnection? = null
    @Volatile private var shown: PhoneMedia? = null

    private inner class Exported : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath() = PATH
        override fun Raise() = raise()
        override fun Quit() {}
        override fun Next() = act(MediaAction.NEXT, 0)
        override fun Previous() = act(MediaAction.PREVIOUS, 0)
        override fun Pause() = act(MediaAction.PAUSE, 0)
        override fun PlayPause() = act(MediaAction.PLAY_PAUSE, 0)
        override fun Stop() = act(MediaAction.PAUSE, 0)
        override fun Play() = act(MediaAction.PLAY, 0)
        override fun Seek(Offset: Long) {
            val now = shown ?: return
            if (now.canSeek) act(MediaAction.SEEK, (now.positionAt(System.currentTimeMillis()) + Offset / 1000).coerceIn(0, now.duration))
        }
        override fun SetPosition(TrackId: DBusPath, Position: Long) {
            val now = shown ?: return
            if (now.canSeek && TrackId.path == MprisProperties.trackId(now).path && Position / 1000 in 0..now.duration) act(MediaAction.SEEK, Position / 1000)
        }
        override fun OpenUri(Uri: String) {}

        // The typed Variant itself, so a map or a list of strings keeps its D-Bus type.
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = (GetAll(interfaceName)[propertyName]
            ?: throw org.freedesktop.dbus.exceptions.DBusExecutionException("No such property: $propertyName")) as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            MprisProperties.ROOT -> MprisProperties.root()
            MprisProperties.PLAYER -> shown?.let { MprisProperties.player(it) } ?: emptyMap()
            else -> emptyMap()
        }
    }

    /** Watches what plays until the app ends. Linux only; does nothing elsewhere. */
    fun start() {
        if (Platform.windows || System.getProperty("os.name").startsWith("Mac")) return
        Thread({
            while (true) {
                runCatching { sync(media()) }.onFailure { Log.add("Media keys (MPRIS): ${it.message ?: it.javaClass.simpleName}"); close(); Thread.sleep(30_000) }
                Thread.sleep(500)
            }
        }, "openbridge-mpris").apply { isDaemon = true }.start()
    }

    /** Shows `now` on the bus (or takes the player away when nothing plays), telling listeners what changed. */
    @Synchronized fun sync(now: PhoneMedia?) {
        val before = shown
        if (now == before) return
        if (now == null) { shown = null; close(); return }
        shown = now
        val bus = connection ?: open()
        if (before == null) return   // just appeared: listeners read everything themselves
        // Position is never announced: listeners ask, or follow Seeked.
        val changed = MprisProperties.player(now) - "Position"
        bus.sendMessage(Properties.PropertiesChanged(PATH, MprisProperties.PLAYER, changed, emptyList()))
        // A jump in position the playing alone doesn't explain: a seek.
        val expected = before.positionAt(now.at)
        if (now.position >= 0 && expected >= 0 && kotlin.math.abs(now.position - expected) > 1_500)
            bus.sendMessage(MprisPlayer.Seeked(PATH, now.position * 1000))
    }

    private fun open(): DBusConnection {
        val bus = (if (address != null) DBusConnectionBuilder.forAddress(address) else DBusConnectionBuilder.forSessionBus()).withShared(false).build()
        bus.exportObject(PATH, Exported())
        bus.requestBusName(NAME)
        connection = bus
        Log.add("Media keys: the phone's player is on the session bus")
        return bus
    }

    @Synchronized fun close() {
        val bus = connection ?: return
        connection = null
        runCatching { bus.releaseBusName(NAME) }
        runCatching { bus.close() }
    }
}
