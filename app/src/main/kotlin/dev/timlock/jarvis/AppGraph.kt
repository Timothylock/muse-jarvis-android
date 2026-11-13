package dev.timlock.jarvis

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import dev.timlock.jarvis.audio.Earcons
import dev.timlock.jarvis.audio.MicSource
import dev.timlock.jarvis.audio.Player
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.secure.CredentialNames
import dev.timlock.jarvis.feature.pairing.ui.FlowExit
import dev.timlock.jarvis.feature.pairing.ui.PairingFlow
import dev.timlock.jarvis.core.secure.KeystoreCredentialStore
import dev.timlock.jarvis.core.model.OrbStateHolder
import dev.timlock.jarvis.core.model.UserConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.Event
import dev.timlock.jarvis.core.transport.TransportEvent
import dev.timlock.jarvis.core.store.EarconKind
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.store.HapticKind
import dev.timlock.jarvis.core.store.Intent
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
    /** App-lifetime scope (transports get their own child scopes from MainViewModel). */
    val appScope: CoroutineScope get() = scope
    lateinit var appContext: Context
        private set
    /** Encrypted credential files (§6.7). Created lazily: constructing it touches no keys. */
    val credentials by lazy { KeystoreCredentialStore(appContext) }
    val pairing by lazy { PairingController(appContext, credentials) { transport } }

    val store = AppStore(scope, logger = { Log.w("JARVIS_STORE", it) })
    val orbHolder = OrbStateHolder()
    val audioBus = AudioVisualBus()

    private val levels = SyntheticLevels(audioBus)
    lateinit var calls: CallController
        private set
    lateinit var devSettings: DevSettings
        private set
    lateinit var testInput: TestInputController
        private set
    lateinit var configStore: ConfigStore
        private set
    lateinit var night: NightController
        private set
    private val configMutex = Mutex()
    private val _settingsOpen = MutableStateFlow(false)
    /** The §4.5 settings overlay is shown (MainActivity hosts it; ADB `settings open|close`). */
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()
    fun setSettingsOpen(open: Boolean) { _settingsOpen.value = open }
    private lateinit var mic: MicSource
    private lateinit var earcons: Earcons

    private val _needsMicPermission = MutableStateFlow(false)
    /** True while the app wants RECORD_AUDIO and does not have it; MainActivity's helper requests it (§4.1 step 4). */
    val needsMicPermission: StateFlow<Boolean> = _needsMicPermission.asStateFlow()
    fun markMicPermissionRequested() { _needsMicPermission.value = false }
    private val _needsNotifPermission = MutableStateFlow(false)
    /** True once on entering REAL mode while POST_NOTIFICATIONS (API 33+) is not granted. */
    val needsNotificationPermission: StateFlow<Boolean> = _needsNotifPermission.asStateFlow()
    fun markNotificationPermissionRequested() { _needsNotifPermission.value = false }
    private val _perf = MutableStateFlow(PerfHudData())
    val perfHud: StateFlow<PerfHudData> = _perf.asStateFlow()
    lateinit var simController: SimController
        private set
    private var started = false

    private val _pairingFlow = MutableStateFlow<PairingFlow?>(null)
    /** The open pairing flow (§4.2), or null. MainActivity shows [PairingFlowScreen] while non-null. */
    val pairingFlow: StateFlow<PairingFlow?> = _pairingFlow.asStateFlow()
    private val _importRequests = MutableStateFlow(0)
    /** Increments when the flow was left via "Import pairing" (MainActivity then opens the import screen). */
    val importRequests: StateFlow<Int> = _importRequests.asStateFlow()

    /**
     * Opens the pairing flow at P1. [repair] = §4.4 re-pair: the stored token stays selected, unless the PairingLost
     * reason says the SDK token itself was revoked (reason contains "token"): then it is deleted and a note is shown.
     */
    fun openPairing(repair: Boolean = false) {
        scope.launch {
            synchronized(this@AppGraph) {
                if (_pairingFlow.value != null) return@launch
                val s = store.state.value
                val sim = s.mode == Mode.SIM
                var note: String? = null
                if (repair && !sim && (s.link as? LinkState.PairingLost)?.reason?.contains("token", ignoreCase = true) == true) {
                    credentials.delete(CredentialNames.SDK_TOKEN)
                    note = "Create a new token at gadgets.muse.ai"
                }
                lateinit var flow: PairingFlow
                flow = PairingFlow(
                    AppPairingDeps(sim, appContext, credentials, store, repair && !sim) { transport },
                    scope,
                    onExit = { exit ->
                        _pairingFlow.compareAndSet(flow, null)
                        if (exit == FlowExit.IMPORT) _importRequests.value += 1
                        flow.close()
                    },
                    startNote = note,
                    repair = repair,
                )
                _pairingFlow.value = flow
            }
        }
    }

    /** Persists then dispatches a config change (serialized; [f] sees the latest config). Returns the stored config. */
    suspend fun updateConfig(f: (UserConfig) -> UserConfig): UserConfig = configMutex.withLock {
        val c = f(store.state.value.config).sanitized()
        configStore.save(c)
        store.dispatch(Intent.SetConfig(c))
        c
    }

    /** Fire-and-forget [updateConfig] for UI callbacks. */
    fun updateConfigAsync(f: (UserConfig) -> UserConfig) { scope.launch { updateConfig(f) } }

    /** Active transport (set/cleared by MainViewModel); effects like ReplyCommand are forwarded to it. */
    @Volatile var transport: GadgetTransport? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        mic = MicSource(app, audioBus) { onMicPermissionMissing() }
        earcons = Earcons(app)
        calls = CallController(scope, { transport }, mic, Player(audioBus)) { store.dispatch(it) }
        devSettings = DevSettings(app)
        configStore = ConfigStore(app)
        night = NightController(store, scope)
        // §4.5 persisted settings -> store at startup; the mic gain follows the config.
        scope.launch { configMutex.withLock { store.dispatch(Intent.SetConfig(configStore.load())) } }
        scope.launch { store.state.map { it.config.micGainDb }.distinctUntilChanged().collect { mic.gainDb = it } }
        testInput = TestInputController(app, store, mic, calls, devSettings)
        // §7.7: persisted developer flag -> store (keeps the injector's `active` flag as is).
        scope.launch {
            devSettings.testInputEnabledFlow.collect { on ->
                store.dispatch(Intent.SetTestInput(on, store.state.value.testInputActive))
            }
        }
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
        // §6.8: the link service runs only in REAL mode; entering REAL also asks for the notification permission.
        scope.launch {
            store.state.map { it.mode }.distinctUntilChanged().collect { m ->
                // A pairing flow belongs to one mode; switching modes (sim exit, reset) ends it.
                _pairingFlow.value?.let { if (it.sim != (m == Mode.SIM) || m == null) it.cancel() }
                if (m == Mode.REAL) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) _needsNotifPermission.value = true
                    JarvisService.start(app)
                } else JarvisService.stop(app)
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

    /**
     * RECORD_AUDIO missing when a capture starts (Fable 17): request it, and in REAL mode end the call/PTT through the
     * normal HangUp intent (so the orb never says LISTENING over silence) and say why with a system caption. Sim keeps
     * running on its synthetic mic levels.
     */
    private fun onMicPermissionMissing() {
        _needsMicPermission.value = true
        if (store.state.value.mode != Mode.REAL) return
        store.dispatch(Intent.HangUp)
        store.dispatch(
            Event(TransportEvent.Transcript(Role.SYSTEM, "Microphone permission needed", true, TransportKind.REAL)),
        )
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
