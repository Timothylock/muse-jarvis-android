package dev.timlock.jarvis.transport.sim

import android.content.Context
import android.util.Log
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.DisplayPreset
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.ScenarioInfo
import dev.timlock.jarvis.core.model.ScenarioRun
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.model.SimLogEntry
import dev.timlock.jarvis.core.model.SimSettings
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.AppState
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.CallStatus
import dev.timlock.jarvis.core.store.Intent
import dev.timlock.jarvis.core.store.PttState
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "JARVIS_SIM"
private const val LOG_MAX = 200
private const val OFFLINE_FAULT_MS = 8000L

/**
 * The single entry point for everything simulation-specific (§7): scenario library + engine, free-play
 * auto-replies, faults, display presets, latency/time-scale settings and the event log. Owned by AppGraph for
 * the app's lifetime; [transport] returns the active [SimTransport] (null when not in sim mode), because a new
 * transport is created on every mode switch. Every action is a no-op returning an error string unless a
 * SimTransport is active.
 *
 * Results that ADB must report are returned as String? : null = ok, otherwise a short error.
 */
class SimController(
    private val context: Context,
    private val store: AppStore,
    private val scope: CoroutineScope,
    private val transport: () -> SimTransport?,
) {
    private val _scenarios = MutableStateFlow<List<ScenarioInfo>>(emptyList())
    val scenarios: StateFlow<List<ScenarioInfo>> = _scenarios.asStateFlow()
    private val _run = MutableStateFlow(ScenarioRun())
    val run: StateFlow<ScenarioRun> = _run.asStateFlow()
    private val _log = MutableStateFlow<List<SimLogEntry>>(emptyList())
    val log: StateFlow<List<SimLogEntry>> = _log.asStateFlow()
    private val _settings = MutableStateFlow(SimSettings())
    val settings: StateFlow<SimSettings> = _settings.asStateFlow()

    private val anchors = AnchorTracker(::monoMillis)
    @Volatile private var library: Map<String, Scenario> = emptyMap()
    private var engine: ScenarioEngine? = null
    private var engineJob: Job? = null
    private var freePlayJob: Job? = null
    private var lastState: AppState? = null
    private var attached: SimTransport? = null
    private var freeLine = 0
    private var freeTurn = 0

    /** Call once from AppGraph.start(): loads the library and starts observing the store (free play, anchors). */
    fun start() {
        reload()
        scope.launch { store.state.collect { onState(it) } }
    }

    // ---- library ------------------------------------------------------------------------------

    /** Re-scan assets/scenarios + getExternalFilesDir("scenarios"); external files override bundled ids. */
    fun reload(): String? {
        val found = LinkedHashMap<String, Pair<Scenario, Boolean>>()
        fun load(name: String, text: String, external: Boolean) {
            when (val r = ScenarioParser.parse(text)) {
                is ParseResult.Ok -> found[r.scenario.id] = r.scenario to external
                is ParseResult.Error -> logLine(jsonLine("cmd" to "scenario_rejected", "file" to name, "error" to r.message))
            }
        }
        try {
            context.assets.list("scenarios")?.filter { it.endsWith(".json") }?.sorted()?.forEach { n ->
                runCatching { context.assets.open("scenarios/$n").bufferedReader().use { it.readText() } }
                    .onSuccess { load(n, it, false) }
                    .onFailure { logLine(jsonLine("cmd" to "scenario_rejected", "file" to n, "error" to "unreadable")) }
            }
            context.getExternalFilesDir("scenarios")?.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                runCatching { f.readText() }
                    .onSuccess { load(f.name, it, true) }
                    .onFailure { logLine(jsonLine("cmd" to "scenario_rejected", "file" to f.name, "error" to "unreadable")) }
            }
        } catch (e: Exception) {
            return "reload failed: ${e.message}"
        }
        library = found.mapValues { it.value.first }
        val order = ORDER.withIndex().associate { it.value to it.index }
        _scenarios.value = found.values.sortedWith(compareBy({ order[it.first.id] ?: Int.MAX_VALUE }, { it.first.id }))
            .map { ScenarioInfo(it.first.id, it.first.title, it.second) }
        logLine(jsonLine("cmd" to "reload", "ok" to true, "count" to found.size))
        return null
    }

    // ---- scenario control ----------------------------------------------------------------------

    /** Start scenario [id] (stops any running one). [autoPttMs] != null forces unattended PTT presses. */
    fun play(id: String, timescale: Float? = null, autoPttMs: Long? = null): String? {
        val t = transport() ?: return "sim transport not active"
        val sc = library[id] ?: return "unknown scenario"
        stopEngine()
        val ts = (timescale ?: _settings.value.timescale).coerceIn(0.25f, 4f)
        t.timescale = ts
        t.admitMs = _settings.value.admitMs
        freePlayJob?.cancel()
        store.dispatch(Intent.ResetFallback) // scenarios must not inherit another run's fallback window
        val eng = ScenarioEngine(scope, host, anchors, ts, autoPttMs)
        engine = eng
        _run.value = ScenarioRun(id = id, step = 0, total = sc.steps.size, timescale = ts)
        logLine(jsonLine("cmd" to "scenario", "action" to "start", "id" to id, "timescale" to ts, "auto_ptt_ms" to (autoPttMs ?: sc.autoPttMs)))
        sc.note?.let { logLine(jsonLine("cmd" to "note", "id" to id, "note" to it)) }
        engineJob = eng.start(sc)
        return null
    }

    fun stop() {
        val id = _run.value.id
        stopEngine()
        _run.update { it.copy(id = null, paused = false) }
        if (id != null) logLine(jsonLine("cmd" to "scenario", "action" to "stop", "id" to id))
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        engineJob = null
        restoreTransportSettings()
    }

    private fun restoreTransportSettings() {
        transport()?.let { it.admitMs = _settings.value.admitMs; it.timescale = _settings.value.timescale }
    }

    fun pause(paused: Boolean) {
        val e = engine ?: return
        e.pause(paused)
        _run.update { it.copy(paused = paused) }
        logLine(jsonLine("cmd" to "scenario", "action" to (if (paused) "pause" else "resume"), "id" to _run.value.id))
    }

    // ---- faults / settings -----------------------------------------------------------------------

    fun fault(kind: SimFault): String? {
        val t = transport() ?: return "sim transport not active"
        val err = t.applyFault(kind, if (kind == SimFault.OFFLINE) OFFLINE_FAULT_MS else null)
        logLine(jsonLine("cmd" to "fault", "kind" to kind.wire, "ok" to (err == null), "error" to err))
        return err
    }

    /** Sim re-pair after `revoke` (stands in for the P1-P4 flow until M7): link back online. */
    fun repair(): String? {
        val t = transport() ?: return "sim transport not active"
        t.repair()
        return null
    }

    fun updateSettings(settings: SimSettings) {
        val s = SimSettings(settings.admitMs.coerceIn(0, 5000), settings.thinkMs.coerceIn(0, 20000), settings.timescale.coerceIn(0.25f, 4f))
        _settings.value = s
        if (engine == null) restoreTransportSettings()
    }

    // ---- display injection -------------------------------------------------------------------------

    /** Inject a display command through validation + replyCommand; returns the reply (null = timeout). */
    suspend fun inject(name: String, paramsJson: String): CommandResult? {
        val t = transport()
        if (t == null) {
            logLine(jsonLine("cmd" to "display", "ok" to false, "error" to "sim transport not active"))
            return null
        }
        val r = t.injectCommand(name, paramsJson)
        logLine(jsonLine("cmd" to "display", "name" to name, "ok" to (r?.ok ?: false), "error" to (if (r == null) "no reply" else r.error)))
        return r
    }

    /** One-tap presets; returns the (last) reply. */
    suspend fun preset(p: DisplayPreset): CommandResult? = when (p) {
        DisplayPreset.CARD -> inject("display.card", Presets.CARD)
        DisplayPreset.STATS -> inject("display.stats", Presets.STATS)
        DisplayPreset.LIST -> inject("display.list", Presets.LIST)
        DisplayPreset.TIMER -> inject("display.timer", Presets.TIMER)
        DisplayPreset.CLEAR_ALL -> inject("display.clear", "{}")
        DisplayPreset.FLOOD_X6 -> {
            var last: CommandResult? = null
            for (i in 1..6) { last = inject("display.card", Presets.flood(i)); delay(120) }
            last
        }
        DisplayPreset.INVALID_MISSING_TITLE -> inject("display.card", Presets.MISSING_TITLE)
        DisplayPreset.INVALID_7_METRICS -> inject("display.stats", Presets.SEVEN_METRICS)
        DisplayPreset.INVALID_LONG_LABEL -> inject("display.stats", Presets.LONG_LABEL)
        DisplayPreset.INVALID_BAD_ID -> inject("display.card", Presets.BAD_ID)
    }

    // ---- store observation: anchors + free play ---------------------------------------------------

    private fun onState(s: AppState) {
        transport()?.let { t ->
            if (t !== attached) {
                attached = t
                t.logSink = { line -> appendLog(line) }
                restoreTransportSettings()
            }
        }
        val prev = lastState
        lastState = s
        if (prev == null) return
        val wasDown = prev.ptt != PttState.Up
        val isDown = s.ptt != PttState.Up
        if (!wasDown && isDown) {
            anchors.fire(Anchor.PTT_DOWN)
            freePlayJob?.cancel()
        } else if (wasDown && !isDown) {
            anchors.fire(Anchor.PTT_UP)
            if (_run.value.id == null && s.call != CallStatus.None) startFreePlay()
        }
        if (!prev.sinkPlaying && s.sinkPlaying) anchors.fire(Anchor.SPEECH_START)
        if (prev.sinkPlaying && !s.sinkPlaying) anchors.fire(Anchor.SPEECH_END)
    }

    /** Free play (no scenario): answer every PTT turn like `hello_short` with a canned line. */
    private fun startFreePlay() {
        val t = transport() ?: return
        freePlayJob?.cancel()
        freePlayJob = scope.launch {
            val ts = _settings.value.timescale
            val id = "sim-free-${++freeTurn}"
            host.emit(TransportEvent.TurnStarted(id, TransportKind.SIM))
            delay((_settings.value.thinkMs / ts).toLong())
            val i = freeLine++
            val ms = 2000L + (i % 3) * 500L
            val base = anchors.count(Anchor.SPEECH_END)
            if (t.speak(FREE_LINES[i % FREE_LINES.size], (ms / ts).toLong())) {
                withTimeoutOrNull((ms / ts).toLong() + 10_000) { anchors.awaitCountAbove(Anchor.SPEECH_END, base) }
            } else logLine("free play: no open call, skipped")
            host.emit(TransportEvent.TurnSettled(id, TransportKind.SIM))
        }
    }

    // ---- logging --------------------------------------------------------------------------------------

    private fun appendLog(text: String) {
        _log.update { (it + SimLogEntry(System.currentTimeMillis(), text)).takeLast(LOG_MAX) }
    }

    private fun logLine(line: String) {
        Log.i(TAG, line)
        appendLog(line)
    }

    private fun snapshot(): SimSnapshot {
        val s = store.state.value
        return SimSnapshot(
            orb = s.orbState.name.lowercase(),
            panelCount = s.panelCount,
            call = s.call.name.lowercase(),
            link = when (s.link) {
                LinkState.Unpaired -> "unpaired"
                LinkState.Connecting -> "connecting"
                is LinkState.Online -> "online"
                is LinkState.Offline -> "offline"
                is LinkState.PairingLost -> "pairing_lost"
            },
            panelIds = s.panels.panels.map { it.id },
        )
    }

    private val host = object : ScenarioHost {
        override fun nowMs() = monoMillis()
        override fun pttDown() = store.dispatch(Intent.PttDown)
        override fun pttUp() = store.dispatch(Intent.PttUp)
        override fun snapshot() = this@SimController.snapshot()

        override fun emit(event: TransportEvent) {
            val t = transport()
            if (t == null) { appendLog("event dropped (no sim transport)"); return }
            t.emit(event)
            appendLog(when (event) {
                is TransportEvent.TurnStarted -> "event turn_started ${event.id}"
                is TransportEvent.TurnSettled -> "event turn_settled ${event.id}"
                is TransportEvent.Transcript -> "event transcript ${if (event.role == Role.USER) "user" else "muse"}: ${event.text}"
                else -> "event ${event::class.simpleName}"
            })
        }

        override suspend fun command(name: String, paramsJson: String): CommandResult? =
            transport()?.injectCommand(name, paramsJson)

        override fun speak(text: String, ms: Long): Boolean = transport()?.speak(text, ms) ?: false

        private val NOT_ACTIVE = "sim transport not active"
        override fun fault(kind: SimFault, ms: Long?): String? = transport()?.applyFault(kind, ms) ?: if (transport() == null) NOT_ACTIVE else null
        override fun setAdmission(ms: Long) { transport()?.admitMs = ms }
        override fun remoteStop(): String? = transport()?.remoteStop() ?: if (transport() == null) NOT_ACTIVE else null
        override fun takeover(): String? = transport()?.takeover() ?: if (transport() == null) NOT_ACTIVE else null
        override fun repair(): String? {
            val t = transport() ?: return NOT_ACTIVE
            t.repair()
            return null
        }

        override suspend fun prepareStart() {
            val t = transport() ?: return
            t.resetFaults()
            store.dispatch(Intent.HangUp)
            t.activeCall()?.end("hangup")
            t.injectCommand("display.clear", "{}")
            delay(150)
        }

        override fun log(line: String) = logLine(line)

        override fun onProgress(step: Int, total: Int, asserts: Int, failed: Int) {
            _run.update { if (it.id == null) it else it.copy(step = step, total = total, asserts = asserts, failed = failed) }
        }

        override fun onDone(asserts: Int, failed: Int) {
            _run.update { it.copy(id = null, paused = false, asserts = asserts, failed = failed) }
            engine = null
            restoreTransportSettings()
        }
    }

    private companion object {
        val ORDER = listOf(
            "hello_short", "weather_mid_reply", "card_while_thinking", "planning_outline", "long_list", "panel_flood",
            "sticky_overflow", "multi_turn", "barge_in_long", "slow_think", "error_offline_mid_reply", "token_revoked",
            "admission_fails", "send_failure", "ambient_push_timer", "soak_perf", "invalid_actions", "call_takeover",
        )
        val FREE_LINES = listOf(
            "Simulated reply. Nothing here is real.",
            "This is the simulator answering. Press and hold to talk again.",
            "Sim mode is active, so this voice is just babble.",
            "Got it. That was a canned response from the simulator.",
        )
    }
}

