package org.openbridge.android

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import org.openbridge.protocol.EventLog
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Notifications on computers: off until the user turns it on, and then only with Android's notification access. While
 * on, the phone's notifications (except ongoing ones, group summaries, OpenBridge's own and excluded apps) go into the
 * in-memory [PhoneCompanion.log], which paired computers read with EVENTS. Nothing is written to storage but the
 * switch, the excluded apps and the names of apps seen, and nothing about notifications goes into diagnostics.
 */
object Mirror {
    private lateinit var app: Context
    /** The user's switch, remembered. Mirroring also needs [access] and a connected listener. */
    var enabled by mutableStateOf(false); private set
    /** Android's notification access for OpenBridge, as last checked. */
    var access by mutableStateOf(false); private set
    /** Packages left out, by the user. */
    var excluded by mutableStateOf(emptySet<String>()); private set
    /** Packages seen posting notifications while mirroring was on, for the Apps list. */
    var seen by mutableStateOf(emptySet<String>()); private set
    private const val MAX_SEEN = 300

    /** The listener while Android has it connected. */
    @Volatile internal var listener: NotificationMirror? = null
    /** The wire key of each notification sent, to its package and Android's own key. */
    private val sent = ConcurrentHashMap<String, Pair<String, String>>()

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val preferences = preferences()
        enabled = preferences.getBoolean("enabled", false)
        excluded = preferences.getStringSet("excluded", emptySet())!!.toSet()
        seen = preferences.getStringSet("seen", emptySet())!!.toSet()
        refreshAccess()
    }

    private fun preferences() = app.getSharedPreferences("mirror", Context.MODE_PRIVATE)

    /** Checks Android's notification access again (after returning from its settings screen). */
    fun refreshAccess() {
        val now = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
        if (now != access) Log.add("Notification access ${if (now) "allowed" else "not allowed"}")
        access = now
        if (now && enabled && listener == null)
            runCatching { NotificationListenerService.requestRebind(ComponentName(app, NotificationMirror::class.java)) }
    }

    /** Whether notifications go to computers now. */
    val active get() = enabled && listener != null

    fun enable(on: Boolean) {
        if (on == enabled) return
        enabled = on
        preferences().edit().putBoolean("enabled", on).apply()
        Log.add("Notifications on computers turned ${if (on) "on" else "off"}")
        if (on) { refreshAccess(); listener?.sendAll() } else clear()
    }

    /** The screen where the user allows (or takes away) notification access. */
    fun accessSettings(): Intent =
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(app, NotificationMirror::class.java).flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun include(packageName: String, shown: Boolean) {
        excluded = if (shown) excluded - packageName else excluded + packageName
        preferences().edit().putStringSet("excluded", excluded).apply()
        Log.add("An app was ${if (shown) "included in" else "excluded from"} notifications on computers")
        if (!shown) sent.entries.filter { it.value.first == packageName }.forEach { remove(it.key) }
        else listener?.sendAll()
    }

    private fun clear() {
        sent.clear()
        PhoneCompanion.log.removeAll()
    }

    private fun remove(wireKey: String) {
        sent.remove(wireKey)
        PhoneCompanion.log.removed(wireKey)
    }

    internal fun connected(service: NotificationMirror) {
        listener = service
        Log.add("Notification listener connected")
        refreshAccess()
        service.sendAll()
    }

    internal fun disconnected(service: NotificationMirror) {
        if (listener === service) listener = null
        Log.add("Notification listener disconnected")
        clear()
    }

    /** On the main thread, where Android delivers the listener's callbacks. */
    private fun noteSeen(packageName: String) {
        if (packageName in seen || seen.size >= MAX_SEEN) return
        seen = seen + packageName
        preferences().edit().putStringSet("seen", seen).apply()
    }

    /**
     * A key computers can send back as a document ID: Android's own when it fits in 128 bytes (the ID limit every
     * computer knows), otherwise a hash of it.
     */
    fun wireKey(key: String): String =
        if (key.toByteArray().size <= 128) key
        else "h:" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Whether this notification goes to computers at all. */
    private fun mirrored(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        if (sbn.packageName == app.packageName || sbn.isOngoing) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        // Media controls and navigation are ongoing in spirit even when paused.
        if (notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        if (notification.category == Notification.CATEGORY_TRANSPORT || notification.category == Notification.CATEGORY_NAVIGATION) return false
        return true
    }

    internal fun posted(sbn: StatusBarNotification) {
        // A connected listener means Android allows access; the switch decides.
        if (!enabled) return
        val key = wireKey(sbn.key)
        // One that stopped qualifying (it became ongoing, say) goes from computers too.
        if (!mirrored(sbn)) { if (sent.containsKey(key)) remove(key); return }
        noteSeen(sbn.packageName)
        if (sbn.packageName in excluded) { if (sent.containsKey(key)) remove(key); return }
        val notification = sbn.notification
        val extras = notification.extras ?: Bundle()
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: lines ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty()
        if (title.isBlank() && text.isBlank()) { if (sent.containsKey(key)) remove(key); return }
        val time = if (notification.`when` > 0) notification.`when` else sbn.postTime
        val flags = (if (replyAction(notification) != null) EventLog.CAN_REPLY else 0) or (if (sbn.isClearable) EventLog.CAN_DISMISS else 0)
        sent[key] = sbn.packageName to sbn.key
        PhoneCompanion.log.notification(key, label(sbn.packageName), sbn.packageName, title, text, time, flags, OneTimeCode.find("$title\n$text").orEmpty())
    }

    internal fun removed(sbn: StatusBarNotification) {
        val key = wireKey(sbn.key)
        if (sent.containsKey(key)) remove(key)
    }

    private val labels = ConcurrentHashMap<String, String>()

    /** The app's name as the launcher shows it, or its package name when Android doesn't tell. */
    fun label(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    fun icon(packageName: String): android.graphics.drawable.Drawable? = runCatching { app.packageManager.getApplicationIcon(packageName) }.getOrNull()

    private fun replyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun find(wireKey: String): StatusBarNotification {
        if (!active) throw IOException("Notifications on computers are off on the phone")
        val androidKey = sent[wireKey]?.second ?: throw IOException("That notification is gone")
        val service = listener ?: throw IOException("Notifications on computers are off on the phone")
        return runCatching { service.activeNotifications?.firstOrNull { it.key == androidKey } }.getOrNull()
            ?: throw IOException("That notification is gone")
    }

    /** NOTIFICATION ACTION dismiss. On a server thread. */
    fun dismiss(wireKey: String) {
        val sbn = find(wireKey)
        if (!sbn.isClearable) throw IOException("That notification can't be dismissed")
        listener?.cancelNotification(sbn.key) ?: throw IOException("Notifications on computers are off on the phone")
        Log.add("A computer dismissed a notification")
    }

    /** NOTIFICATION ACTION reply, through the notification's own reply action. On a server thread. */
    fun reply(wireKey: String, text: String) {
        val sbn = find(wireKey)
        val action = replyAction(sbn.notification) ?: throw IOException("That notification can't be answered")
        val inputs = action.remoteInputs!!
        val intent = Intent()
        val results = Bundle()
        for (input in inputs) if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        try { action.actionIntent.send(app, 0, intent) }
        catch (_: PendingIntent.CanceledException) { throw IOException("That notification can't be answered any more") }
        Log.add("A computer answered a notification")
    }
}

/** Android's connection to the phone's notifications, for [Mirror]. Declared with BIND_NOTIFICATION_LISTENER_SERVICE. */
class NotificationMirror : NotificationListenerService() {
    override fun onListenerConnected() {
        Mirror.attach(this)
        Mirror.connected(this)
    }

    override fun onListenerDisconnected() = Mirror.disconnected(this)

    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn != null) runCatching { Mirror.posted(sbn) } }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn != null) runCatching { Mirror.removed(sbn) } }

    /** Every notification on the phone now (after connecting, or the switch or an app was turned on). */
    fun sendAll() {
        val all = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in all) runCatching { Mirror.posted(sbn) }
    }
}

