package org.openbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps sharing running after the user leaves the app, with an ongoing notification that shows the
 * status and a Stop button. Pairing requests that arrive in the background appear as a notification.
 */
class ShareService : Service() {
    companion object {
        private const val SHARING_CHANNEL = "sharing"
        private const val PAIRING_CHANNEL = "pairing"
        private const val SHARING_ID = 1
        private const val PAIRING_ID = 2
        private const val TEXTS_CHANNEL = "texts"
        private const val TEXT_ID = 3
        private const val STOP = "org.openbridge.android.STOP"
        private const val PAIR = "org.openbridge.android.PAIR"
        private const val DECLINE = "org.openbridge.android.DECLINE"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, ShareService::class.java)) }

        /** Updates the ongoing notification, for example once a computer has paired. */
        fun refresh(context: Context) {
            if (Sharing.server != null && Sharing.inBackground) notify(context, SHARING_ID, sharing(context))
        }

        fun showPairing(context: Context, code: String, computer: String?) {
            channels(context)
            val text = S.codeNotice(code)
            val notification = NotificationCompat.Builder(context, PAIRING_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(S.pairWith(computer))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(openApp(context))
                .addAction(0, S.cancel, action(context, DECLINE))
                .addAction(0, S.pair, action(context, PAIR))
                .setTimeoutAfter(60_000)
                .setAutoCancel(true)
                .build()
            notify(context, PAIRING_ID, notification)
        }

        fun clearPairing(context: Context) = NotificationManagerCompat.from(context).cancel(PAIRING_ID)

        private fun notify(context: Context, id: Int, notification: Notification) {
            try { NotificationManagerCompat.from(context).notify(id, notification) } catch (_: SecurityException) { /* notifications are off */ }
        }

        private fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(SHARING_CHANNEL, S.channelSharing, NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(PAIRING_CHANNEL, S.channelPairing, NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(TEXTS_CHANNEL, S.channelTexts, NotificationManager.IMPORTANCE_LOW))
        }

        private fun openApp(context: Context) = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

        private fun action(context: Context, name: String) = PendingIntent.getService(context, name.hashCode(),
            Intent(context, ShareService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

        private fun sharing(context: Context): Notification {
            channels(context)
            val server = Sharing.server
            val network = Sharing.endpoints.firstOrNull { it.via != Via.USB }
            val text = when {
                Sharing.paired -> S.connectedTo(Sharing.computer)
                server == null -> S.starting
                network != null -> S.onComputer(network.host, network.port)
                else -> S.overCable
            }
            val title = when {
                server?.cableOnly == true -> S.sharingOverCableTitle
                Sharing.wholePhone -> S.sharingWholePhoneTitle
                else -> S.sharingOneFolderTitle
            }
            return NotificationCompat.Builder(context, SHARING_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp(context))
                .addAction(0, S.sendClipboard, PendingIntent.getActivity(context, 3, SendClipboard.intent(context), PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, S.stopSharing, action(context, STOP))
                .build()
        }

        /** A computer put a text on the phone's clipboard while the app wasn't in front. The text itself isn't shown. */
        fun textReceived(context: Context, computer: String?) {
            channels(context)
            notify(context, TEXT_ID, NotificationCompat.Builder(context, TEXTS_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(S.textCopied(computer))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build())
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Sharing.attach(this)
        when (intent?.action) {
            STOP -> { Sharing.stop(); return START_NOT_STICKY }
            PAIR -> Sharing.decidePairing(true)
            DECLINE -> Sharing.decidePairing(false)
        }
        // Always enter the foreground first: Android requires it after startForegroundService.
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, SHARING_ID, sharing(this), type)
        if (Sharing.server == null && !Sharing.busy) { stopSelf(); return START_NOT_STICKY }
        // Transfers continue with the screen off: keep the CPU and Wi-Fi awake while sharing.
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenBridge:sharing").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        if (wifiLock == null) wifiLock = @Suppress("DEPRECATION") getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenBridge:sharing").apply { setReferenceCounted(false); acquire() }
        return START_NOT_STICKY
    }

    /** Android 15 limits background data sync to about six hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Sharing.stop(S.pausedInBackground)
    }

    override fun onDestroy() {
        wakeLock?.release(); wakeLock = null
        wifiLock?.release(); wifiLock = null
        super.onDestroy()
    }
}
