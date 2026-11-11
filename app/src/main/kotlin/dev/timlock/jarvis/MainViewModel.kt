package dev.timlock.jarvis

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbSnapshot
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.AppState
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.Event
import dev.timlock.jarvis.core.store.Intent
import dev.timlock.jarvis.core.transport.GadgetTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Owns the store, the persisted mode and the active transport. Commands run strictly in order. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = AppStore(logger = { Log.w("JARVIS_STORE", it) })
    private val modeStore = ModeStore(app)

    val orbHolder = OrbStateHolder()
    val audioBus = AudioVisualBus()

    private sealed interface Cmd {
        /** [override] is the raw jarvis.mode extra from the launching intent, if any. */
        data class Boot(val override: String?) : Cmd
        data class Choose(val mode: Mode) : Cmd
        data object Reset : Cmd
    }

    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private var started = false
    private var transport: GadgetTransport? = null
    private var jobs = emptyList<Job>()

    init {
        viewModelScope.launch {
            for (cmd in commands) {
                when (cmd) {
                    is Cmd.Boot -> boot(cmd.override)
                    is Cmd.Choose -> applyMode(cmd.mode, persist = true)
                    Cmd.Reset -> reset()
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

    fun choose(mode: Mode) { commands.trySend(Cmd.Choose(mode)) }

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
        val t = TransportFactory.create(mode)
        transport = t
        jobs = listOf(
            viewModelScope.launch { t.link.collect { store.dispatch(Intent.TransportLink(it)) } },
            viewModelScope.launch { t.events.collect { store.dispatch(Event(it)) } },
            viewModelScope.launch { t.start() },
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
        transport?.stop()
        transport = null
    }

    // ---- TEMP(M2) orb bridge: the orb-state-machine work replaces this whole block at merge ----
    private var lastOrb = OrbState.BOOT
    private var prevOrb = OrbState.BOOT
    private var orbSince = monoMillis()
    private var lastLayout = OrbLayout.CENTERED
    private var layoutSince = 0L
    private var lastApp = AppState()
    private var debugOrb: OrbState? = null // TEMP(M2) removed at merge

    // Must stay below the fields above: collection starts immediately on the main dispatcher.
    init { viewModelScope.launch { store.state.collect { publishOrbSnapshot(it) } } }

    /** TEMP(M2) removed at merge: `--es jarvis.debugorb <STATE>` forces the published orb state. */
    fun setDebugOrb(name: String?) {
        debugOrb = name?.let { n -> OrbState.values().firstOrNull { it.name.equals(n, ignoreCase = true) } }
        publishOrbSnapshot(lastApp)
    }

    private fun publishOrbSnapshot(s: AppState) {
        lastApp = s
        val now = monoMillis()
        val eff = debugOrb ?: s.orbState
        if (eff != lastOrb) { prevOrb = lastOrb; lastOrb = eff; orbSince = now }
        val layout = if (s.mode == null) OrbLayout.FIRST_RUN else OrbLayout.CENTERED
        if (layout != lastLayout) { lastLayout = layout; layoutSince = now }
        orbHolder.snapshot = OrbSnapshot(
            state = eff, previous = prevOrb, sinceMs = orbSince, sim = s.mode == Mode.SIM,
            layout = layout, layoutSinceMs = layoutSince,
        )
    }
}
