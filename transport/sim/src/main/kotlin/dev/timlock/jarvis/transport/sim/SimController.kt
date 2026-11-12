package dev.timlock.jarvis.transport.sim

import android.content.Context
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.DisplayPreset
import dev.timlock.jarvis.core.model.ScenarioInfo
import dev.timlock.jarvis.core.model.ScenarioRun
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.model.SimLogEntry
import dev.timlock.jarvis.core.model.SimSettings
import dev.timlock.jarvis.core.store.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M4 CONTRACT (public API is fixed; Track A implements the bodies, Track B wires and calls it).
 *
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

    /** Call once from AppGraph.start(): loads the library and starts observing the store (free play, anchors). */
    fun start() { TODO("M4 Track A") }

    /** Re-scan assets/scenarios + getExternalFilesDir("scenarios"); external files override bundled ids. */
    fun reload(): String? = TODO("M4 Track A")

    /** Start scenario [id] (stops any running one). [autoPttMs] != null forces unattended PTT presses. */
    fun play(id: String, timescale: Float? = null, autoPttMs: Long? = null): String? = TODO("M4 Track A")
    fun stop() { TODO("M4 Track A") }
    fun pause(paused: Boolean) { TODO("M4 Track A") }

    fun fault(kind: SimFault): String? = TODO("M4 Track A")
    /** Sim re-pair after `revoke` (stands in for the P1-P4 flow until M7): link back online. */
    fun repair(): String? = TODO("M4 Track A")

    fun updateSettings(settings: SimSettings) { TODO("M4 Track A") }

    /** Inject a display command through validation + replyCommand; returns the reply (null = timeout). */
    suspend fun inject(name: String, paramsJson: String): CommandResult? = TODO("M4 Track A")
    /** One-tap presets; returns the (last) reply. */
    suspend fun preset(p: DisplayPreset): CommandResult? = TODO("M4 Track A")
}
