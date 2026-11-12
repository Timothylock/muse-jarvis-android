package dev.timlock.jarvis

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.store.Event
import dev.timlock.jarvis.core.store.Intent
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.feature.pairing.ble.AndroidBlePeripheral
import dev.timlock.jarvis.feature.pairing.ble.AndroidNetworkStatus
import dev.timlock.jarvis.transport.sdk.SdkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Owns the store, the persisted mode and the active transport. Commands run strictly in order. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store get() = AppGraph.store
    val orbHolder get() = AppGraph.orbHolder
    val audioBus get() = AppGraph.audioBus
    val perfHud get() = AppGraph.perfHud
    private val modeStore = ModeStore(app)

    private sealed interface Cmd {
        /** [override] is the raw jarvis.mode extra from the launching intent, if any. */
        data class Boot(val override: String?) : Cmd
        data class Choose(val mode: Mode) : Cmd
        data object Reset : Cmd
        data class Import(val overwrite: Boolean) : Cmd
        data class Scenario(val id: String, val timescale: Float?, val autoPttMs: Long?) : Cmd
    }

    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private var started = false
    private var transport: GadgetTransport? = null
    private var jobs = emptyList<Job>()
    private var transportScope: CoroutineScope? = null

    init {
        viewModelScope.launch {
            for (cmd in commands) {
                when (cmd) {
                    is Cmd.Boot -> boot(cmd.override)
                    is Cmd.Choose -> applyMode(cmd.mode, persist = true)
                    Cmd.Reset -> reset()
                    is Cmd.Import -> {
                        // First-run "Import pairing": switch to REAL (persisted) first, then import from the drop dir.
                        if (store.state.value.mode != Mode.REAL) applyMode(Mode.REAL, persist = true)
                        AppGraph.pairing.import(cmd.overwrite)
                    }
                    is Cmd.Scenario -> {
                        if (store.state.value.mode != Mode.SIM) Log.w("JARVIS_SIM", "jarvis.scenario ignored: not in sim mode")
                        else AppGraph.simController.play(cmd.id, cmd.timescale, cmd.autoPttMs)?.let { Log.w("JARVIS_SIM", "jarvis.scenario failed: $it") }
                    }
                }
            }
        }
    }

    /** Called once from onCreate. */
    fun start(modeExtra: String?) {
        if (started) return
        started = true
        commands.trySend(Cmd.Boot(modeExtra))
    }

    /** ADB `--es jarvis.mode sim|real|reset` on a running activity. */
    fun onModeExtra(extra: String?) {
        when (extra?.lowercase()) {
            "sim" -> commands.trySend(Cmd.Choose(Mode.SIM))
            "real" -> commands.trySend(Cmd.Choose(Mode.REAL))
            "reset" -> commands.trySend(Cmd.Reset)
            null -> Unit
            else -> Log.w("JARVIS_UI", "unknown jarvis.mode extra")
        }
    }

    /** Launch extras `jarvis.scenario` / `jarvis.timescale` / `jarvis.auto_ptt_ms`; runs after any pending mode change. */
    fun onScenarioExtras(id: String?, timescale: Float?, autoPttMs: Long?) {
        if (id.isNullOrBlank()) return
        commands.trySend(Cmd.Scenario(id.trim(), timescale, autoPttMs))
    }

    fun choose(mode: Mode) { commands.trySend(Cmd.Choose(mode)) }

    /** UI import (§4.2.6); [overwrite] is the explicit confirm for a different stored identity. */
    fun importPairing(overwrite: Boolean = false) { commands.trySend(Cmd.Import(overwrite)) }

    private suspend fun boot(extra: String?) {
        when (extra?.lowercase()) {
            "reset" -> reset()
            "sim" -> applyMode(Mode.SIM, persist = true)
            "real" -> applyMode(Mode.REAL, persist = true)
            else -> modeStore.read()?.let { applyMode(it, persist = false) }
        }
        store.dispatch(Intent.BootComplete)
    }

    private suspend fun applyMode(mode: Mode, persist: Boolean) {
        stopTransport()
        if (persist) modeStore.write(mode)
        store.dispatch(Intent.ChooseMode(mode))
        val ts = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        transportScope = ts
        val t = withContext(Dispatchers.Default) { TransportFactory.create(getApplication(), mode, AppGraph.credentials, ts) }
        if (t is SdkTransport) {
            // M7: the BLE byte pipe and network check live outside :transport:sdk; :app is the only place that knows both.
            t.peripheral = AndroidBlePeripheral(getApplication())
            t.network = AndroidNetworkStatus(getApplication())
        }
        transport = t
        AppGraph.transport = t
        jobs = listOf(
            viewModelScope.launch { t.link.collect { store.dispatch(Intent.LinkChanged(it)) } },
            viewModelScope.launch { t.events.collect { store.dispatch(Event(it)) } },
            viewModelScope.launch(Dispatchers.Default) { t.start() }, // never on the main thread (Python/IO)
        )
    }

    private suspend fun reset() {
        stopTransport()
        modeStore.clear()
        store.dispatch(Intent.ResetMode)
    }

    private suspend fun stopTransport() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        AppGraph.transport = null
        val old = transport
        transport = null
        if (old != null) withContext(Dispatchers.Default) { old.stop() }
        transportScope?.cancel()
        transportScope = null
    }
}
