package org.openbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.openbridge.protocol.Companion
import org.openbridge.protocol.EventLog
import java.io.File

/**
 * What the phone does for paired computers beyond files (operations 19 to 23): its events, answering notifications,
 * ringing, links and received files. One for the whole app process, so the event log's cursor stays valid across
 * sharing sessions and a computer starts over (RESET) only when the app restarted.
 */
object PhoneCompanion : Companion {
    /** The phone's notifications (when mirrored) and battery, in memory only. */
    val log = EventLog()
    private lateinit var app: Context

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = battery(intent)
    }

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Mirror.attach(app)
        battery(ContextCompat.registerReceiver(app, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))
    }

    /** Sent to computers only when the level or charging changes. */
    private fun battery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        log.battery(level * 100 / scale, charging)
    }

    override fun events(cursor: Long, millis: Long): EventLog.Batch = log.read(cursor, millis)

    override fun dismiss(key: String) = Mirror.dismiss(key)

    override fun reply(key: String, text: String) = Mirror.reply(key, text)

    override fun ring(on: Boolean) { if (on) Ringer.start(app) else Ringer.stop(app) }

    override fun openUrl(url: String, computer: String) = Alerts.link(app, url, computer)

    override fun received(name: String, location: String?, computer: String) = Alerts.received(app, name, location, computer)
}

/** Notifications for ringing, links and files from computers, and their channels. */
object Alerts {
    private const val RING_CHANNEL = "ring"
    private const val LINKS_CHANNEL = "links"
    private const val RECEIVED_CHANNEL = "received"
    private const val RECEIVED_GROUP = "org.openbridge.android.RECEIVED"
    const val RING_ID = 10
    private const val RECEIVED_SUMMARY_ID = 11
    /** Links and files get their own IDs from here on, so several stay side by side. */
    private var next = 100
    /** Files received since the summary was last cleared, newest last (names only, in memory). */
    private val received = ArrayList<String>()

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Ringing plays its own alarm sound, so the channel itself is silent.
        manager.createNotificationChannel(NotificationChannel(RING_CHANNEL, S.channelRing, NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        manager.createNotificationChannel(NotificationChannel(LINKS_CHANNEL, S.channelLinks, NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(RECEIVED_CHANNEL, S.channelReceived, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try { NotificationManagerCompat.from(context).notify(id, notification) } catch (_: SecurityException) { /* notifications are off */ }
    }

    fun ringing(context: Context): Notification {
        channels(context)
        val stop = PendingIntent.getBroadcast(context, 1, Intent(context, AlertReceiver::class.java).setAction(AlertReceiver.STOP_RING), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(S.ringTitle)
            .setContentText(S.ringText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setDeleteIntent(stop)
            .addAction(0, S.stop, stop)
            .setTimeoutAfter(Ringer.MAX_MILLIS + 5_000)
            .build()
    }

    fun showRinging(context: Context) = notify(context, RING_ID, ringing(context))

    fun clearRinging(context: Context) = NotificationManagerCompat.from(context).cancel(RING_ID)

    /** A link from a computer: Android doesn't let an app open a page by itself in the background, so the user taps it. */
    fun link(context: Context, url: String, computer: String) {
        channels(context)
        val uri = Uri.parse(url)
        val host = uri.host ?: url
        val id = synchronized(this) { next++ }
        val open = PendingIntent.getActivity(context, id, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        notify(context, id, NotificationCompat.Builder(context, LINKS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(S.linkFrom(computer))
            .setContentText(host)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$host\n$url"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        Log.add("A computer sent a link")
    }

    /** A file a computer sent: tapping opens it in an app that can, with read access to just that file. */
    fun received(context: Context, name: String, location: String?, computer: String) {
        channels(context)
        val uri = location?.let { place ->
            if (place.startsWith("content://")) Uri.parse(place)
            else runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(place)) }.getOrNull()
        }
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val id = synchronized(this) { next++ }
        val builder = NotificationCompat.Builder(context, RECEIVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(S.receivedFrom(name, computer))
            .setContentText(if (location?.startsWith("content://") == true) S.savedInFolder else S.savedInDownloads)
            .setGroup(RECEIVED_GROUP)
            .setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(PendingIntent.getActivity(context, id, Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PendingIntent.FLAG_IMMUTABLE))
        }
        notify(context, id, builder.build())
        // Several files: Android bundles them under a summary.
        val names = synchronized(this) {
            received += name
            while (received.size > 50) received.removeAt(0)
            received.toList()
        }
        if (names.size >= 2) {
            val inbox = NotificationCompat.InboxStyle()
            names.takeLast(5).reversed().forEach { inbox.addLine(it) }
            notify(context, RECEIVED_SUMMARY_ID, NotificationCompat.Builder(context, RECEIVED_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(S.receivedCount(names.size))
                .setStyle(inbox)
                .setGroup(RECEIVED_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setDeleteIntent(PendingIntent.getBroadcast(context, 2, Intent(context, AlertReceiver::class.java).setAction(AlertReceiver.CLEAR_RECEIVED),
                    PendingIntent.FLAG_IMMUTABLE))
                .build())
        }
        Log.add("Received a file from a computer")
    }

    fun clearReceived() = synchronized(this) { received.clear() }
}

/**
 * Plays the alarm sound at full alarm volume, even on silent, looping, for at most a minute; the volume goes back to
 * what it was afterwards. Main thread only (calls from elsewhere are posted there).
 */
object Ringer {
    const val MAX_MILLIS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var previousVolume: Int? = null
    private val timeout = Runnable { stopNow(appContext ?: return@Runnable) }
    private var appContext: Context? = null

    fun start(context: Context) = main.post { startNow(context.applicationContext) }

    fun stop(context: Context) = main.post { stopNow(context.applicationContext) }

    private fun startNow(context: Context) {
        appContext = context
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, MAX_MILLIS)
        Alerts.showRinging(context)
        if (player != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (previousVolume == null) previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val sound = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .firstNotNullOfOrNull { RingtoneManager.getActualDefaultRingtoneUri(context, it) }
            ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, sound)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.add("Couldn't play the alarm sound: ${e.javaClass.simpleName}")
            null
        }
        Log.add("Ringing for a computer")
    }

    private fun stopNow(context: Context) {
        main.removeCallbacks(timeout)
        val was = player
        player = null
        if (was != null) runCatching { was.stop() }.also { runCatching { was.release() } }
        previousVolume?.let { volume ->
            runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        previousVolume = null
        Alerts.clearRinging(context)
        if (was != null) Log.add("Stopped ringing")
    }
}

/** Stop on the ringing notification, and dismissing the received-files summary. */
class AlertReceiver : BroadcastReceiver() {
    companion object {
        const val STOP_RING = "org.openbridge.android.STOP_RING"
        const val CLEAR_RECEIVED = "org.openbridge.android.CLEAR_RECEIVED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            STOP_RING -> Ringer.stop(context)
            CLEAR_RECEIVED -> Alerts.clearReceived()
        }
    }
}
