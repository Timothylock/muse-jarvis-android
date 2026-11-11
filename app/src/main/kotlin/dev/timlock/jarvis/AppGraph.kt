package dev.timlock.jarvis

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.EarconKind
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.transport.GadgetTransport
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

    /** Active transport (set/cleared by MainViewModel); effects like ReplyCommand are forwarded to it. */
    @Volatile var transport: GadgetTransport? = null

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
                when (e) {
                    is Effect.ReplyCommand -> transport?.replyCommand(e.invocationId, e.result)
                    is Effect.Earcon -> if (e.kind == EarconKind.TIMER_CHIME) playTimerChime()
                    else -> Unit
                }
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

    /** ~3 short beeps; ToneGenerator is released after the pattern. */
    private fun playTimerChime() {
        scope.launch {
            val tg = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (t: Throwable) { return@launch }
            try {
                repeat(3) {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                    delay(350)
                }
            } finally {
                tg.release()
            }
        }
    }

    // Names only: payloads/captions/results can carry server text.
    private fun Effect.logName(): String = when (this) {
        is Effect.ShowCaption -> "ShowCaption"
        is Effect.ReplyCommand -> "ReplyCommand"
        is Effect.PersistPanels -> "PersistPanels"
        else -> toString()
    }
}
