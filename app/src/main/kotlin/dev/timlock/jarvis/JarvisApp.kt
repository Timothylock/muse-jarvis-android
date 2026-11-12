package dev.timlock.jarvis

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.start(this)
        // §7.7 ADB surface. Registered at runtime because Android 8+ does not deliver implicit
        // broadcasts (`am broadcast -a dev.timlock.jarvis.SIM` without -n) to manifest receivers.
        // Guarded by DUMP, which only the adb shell (and system) hold.
        ContextCompat.registerReceiver(
            this, SimCommandReceiver(), IntentFilter(ACTION_SIM),
            "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED,
        )
    }

    companion object {
        const val ACTION_SIM = "dev.timlock.jarvis.SIM"
    }
}
