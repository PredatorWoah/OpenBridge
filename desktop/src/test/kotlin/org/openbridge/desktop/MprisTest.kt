package org.openbridge.desktop

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's player on D-Bus (MPRIS), on a private bus of its own, asked with dbus-send as a desktop's media keys
 * and widgets would. Skipped where there's no dbus-daemon (Windows).
 */
class MprisTest {
    private fun tool(name: String) = listOf("/usr/bin", "/bin").map { File(it, name) }.firstOrNull { it.canExecute() }

    @Test
    fun showsAndControlsWhatPlays() {
        val daemon = tool("dbus-daemon") ?: return
        val send = tool("dbus-send") ?: return
        val bus = ProcessBuilder(daemon.path, "--session", "--nofork", "--print-address=1").start()
        try {
            val address = bus.inputStream.bufferedReader().readLine().trim()
            val actions = CopyOnWriteArrayList<String>()
            val mpris = Mpris({ null }, { action, at -> actions += "$action $at" }, { actions += "raise" }, address)
            val song = PhoneMedia("Spotify", "Bohemian Rhapsody", "Queen", 354_000, 61_000, PhoneMedia.State.PLAYING, 15)
            mpris.sync(song)
            fun call(vararg args: String): String {
                val process = ProcessBuilder(listOf(send.path, "--bus=$address", "--print-reply", "--dest=${Mpris.NAME}", Mpris.PATH) + args).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor(10, TimeUnit.SECONDS)
                return text
            }
            val status = call("org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2.Player", "string:PlaybackStatus")
            assertTrue("\"Playing\"" in status, status)
            val metadata = call("org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2.Player", "string:Metadata")
            assertTrue("Bohemian Rhapsody" in metadata && "Queen" in metadata && "354000000" in metadata, metadata)
            assertTrue("OpenBridge" in call("org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2", "string:Identity"))
            val all = call("org.freedesktop.DBus.Properties.GetAll", "string:org.mpris.MediaPlayer2.Player")
            assertTrue("CanGoNext" in all && "Position" in all, all)
            call("org.mpris.MediaPlayer2.Player.PlayPause")
            call("org.mpris.MediaPlayer2.Player.Next")
            call("org.mpris.MediaPlayer2.Player.SetPosition", "objpath:${MprisProperties.trackId(song).path}", "int64:90000000")
            call("org.mpris.MediaPlayer2.Raise")
            assertEquals(listOf("PLAY_PAUSE 0", "NEXT 0", "SEEK 90000", "raise"), actions)
            // Paused on the phone: the change reaches the bus; nothing playing: the player goes away.
            mpris.sync(song.withState(false))
            assertTrue("\"Paused\"" in call("org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2.Player", "string:PlaybackStatus"))
            mpris.sync(null)
            assertTrue("\"Paused\"" !in call("org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2.Player", "string:PlaybackStatus"))
        } finally { bus.destroy() }
    }
}
