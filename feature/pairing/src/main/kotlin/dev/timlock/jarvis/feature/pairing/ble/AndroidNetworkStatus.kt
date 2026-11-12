package dev.timlock.jarvis.feature.pairing.ble

import android.content.Context
import dev.timlock.jarvis.core.transport.NetworkStatus

/** M7 CONTRACT STUB — track B implements (ConnectivityManager validated network; SSID best-effort). */
class AndroidNetworkStatus(private val context: Context) : NetworkStatus {
    override fun isOnline(): Boolean = true
    override fun ssid(): String? = null
}
