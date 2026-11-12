package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.LeaderAnchor
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbSnapshot
import dev.timlock.jarvis.core.model.monoMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single state holder; all changes go through [dispatch] -> [reduce]. Thread-safe.
 * After each dispatch a single [Intent.Tick] is scheduled at the earliest pending deadline
 * (the previous one is cancelled). [effects] carries the reducer's side-effect requests.
 */
class AppStore(
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = ::monoMillis,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var tickJob: Job? = null

    fun dispatch(input: Input) {
        synchronized(lock) {
            val now = clock()
            val r = reduce(_state.value, input, now, logger)
            _state.value = r.state
            for (e in r.effects) _effects.tryEmit(e)
            reschedule(r.state)
        }
    }

    private fun reschedule(s: AppState) {
        tickJob?.cancel()
        tickJob = null
        val due = s.nextDeadline() ?: return
        tickJob = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            dispatch(Intent.Tick)
        }
    }
}

/** Maps store state to the renderer contract. [OrbSnapshot.sinceMs] etc. are tracked in [AppState]. */
fun toOrbSnapshot(s: AppState): OrbSnapshot = OrbSnapshot(
    leaders = leadersFor(s),
    state = s.orbState,
    previous = s.orbPrevious,
    sinceMs = s.orbStateSince,
    sim = s.mode == Mode.SIM,
    layout = s.layout,
    layoutSinceMs = s.layoutSinceMs,
    pulseAtMs = s.pulseAtMs,
    attentionAtMs = s.attentionAtMs,
    countdownStartMs = s.countdownStartMs,
    countdownEndMs = s.countdownEndMs,
    latched = s.latched,
    shakeAtMs = s.shakeAtMs,
    night = s.night,
)

/** GL leader anchors (H4/C9/C15): one per DOCK panel, top first, only while DOCKED. y matches PanelStackView. */
internal fun leadersFor(s: AppState): List<LeaderAnchor> {
    if (s.layout != OrbLayout.DOCKED) return emptyList()
    var top = 64
    val out = ArrayList<LeaderAnchor>(3)
    for (p in PanelManager.dockPanels(s.panels.panels).take(s.config.maxPanels)) {
        val h = PanelHeights.estimatePx(p)
        out += LeaderAnchor(
            yPx = top + h / 2f, revealStartMs = p.revealStartMs,
            pulseAtMs = if (p.version > 0) p.updatedAtMs else 0L,
        )
        top += h + PanelManager.GAP_PX
    }
    return out
}
