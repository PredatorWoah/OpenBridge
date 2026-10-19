package org.openbridge.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.openbridge.protocol.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Phone hotspot, for Wi-Fi that keeps devices apart (guest, hotel and campus networks): the computer joins the phone's
 * own network instead. On Android 13 and later the app starts a local-only hotspot (its own name and password, no
 * internet) without location access; before that the user turns on the system hotspot in settings. Either way,
 * sharing also listens on it while it's on.
 */
object Hotspot {
    class Network(val name: String, val password: String?)

    /** The hotspot this app started, while it's on. */
    var network by mutableStateOf<Network?>(null); private set
    var starting by mutableStateOf(false); private set
    /** Why the hotspot didn't start, or null. */
    var problem by mutableStateOf<String?>(null); private set
    /** Android says a Wi-Fi hotspot is on: this app's, or the system one the user turned on. */
    var systemOn by mutableStateOf(false); private set

    /** Android's own hotspot state (sticky broadcast), by interface. */
    private const val AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val AP_ENABLED = 13
    private val interfaces = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var anyOn = false
    @Volatile private var ours = false
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var stopRequested = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    /** Names Android gives Wi-Fi access point interfaces. */
    private val accessPoint = Regex("(wlan|swlan|ap|softap|wifi|wigig)\\d+")

    /** Android 13 lets apps start a local-only hotspot with the Nearby devices permission instead of location. */
    val supported get() = Build.VERSION.SDK_INT >= 33

    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = apState(intent) }
        apState(ContextCompat.registerReceiver(app, receiver, IntentFilter(AP_STATE), ContextCompat.RECEIVER_EXPORTED))
    }

    private fun apState(intent: Intent?) {
        intent ?: return
        val enabled = intent.getIntExtra("wifi_state", 0) == AP_ENABLED
        val name = intent.getStringExtra("wifi_ap_interface_name") ?: ""
        if (enabled) interfaces[name] = true else if (name.isEmpty()) interfaces.clear() else interfaces.remove(name)
        anyOn = interfaces.isNotEmpty()
        systemOn = anyOn
        changed()
    }

    @RequiresApi(33)
    fun start(context: Context) {
        if (network != null || starting) return
        starting = true; problem = null; stopRequested = false
        Log.add("Starting the phone hotspot")
        try {
            context.getSystemService(WifiManager::class.java).startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(started: WifiManager.LocalOnlyHotspotReservation) {
                    starting = false
                    if (stopRequested) { started.close(); return }
                    reservation = started
                    val config = started.softApConfiguration
                    network = Network(config.wifiSsid?.bytes?.toString(Charsets.UTF_8).orEmpty(), config.passphrase)
                    ours = true
                    Log.add("Phone hotspot on")
                    changed()
                }

                override fun onStopped() {
                    reservation = null; network = null; ours = false
                    Log.add("Android stopped the phone hotspot")
                    changed()
                }

                override fun onFailed(reason: Int) {
                    starting = false
                    problem = when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> S.hotspotBusy
                        ERROR_TETHERING_DISALLOWED -> S.hotspotDisallowed
                        else -> S.hotspotFailed
                    }
                    Log.add("Phone hotspot failed ($reason)")
                }
            }, main)
        } catch (_: SecurityException) {
            starting = false; problem = S.hotspotPermission
            Log.add("Phone hotspot needs the Nearby devices permission")
        } catch (_: IllegalStateException) {
            starting = false; problem = S.hotspotFailed
            Log.add("Phone hotspot was already requested")
        }
    }

    /** Turns off the hotspot this app started (when sharing stops, the app closes, or the user taps Stop). */
    fun stop() {
        stopRequested = true
        val held = reservation ?: return
        reservation = null; network = null; ours = false
        runCatching { held.close() }
        Log.add("Phone hotspot off")
        changed()
    }

    /** The user said no to Nearby devices. */
    fun denied() { problem = S.hotspotPermission }

    /** Before Android 13: the system hotspot settings (tethering), or the network settings when those can't open. */
    fun openSystemSettings(activity: Activity) {
        Log.add("Opening the system hotspot settings")
        try {
            activity.startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
        } catch (_: Exception) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    /**
     * The private IPv4 address of the phone's own Wi-Fi hotspot while one is on, or null. That's an access point
     * interface that isn't one of Android's networks (the Wi-Fi the phone joined, mobile data, a VPN). Any thread.
     */
    fun address(context: Context): InetAddress? {
        if (!ours && !anyOn) return null
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = runCatching { connectivity.allNetworks.mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet() }
            .getOrDefault(emptySet())
        val known = interfaces.keys
        return runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && it.name !in networks && accessPoint.matches(it.name) }
            .sortedBy { if (it.name in known) 0 else 1 }
            .firstNotNullOfOrNull { candidate -> candidate.inetAddresses.toList().firstOrNull { it is Inet4Address && Wire.isPrivateIPv4(it) } }
    }

    /** The hotspot's address appears a moment after it starts: look a few times. */
    private fun changed() {
        for (delay in longArrayOf(0, 1_500, 4_000)) main.postDelayed({ Sharing.server?.check() }, delay)
    }
}
