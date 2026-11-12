package dev.timlock.jarvis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.store.AppState
import dev.timlock.jarvis.core.store.Intent as StoreIntent
import dev.timlock.jarvis.transport.sdk.SdkTransport
import dev.timlock.jarvis.ui.hud.SettingsInfo
import dev.timlock.jarvis.ui.hud.SettingsOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Wires the stateless [SettingsOverlay] (§4.5) to the app: store, persistence, pairing, mode change. */
@Composable
internal fun SettingsHost(
    vm: MainViewModel,
    state: AppState,
    onOpenImport: () -> Unit,
    onOpenSimPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val diagFlow = remember(state.mode, state.link) { (AppGraph.transport as? SdkTransport)?.diagnostics }
    val diag = diagFlow?.collectAsState()?.value
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (t: Throwable) { "" }
    }
    val info = SettingsInfo(
        mode = state.mode,
        link = state.link,
        nodeId = (state.link as? LinkState.Online)?.nodeId ?: diag?.nodeId,
        tokenHint = diag?.tokenHint,
        versionName = version,
        sdkVersion = diag?.sdkVersion,
        sdkCommit = diag?.sdkCommit,
        perfHud = state.perfHud,
        testInputEnabled = state.testInputEnabled,
    )
    val close = { AppGraph.setSettingsOpen(false) }
    SettingsOverlay(
        info = info,
        config = state.config,
        onConfig = { new -> AppGraph.updateConfigAsync { new } },
        onClose = close,
        onMode = { m -> close(); vm.choose(m) },
        onRepair = { close(); AppGraph.openPairing(repair = state.mode == Mode.REAL && state.link is LinkState.PairingLost) },
        onImport = { close(); onOpenImport() },
        onUnpair = { AppGraph.appScope.launch(Dispatchers.Default) { AppGraph.transport?.unpair() } },
        onPerfHud = { vm.store.dispatch(StoreIntent.SetPerfHud(it)) },
        onTestInput = { on ->
            AppGraph.appScope.launch {
                AppGraph.devSettings.setTestInputEnabled(on)
                vm.store.dispatch(StoreIntent.SetTestInput(on, vm.store.state.value.testInputActive))
            }
        },
        onOpenSimPanel = { close(); onOpenSimPanel() },
        modifier = modifier,
    )
}
