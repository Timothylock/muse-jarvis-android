package dev.timlock.jarvis

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.Intent as StoreIntent
import dev.timlock.jarvis.core.store.PanelHeights
import dev.timlock.jarvis.render.orb.OrbSurfaceView
import dev.timlock.jarvis.ui.hud.AmbientFace
import dev.timlock.jarvis.ui.hud.FirstRunScreen
import dev.timlock.jarvis.ui.hud.HudBackground
import dev.timlock.jarvis.ui.hud.PairingPlaceholderScreen
import dev.timlock.jarvis.ui.hud.ProvidePx
import dev.timlock.jarvis.ui.hud.SimFrame
import dev.timlock.jarvis.ui.hud.SimInterstitial
import dev.timlock.jarvis.ui.hud.panels.PanelStackView
import kotlinx.coroutines.delay

private const val EXTRA_MODE = "jarvis.mode"

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var orbView: OrbSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        // Only honor the launch extra on a fresh start, not on recreation.
        vm.start(if (savedInstanceState == null) intent?.getStringExtra(EXTRA_MODE) else null)
        setContent { MuseDeskApp(vm) { orbView = it } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.onModeExtra(intent.getStringExtra(EXTRA_MODE))
    }

    override fun onResume() {
        super.onResume()
        orbView?.resumeOrb()
    }

    override fun onPause() {
        orbView?.pauseOrb()
        super.onPause()
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
private fun MuseDeskApp(vm: MainViewModel, onOrbView: (OrbSurfaceView) -> Unit) {
    val state by vm.store.state.collectAsState()
    var pairingOpen by remember { mutableStateOf(false) }
    val interstitialUntil = state.simInterstitialUntil

    LaunchedEffect(interstitialUntil) {
        if (interstitialUntil != null) {
            delay((interstitialUntil - monoMillis()).coerceAtLeast(0))
            vm.store.dispatch(StoreIntent.ClearInterstitial)
        }
    }

    ProvidePx {
        // GL orb behind everything; it draws the window background (vignette) itself.
        AndroidView(
            factory = { ctx -> OrbSurfaceView(ctx, vm.orbHolder, vm.audioBus).also(onOrbView) },
            modifier = Modifier.fillMaxSize(),
        )
        HudBackground(drawBackground = false) {
            Box {
                when {
                    state.mode == null && state.orbState == OrbState.BOOT -> Unit // loading persisted mode
                    interstitialUntil != null -> SimInterstitial()
                    pairingOpen -> PairingPlaceholderScreen(onBack = { pairingOpen = false })
                    state.mode == null -> FirstRunScreen(
                        onPair = { pairingOpen = true },
                        onSimulate = { vm.choose(Mode.SIM) },
                        onImport = { pairingOpen = true },
                        orb = {},
                    )
                    else -> AmbientFace(
                        mode = state.mode,
                        link = state.link,
                        orbState = state.orbState,
                        onPttClick = { if (state.orbState == OrbState.UNPAIRED) pairingOpen = true },
                        orb = {},
                        hasDockedPanels = state.dockCount > 0,
                        overlay = {
                            PanelStackView(
                                stack = state.panels,
                                speaking = state.orbState == OrbState.SPEAKING,
                                heightPx = PanelHeights::estimatePx,
                                onDismiss = { vm.store.dispatch(StoreIntent.DismissPanel(it)) },
                                onPin = { id, pinned -> vm.store.dispatch(StoreIntent.PinPanel(id, pinned)) },
                                onExpand = { vm.store.dispatch(StoreIntent.ExpandPanel(it)) },
                                onAcknowledgeTimer = { vm.store.dispatch(StoreIntent.AcknowledgeTimer(it)) },
                            )
                        },
                    )
                }
            }
            if (state.mode == Mode.SIM) SimFrame()
        }
    }
}
