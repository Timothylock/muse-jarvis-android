package dev.timlock.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.store.AppState
import dev.timlock.jarvis.core.store.CallStatus
import dev.timlock.jarvis.core.store.PttState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * §6.8 foreground service: keeps the process (and so the Muse link) alive while mode == REAL.
 * Type `specialUse` always; `microphone` is added while a call/PTT turn is active (re-calls startForeground; Android 14+
 * requires the microphone type to be started while the activity is visible, which the PTT press is) and dropped after.
 * The notification text is derived from the link state only (no secrets). The service owns no transport.
 */
class JarvisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Muse Desk link", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows that the display is connected to Muse"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || AppGraph.store.state.value.mode != Mode.REAL) {
            // startForegroundService() obliges a startForeground() call even if we are stopping right away.
            try { startFg(notification("Offline"), false) } catch (e: Exception) { Log.w("JARVIS_UI", "fgs start failed: ${e.javaClass.simpleName}") }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        render(AppGraph.store.state.value) // must call startForeground promptly
        scope.launch {
            AppGraph.store.state
                .map { s -> Triple(linkText(s), micActive(s), s.mode == Mode.REAL) }
                .distinctUntilChanged()
                .collect { (_, _, real) ->
                    if (!real) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else render(AppGraph.store.state.value)
                }
        }
        return START_NOT_STICKY
    }

    private fun render(s: AppState) {
        val text = linkText(s)
        val mic = micActive(s) && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val n = notification(text)
        try {
            startFg(n, mic)
        } catch (e: SecurityException) {
            // Microphone type refused (e.g. started from the background): stay on specialUse.
            Log.w("JARVIS_UI", "fgs microphone type refused: ${e.javaClass.simpleName}")
            try { startFg(n, false) } catch (t: Exception) { Log.w("JARVIS_UI", "fgs start failed: ${t.javaClass.simpleName}") }
        } catch (e: IllegalStateException) {
            Log.w("JARVIS_UI", "fgs start failed: ${e.javaClass.simpleName}")
        }
    }

    private fun startFg(n: Notification, mic: Boolean) {
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, n, type)
            }
            Build.VERSION.SDK_INT >= 29 && mic -> startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else -> startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Muse Desk")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "jarvis_link"
        const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "dev.timlock.jarvis.STOP_SERVICE"

        fun linkText(s: AppState): String = when (s.link) {
            is LinkState.Online -> "Connected to Muse"
            LinkState.Unpaired, is LinkState.PairingLost -> "Not paired"
            else -> "Offline"
        }

        private fun micActive(s: AppState) = s.call != CallStatus.None || s.ptt != PttState.Up

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
            } catch (e: Exception) {
                Log.w("JARVIS_UI", "cannot start link service: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }
}
