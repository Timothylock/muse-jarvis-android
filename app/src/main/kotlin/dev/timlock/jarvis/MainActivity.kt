package dev.timlock.jarvis

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.store.Intent as StoreIntent
import dev.timlock.jarvis.ui.hud.AmbientFace
import dev.timlock.jarvis.ui.hud.FirstRunScreen
import dev.timlock.jarvis.ui.hud.HudBackground
import dev.timlock.jarvis.ui.hud.PairingPlaceholderScreen
import dev.timlock.jarvis.ui.hud.ProvidePx
import dev.timlock.jarvis.ui.hud.SimFrame
import dev.timlock.jarvis.ui.hud.SimInterstitial
import kotlinx.coroutines.delay

private const val EXTRA_MODE = "jarvis.mode"

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        // Only honor the launch extra on a fresh start, not on recreation.
        vm.start(if (savedInstanceState == null) intent?.getStringExtra(EXTRA_MODE) else null)
        setContent { MuseDeskApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.onModeExtra(intent.getStringExtra(EXTRA_MODE))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun MuseDeskApp(vm: MainViewModel) {
    val state by vm.store.state.collectAsState()
    var pairingOpen by remember { mutableStateOf(false) }
    val interstitialUntil = state.simInterstitialUntil

    LaunchedEffect(interstitialUntil) {
        if (interstitialUntil != null) {
            delay((interstitialUntil - System.currentTimeMillis()).coerceAtLeast(0))
            vm.store.dispatch(StoreIntent.ClearInterstitial)
        }
    }

    ProvidePx {
        HudBackground {
            Box {
                when {
                    state.mode == null && state.orbState == OrbState.BOOT -> Unit // loading persisted mode
                    interstitialUntil != null -> SimInterstitial()
                    pairingOpen -> PairingPlaceholderScreen(onBack = { pairingOpen = false })
                    state.mode == null -> FirstRunScreen(
                        onPair = { pairingOpen = true },
                        onSimulate = { vm.choose(Mode.SIM) },
                        onImport = { pairingOpen = true },
                    )
                    else -> AmbientFace(
                        mode = state.mode,
                        link = state.link,
                        orbState = state.orbState,
                        onPttClick = { if (state.orbState == OrbState.UNPAIRED) pairingOpen = true },
                    )
                }
            }
            if (state.mode == Mode.SIM) SimFrame()
        }
    }
}