/**
 * One-time codes in a notification: 4 to 8 digits (also "123 456" or "123-456") close to a word like code, OTP or
 * verification, in English, Chinese and a few other languages. The digits nearest such a word win.
 */
object OneTimeCode {
    private val words = Regex(
        "code|otp|one[- ]time|passcode|password|pin\\b|verif|2fa|two[- ]factor|security|login|log in|sign[- ]in|auth|token|" +
            "验证码|校驗碼|驗證碼|校验码|动态码|動態碼|确认码|確認碼|密码|口令|认证|認証|認證|인증|コード|" +
            "código|codigo|codice|kod|kode|verificación|verificação|bestätigung|vérification|код|пароль|رمز",
        RegexOption.IGNORE_CASE)
    private val digits = Regex("(?<![0-9])([0-9]{3})[ -]([0-9]{3})(?![0-9])|(?<![0-9.,:/])[0-9]{4,8}(?![0-9]|[.,:/][0-9])")
    private const val NEAR = 80

    fun find(text: String): String? {
        val hits = words.findAll(text).map { it.range }.toList()
        if (hits.isEmpty()) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (match in digits.findAll(text)) {
            val distance = hits.minOf { word ->
                when {
                    match.range.first > word.last -> match.range.first - word.last
                    word.first > match.range.last -> word.first - match.range.last
                    else -> 0
                }
            }
            if (distance <= NEAR && distance < bestDistance) {
                bestDistance = distance
                best = match.value.filter { it.isDigit() }
            }
        }
        return best
    }
}