/** Sample display content for the drawer presets (§5.6 style). */
internal object Presets {
    const val CARD = """{"id":"preset-card","title":"Saturday plans","body":"Ferry at 07:40 from Anacortes. Hotel check-in after 15:00.","bullets":["Pack chargers","Rain jacket","Confirm reservation"]}"""
    const val STATS = """{"id":"preset-stats","title":"Seattle · Today","metrics":[{"label":"Now","value":12,"unit":"°C"},{"label":"High","value":15,"unit":"°C"},{"label":"Rain","value":70,"unit":"%"},{"label":"Wind","value":"14 SW","unit":"km/h"}],"caption":"Clearing after 12:00"}"""
    const val LIST = """{"id":"preset-list","title":"Orcas trip","items":["Book ferry",{"text":"Anacortes 07:40","level":1},"Pack",{"text":"Chargers","level":1,"done":true},"Confirm hotel"]}"""
    const val TIMER = """{"id":"preset-timer","label":"Tea","seconds":90}"""
    fun flood(i: Int) = """{"id":"flood-$i","title":"Flood card $i","body":"Rapid-fire push number $i of 6."}"""
    const val MISSING_TITLE = """{"body":"This card has no title."}"""
    val SEVEN_METRICS = """{"id":"inv-7","title":"Seven metrics","metrics":[""" +
        (1..7).joinToString(",") { """{"label":"M$it","value":$it}""" } + "]}"
    val LONG_LABEL = """{"id":"inv-label","title":"Long label","metrics":[{"label":"${"L".repeat(200)}","value":1}]}"""
    const val BAD_ID = """{"id":"bad id!","title":"Bad id"}"""
}
