package dev.timlock.jarvis

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import dev.timlock.jarvis.audio.Earcons
import dev.timlock.jarvis.audio.MicSource
import dev.timlock.jarvis.audio.Player
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.EarconKind
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.store.HapticKind
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.store.toOrbSnapshot
import dev.timlock.jarvis.core.model.PerfHudData
import dev.timlock.jarvis.render.orb.PerfStats
import dev.timlock.jarvis.transport.sim.SimController
import dev.timlock.jarvis.transport.sim.SimTransport
import dev.timlock.jarvis.transport.sim.SyntheticLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App-lifetime singletons (manual wiring, no DI). Started from [JarvisApp.onCreate]. */
object AppGraph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = AppStore(scope, logger = { Log.w("JARVIS_STORE", it) })
    val orbHolder = OrbStateHolder()
    val audioBus = AudioVisualBus()

    private val levels = SyntheticLevels(audioBus)
    private lateinit var calls: CallController
    private lateinit var mic: MicSource
    private lateinit var earcons: Earcons

    private val _needsMicPermission = MutableStateFlow(false)
    /** True while the app wants RECORD_AUDIO and does not have it; MainActivity's helper requests it (§4.1 step 4). */
    val needsMicPermission: StateFlow<Boolean> = _needsMicPermission.asStateFlow()
    fun markMicPermissionRequested() { _needsMicPermission.value = false }
    private val _perf = MutableStateFlow(PerfHudData())
    val perfHud: StateFlow<PerfHudData> = _perf.asStateFlow()
    lateinit var simController: SimController
        private set
    private var started = false

    /** Active transport (set/cleared by MainViewModel); effects like ReplyCommand are forwarded to it. */
    @Volatile var transport: GadgetTransport? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        mic = MicSource(app, audioBus) { _needsMicPermission.value = true }
        earcons = Earcons(app)
        calls = CallController(scope, { transport }, mic, Player(audioBus)) { store.dispatch(it) }
        simController = SimController(context.applicationContext, store, scope) { transport as? SimTransport }
        simController.start()
        // Orb snapshot for the GL thread on every state change.
        scope.launch { store.state.collect { orbHolder.snapshot = toOrbSnapshot(it) } }
        // Contextual RECORD_AUDIO request on entering sim mode (§4.1 step 4); the first PTT press also asks (MicSource).
        scope.launch {
            store.state.map { it.mode }.distinctUntilChanged().collect { m ->
                if (m == Mode.SIM && !mic.hasPermission()) _needsMicPermission.value = true
            }
        }
        // Effects: log names only (never caption text).
        scope.launch {
            store.effects.collect { e ->
                Log.d("JARVIS_STORE", "effect ${e.logName()}")
                when (e) {
                    is Effect.ReplyCommand -> transport?.replyCommand(e.invocationId, e.result)
                    is Effect.Earcon -> when (e.kind) {
                        EarconKind.TIMER_CHIME -> playTimerChime()
                        EarconKind.CHIRP -> earcons.playChirp()
                        EarconKind.ERROR -> earcons.playError()
                    }
                    is Effect.Haptic -> if (e.kind == HapticKind.TICK) earcons.tick()
                    else -> Unit
                }
            }
        }
        // Call/audio effects: one coroutine, strictly in order.
        scope.launch { store.effects.collect { calls.handle(it) } }
        // Perf HUD sampler (1 Hz, only while the HUD is on; works in real mode too).
        scope.launch {
            while (true) {
                delay(1000)
                if (!store.state.value.perfHud) continue
                _perf.value = PerfHudData(
                    fps = PerfStats.fps, p50Ms = PerfStats.p50Ms, p95Ms = PerfStats.p95Ms,
                    tier = PerfStats.tier.name, particles = PerfStats.particles, thermal = PerfStats.thermal,
                    pssMb = (android.os.Debug.getPss() / 1024L).toInt(),
                )
            }
        }
        // 50 Hz synthetic levels while simulating: only the mic channel, and only while RECORD_AUDIO is missing
        // (the real mic and the Player's analysis drive the bus otherwise).
        scope.launch {
            while (true) {
                delay(20)
                val s = store.state.value
                if (s.mode == Mode.SIM) levels.step(s.orbState, monoMillis(), driveOut = false, driveMic = !mic.hasPermission())
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
