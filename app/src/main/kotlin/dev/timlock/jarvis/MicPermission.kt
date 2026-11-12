package dev.timlock.jarvis

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Contextual RECORD_AUDIO request (§4.1 step 4). The activity owns the request; the audio layer only raises
 * [AppGraph.needsMicPermission] (first PTT press, or entering sim mode). Call from onCreate.
 */
fun ComponentActivity.bindMicPermission() {
    val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* mic checks the grant itself */ }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            AppGraph.needsMicPermission.collect { needed ->
                if (needed) {
                    AppGraph.markMicPermissionRequested()
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    // POST_NOTIFICATIONS (API 33+) for the link service's notification, asked on entering REAL mode (§4.1 step 4).
    val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            AppGraph.needsNotificationPermission.collect { needed ->
                if (needed) {
                    AppGraph.markNotificationPermissionRequested()
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
