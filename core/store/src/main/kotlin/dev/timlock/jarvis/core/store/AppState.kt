package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.transport.TransportEvent

/** Push-to-talk control state (§4.3). */
sealed interface PttState {
    data object Up : PttState
    data class Held(val sinceMs: Long) : PttState
    /** Tap-latched talk ("tap to start, tap to stop"). */
    data object Latched : PttState
}

enum class CallStatus { None, Admitting, Ready }

/**
 * All times are monotonic millis ([dev.timlock.jarvis.core.model.monoMillis]).
 *
 * [machine] is the true §3.1 state; [orbState] is what is displayed: [forcedOrb] ?: [machine]
 * (sim debug override, null = auto). [orbPrevious]/[orbStateSince] track the displayed state.
 */
data class AppState(
    val mode: Mode? = null,
    val link: LinkState = LinkState.Unpaired,
    val machine: OrbState = OrbState.BOOT,
    val orbState: OrbState = OrbState.BOOT,
    val orbPrevious: OrbState = OrbState.BOOT,
    val orbStateSince: Long = 0L,
    val forcedOrb: OrbState? = null,
    /** Monotonic millis until which the sim interstitial is shown, or null. */
    val simInterstitialUntil: Long? = null,

    val ptt: PttState = PttState.Up,
    val call: CallStatus = CallStatus.None,
    val turnInFlight: Boolean = false,
    /** Playback is audible (between SinkFirstAudible and SinkDrained). */
    val sinkPlaying: Boolean = false,
    /** Stable state ERROR returns to (recomputed from the link on exit). */
    val priorStable: OrbState = OrbState.IDLE,
    val advisoryErrors: Int = 0,

    // Deadlines (null = none pending)
    val thinkDeadline: Long? = null,
    val standbyDeadline: Long? = null,
    val errorDeadline: Long? = null,
    val pttCapAt: Long? = null,
    val pttWarnAt: Long? = null,
    val settleDeadline: Long? = null,
    val layoutReturnAt: Long? = null,

    // Orb snapshot feeders
    val layout: OrbLayout = OrbLayout.FIRST_RUN,
    val layoutSinceMs: Long = 0L,
    /** Visible panels (§2.5); owned by the panel manager. [panelCount] mirrors panels.size. */
    val panels: PanelStack = PanelStack(),
    val panelCount: Int = 0,
    /** Arrival times of recent display commands for the 10-per-5 s rate limit (§5.3). */
    val displayHits: List<Long> = emptyList(),
    val pulseAtMs: Long = 0L,
    val attentionAtMs: Long = 0L,
    val shakeAtMs: Long = 0L,
    val countdownStartMs: Long = 0L,
    val countdownEndMs: Long = 0L,
    val night: Boolean = false,
) {
    val latched: Boolean get() = ptt == PttState.Latched

    /** Earliest pending deadline, for scheduling a Tick. */
    fun nextDeadline(): Long? = listOfNotNull(
        thinkDeadline, standbyDeadline, errorDeadline, pttCapAt, pttWarnAt, settleDeadline, layoutReturnAt,
    ).minOrNull()
}

sealed interface Input

sealed interface Intent : Input {
    data class ChooseMode(val mode: Mode) : Intent
    /** Clears the stored mode (back to first run). */
    data object ResetMode : Intent
    data class LinkChanged(val link: LinkState) : Intent
    data object BootComplete : Intent
    data object ClearInterstitial : Intent

    data object PttDown : Intent
    data object PttUp : Intent
    /** Double-tap on the orb / "End" in the status bar. */
    data object HangUp : Intent
    /** Sim debug override; null = auto. Ignored unless mode == SIM. */
    data class ForceOrb(val state: OrbState?) : Intent
    data class CallPhaseChanged(val phase: CallPhase) : Intent
    /** First audible reply frame reached the speaker (latency compensated). */
    data object SinkFirstAudible : Intent
    /** Playback drained; the reducer applies the 200 ms settle. */
    data object SinkDrained : Intent
    data object Tick : Intent

    data object PairingOpen : Intent
    data class PairingClosed(val paired: Boolean) : Intent

    /** User: swipe right / tap × (§2.5). */
    data class DismissPanel(val id: String) : Intent
    /** User: long-press toggles pin (pinned = sticky, pin glyph). */
    data class PinPanel(val id: String, val pinned: Boolean) : Intent
    /** User: tap a collapsed title strip to expand it (another panel collapses instead). */
    data class ExpandPanel(val id: String) : Intent
    /** User: tap a ringing timer to acknowledge it (removes it). */
    data class AcknowledgeTimer(val id: String) : Intent
    /** Restore persisted sticky panels/timers at boot (real mode only, §5.5). */
    data class RestorePanels(val stack: PanelStack) : Intent

    /** Legacy/manual override of the panel count (tests); the panel manager normally derives it. */
    data class PanelCount(val count: Int) : Intent
    data object DisplayPulse : Intent
    data object AttentionPulse : Intent
    data class SetNight(val night: Boolean) : Intent
}

/** A transport event entering the store. */
data class Event(val event: TransportEvent) : Input
