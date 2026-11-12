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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import dev.timlock.jarvis.ui.hud.LocalPx
import dev.timlock.jarvis.ui.hud.PerfHud
import dev.timlock.jarvis.ui.hud.SimDrawer
import dev.timlock.jarvis.ui.hud.threeFingerLongPress
import dev.timlock.jarvis.core.model.DisplayPreset
import dev.timlock.jarvis.core.model.SimFault
import kotlinx.coroutines.launch
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
private const val EXTRA_SCENARIO = "jarvis.scenario"
private const val EXTRA_TIMESCALE = "jarvis.timescale"
private const val EXTRA_AUTO_PTT = "jarvis.auto_ptt_ms"

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
        if (savedInstanceState == null) applyScenarioExtras(intent)
        setContent { MuseDeskApp(vm) { orbView = it } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.onModeExtra(intent.getStringExtra(EXTRA_MODE))
        applyScenarioExtras(intent)
    }

    /** §7.2: `--es jarvis.scenario <id> [--ef jarvis.timescale F] [--el jarvis.auto_ptt_ms N]`. Queued after the mode. */
    private fun applyScenarioExtras(i: Intent?) {
        val id = i?.getStringExtra(EXTRA_SCENARIO) ?: return
        val ts = if (i.hasExtra(EXTRA_TIMESCALE)) i.getFloatExtra(EXTRA_TIMESCALE, 1f) else null
        val auto = if (i.hasExtra(EXTRA_AUTO_PTT)) i.getLongExtra(EXTRA_AUTO_PTT, 0L) else null
        vm.onScenarioExtras(id, ts, auto)
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
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val simMode = state.mode == Mode.SIM
    LaunchedEffect(simMode) { if (!simMode) drawerOpen = false }
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
            Box(Modifier.threeFingerLongPress(enabled = simMode) { drawerOpen = true }) {
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
                        onSimChipClick = { drawerOpen = true },
                        hasDockedPanels = state.dockCount > 0,
                        layout = state.layout,
                        caption = state.caption,
                        callSinceMs = state.callSinceMs,
                        latched = state.latched,
                        onPttDown = { vm.store.dispatch(StoreIntent.PttDown) },
                        onPttUp = { vm.store.dispatch(StoreIntent.PttUp) },
                        onHangUp = { vm.store.dispatch(StoreIntent.HangUp) },
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
                if (interstitialUntil == null) {
                    if (state.perfHud) {
                        val perf by vm.perfHud.collectAsState()
                        val px = LocalPx.current
                        PerfHud(perf, Modifier.align(Alignment.BottomStart).padding(bottom = px.dp(72f), start = px.dp(16f))) // bottom-left: clear of panels (x>=625) and the docked orb
                    }
                    if (simMode) SimLayer(vm, drawerOpen, state.forcedOrb, state.orbState, state.perfHud) { drawerOpen = it }
                }
            }
            if (state.mode == Mode.SIM) SimFrame()
        }
    }
}

/** Collects the sim controller's flows; composed ONLY in sim mode. */
@Composable
private fun SimLayer(
    vm: MainViewModel,
    open: Boolean,
    forced: OrbState?,
    orb: OrbState,
    perfHud: Boolean,
    setOpen: (Boolean) -> Unit,
) {
    val sim = AppGraph.simController
    val scenarios by sim.scenarios.collectAsState()
    val run by sim.run.collectAsState()
    val settings by sim.settings.collectAsState()
    val log by sim.log.collectAsState()
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(toast) { if (toast != null) { delay(4000); toast = null } }
    SimDrawer(
        open = open, scenarios = scenarios, run = run, settings = settings,
        forcedOrb = forced, currentOrb = orb, perfHud = perfHud, log = log, toast = toast,
        onClose = { setOpen(false) },
        onExit = { setOpen(false); vm.choose(Mode.REAL) },
        onPlay = { sim.play(it)?.let { err -> toast = "error: $err" } },
        onStop = { sim.stop() },
        onPause = { sim.pause(it) },
        onForceOrb = { vm.store.dispatch(StoreIntent.ForceOrb(it)) },
        onPreset = { p ->
            scope.launch {
                val r = try { sim.preset(p) } catch (t: Throwable) { null }
                toast = when {
                    r == null -> "error: no reply"
                    r.ok -> "ok"
                    else -> "error: ${r.error ?: "?"}"
                }
            }
        },
        onFault = { sim.fault(it)?.let { err -> toast = "error: $err" } },
        onRepair = { sim.repair()?.let { err -> toast = "error: $err" } },
        onSettings = { sim.updateSettings(it) },
        onPerfHud = { vm.store.dispatch(StoreIntent.SetPerfHud(it)) },
    )
}
