package dev.timlock.jarvis

import android.util.Log
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.store.toOrbSnapshot
import dev.timlock.jarvis.transport.sim.SimDemoDriver
import dev.timlock.jarvis.transport.sim.SyntheticLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App-lifetime singletons (manual wiring, no DI). Started from [JarvisApp.onCreate]. */
object AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = AppStore(scope, logger = { Log.w("JARVIS_STORE", it) })
    val orbHolder = OrbStateHolder()
    val audioBus = AudioVisualBus()

    private val levels = SyntheticLevels(audioBus)
    private val demo = SimDemoDriver(scope) { store.dispatch(it) }
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // Orb snapshot for the GL thread on every state change.
        scope.launch { store.state.collect { orbHolder.snapshot = toOrbSnapshot(it) } }
        // Effects are not executed by a transport yet (M5): log names only (never caption text).
        scope.launch {
            store.effects.collect { e ->
                Log.d("JARVIS_STORE", "effect ${e.logName()}")
                if (store.state.value.mode == Mode.SIM) demo.onEffect(e)
            }
        }
        // 50 Hz synthetic levels while simulating.
        scope.launch {
            while (true) {
                delay(20)
                val s = store.state.value
                if (s.mode == Mode.SIM) levels.step(s.orbState, monoMillis())
            }
        }
    }

    private fun Effect.logName(): String = if (this is Effect.ShowCaption) "ShowCaption" else toString()
}
