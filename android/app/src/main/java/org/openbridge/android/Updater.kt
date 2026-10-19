package org.openbridge.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openbridge.ui.Release
import org.openbridge.ui.Updates
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Finds a newer release (one anonymous request to GitHub per launch, see Updates) and, when the user taps Update,
 * downloads its APK and SHA256SUMS.txt over HTTPS, checks the APK's SHA-256 against it, and hands it to Android's
 * installer, which asks the user and accepts it only when it's signed with the same key as the installed app.
 */
object Updater {
    enum class Step { IDLE, DOWNLOADING, VERIFYING, INSTALLING, PERMISSION, FAILED }

    private const val DOWNLOADS = "https://github.com/PredatorWoah/OpenBridge/releases/download"
    /** Far above any OpenBridge APK; a download larger than this is refused. */
    private const val MAX_APK = 200L shl 20

    /** A newer release, once the check found one. */
    var available by mutableStateOf<Release?>(null); private set
    var checking by mutableStateOf(false); private set
    /** The latest check the user asked for found nothing newer. */
    var upToDate by mutableStateOf(false); private set
    var step by mutableStateOf(Step.IDLE); private set
    /** Download progress, 0 to 1. */
    var progress by mutableFloatStateOf(0f); private set
    /** Why the update didn't go through, or null. */
    var problem by mutableStateOf<String?>(null); private set

    private var checked = false
    /** The installer's confirmation, when it arrived while the app wasn't in front. */
    private var confirmation: Intent? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Removes downloads an earlier run left behind. */
    fun attach(context: Context) {
        if (step == Step.IDLE) folder(context).listFiles()?.forEach { it.delete() }
    }

    private fun folder(context: Context) = File(context.cacheDir, "updates")

    /** Once per launch; [manual] when the user tapped Check for updates. */
    fun check(manual: Boolean = false) {
        if ((checked && !manual) || checking) return
        checked = true; checking = true; upToDate = false
        worker.execute {
            val release = Updates.newerThan(BuildConfig.VERSION_NAME)
            Log.add(if (release != null) "Update check: ${release.version} is available" else "Update check: nothing newer (or offline)")
            main.post { checking = false; available = release ?: available; upToDate = manual && release == null }
        }
    }

    val busy get() = step == Step.DOWNLOADING || step == Step.VERIFYING || step == Step.INSTALLING

    fun update(activity: Activity) {
        val release = available ?: return
        if (busy) return
        problem = null
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Android asks once whether OpenBridge may install apps; the update continues when the user comes back.
            step = Step.PERMISSION
            Log.add("Update: asking to allow installing apps")
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (_: Exception) { fail(S.updateNeedsPermission) }
            return
        }
        download(activity.applicationContext, release)
    }

    /** Back in the app: continue after the "Install unknown apps" setting, or show a confirmation that arrived meanwhile. */
    fun resumed(activity: Activity) {
        if (step == Step.PERMISSION) {
            if (activity.packageManager.canRequestPackageInstalls()) available?.let { download(activity.applicationContext, it) }
            else fail(S.updateNeedsPermission)
        }
        confirmation?.let { confirmation = null; runCatching { activity.startActivity(it) } }
    }

    private fun download(context: Context, release: Release) {
        step = Step.DOWNLOADING; progress = 0f
        val name = "OpenBridge-${release.version}.apk"
        val base = "$DOWNLOADS/v${release.version}"
        val apk = File(folder(context).apply { mkdirs() }, name)
        worker.execute {
            try {
                Log.add("Update ${release.version}: downloading")
                val sums = ByteArrayOutputStream().also { fetch("$base/SHA256SUMS.txt", 1 shl 20, it) { _, _ -> } }.toString("UTF-8")
                val expected = sums.lineSequence()
                    .mapNotNull { Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*)$").find(it.trim()) }
                    .firstOrNull { it.groupValues[2].trim() == name }?.groupValues?.get(1)?.lowercase()
                    ?: throw IOException("No checksum for $name")
                val digest = MessageDigest.getInstance("SHA-256")
                apk.outputStream().use { file ->
                    val out = object : OutputStream() {
                        override fun write(b: Int) { file.write(b); digest.update(b.toByte()) }
                        override fun write(b: ByteArray, off: Int, len: Int) { file.write(b, off, len); digest.update(b, off, len) }
                    }
                    fetch("$base/$name", MAX_APK, out) { done, total -> if (total > 0) main.post { progress = done.toFloat() / total } }
                }
                main.post { step = Step.VERIFYING }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) throw SecurityException("Checksum mismatch")
                Log.add("Update ${release.version}: checksum matches, installing")
                main.post { step = Step.INSTALLING }
                install(context, apk)
            } catch (e: Exception) {
                Log.add("Update failed: ${e.javaClass.simpleName}${if (e is SecurityException) " (checksum)" else ""}")
                main.post { fail(if (e is SecurityException) S.updateMismatch else S.updateFailed) }
            } finally {
                apk.delete()   // the installer session holds its own copy
            }
        }
    }

    /** Downloads [url] over HTTPS only (GitHub redirects to its file host; never to plain HTTP) into [out]. */
    private fun fetch(url: String, limit: Long, out: OutputStream, progress: (Long, Long) -> Unit) {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "OpenBridge")
        try {
            if (connection.responseCode != 200 || connection.url.protocol != "https") throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            if (total > limit) throw IOException("Too large")
            var done = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    done += read
                    if (done > limit) throw IOException("Too large")
                    out.write(buffer, 0, read)
                    progress(done, total)
                }
            }
        } finally { connection.disconnect() }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("OpenBridge.apk", 0, apk.length()).use { output -> input.copyTo(output, 1 shl 16); session.fsync(output) }
            }
            // The installer fills in the result, so this PendingIntent must be mutable; it names our own receiver.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }

    /** What the installer said (from [UpdateReceiver]). */
    internal fun status(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return fail(S.updateFailed)
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android only lets an app in front open the confirmation; otherwise it opens when the app comes back.
                if (Sharing.onScreen) runCatching { context.startActivity(confirm) }.onFailure { confirmation = confirm }
                else confirmation = confirm
            }
            PackageInstaller.STATUS_SUCCESS -> { Log.add("Update installed"); step = Step.IDLE }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { Log.add("Update cancelled"); step = Step.IDLE }
            else -> { Log.add("Update failed: installer status $status"); fail(S.updateFailed) }
        }
    }

    private fun fail(reason: String) { step = Step.FAILED; problem = reason }
}

/** Receives the installer's answer for an update. Not exported: only the installer, through our PendingIntent, sends it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.status(context, intent)
}
