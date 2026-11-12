package dev.timlock.jarvis.feature.pairing.ble

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dev.timlock.jarvis.core.transport.NetworkStatus

/**
 * ConnectivityManager-backed [NetworkStatus]. Online = the active network is VALIDATED; when validation has not
 * (yet) run, a network that advertises INTERNET and is not a captive portal still counts: wrongly reporting
 * "offline" would block pairing (SDK sends wifi_failed), while a wrong "online" only fails later at the token check.
 * No location permission is requested: the SSID is best-effort and null whenever Android hides it.
 */
class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val app = context.applicationContext

    override fun isOnline(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
            )
    } catch (t: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    override fun ssid(): String? = try {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        BleSupport.normalizeSsid(wm?.connectionInfo?.ssid)
    } catch (t: Throwable) {
        null
    }
}
