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
    /** Deterministic counter for auto-generated panel ids. */
    val idSeq: Int = 0,
    /** Arrival times of recent display commands for the 10-per-5 s rate limit (§5.3). */
    val displayHits: List<Long> = emptyList(),
    val pulseAtMs: Long = 0L,
    val attentionAtMs: Long = 0L,
    val shakeAtMs: Long = 0L,
    val countdownStartMs: Long = 0L,
    val countdownEndMs: Long = 0L,
    val night: Boolean = false,
    /** Perf HUD overlay (§3.6), toggled from the sim drawer or ADB `perfhud`; available in real mode too. */
    val perfHud: Boolean = false,
    /** Caption bar (§2.5). Null = show the state word. Owned by the reducer (M5); fades 8 s after the call goes idle. */
    val caption: dev.timlock.jarvis.core.model.Caption? = null,
    /** When the current call became Ready (status bar CALL mm:ss), 0 = no call. */
    val callSinceMs: Long = 0L,
    /** Monotonic ms at which the caption is cleared (8 s after the call goes idle), or null. */
    val captionClearAt: Long? = null,
    /** Realtime admission failure times within the last 10 min (§4.3.1). */
    val admitFailures: List<Long> = emptyList(),
    /** While now < this, PTT uses the fallback path (chat + TTS); null = realtime. */
    val fallbackUntil: Long? = null,
    /** The current turn is on the fallback path (mic buffers; FallbackSend on release). */
    val fallbackTurn: Boolean = false,
) {
    /** Derived: number of panels in [panels]. */
    val panelCount: Int get() = panels.panels.size

    /** Panels in the dock column (placement DOCK); only these make the layout DOCKED. */
    val dockCount: Int get() = panels.panels.count { it.placement == dev.timlock.jarvis.core.model.PanelPlacement.DOCK }

    val latched: Boolean get() = ptt == PttState.Latched

    /** Earliest pending deadline, for scheduling a Tick. */
    fun nextDeadline(): Long? = (
        listOfNotNull(thinkDeadline, standbyDeadline, errorDeadline, pttCapAt, pttWarnAt, settleDeadline, layoutReturnAt, captionClearAt) +
            panels.panels.mapNotNull { it.expiresAtMs } +
            panels.panels.mapNotNull { (it.content as? dev.timlock.jarvis.core.model.PanelContent.Timer)?.takeIf { t -> !t.chimed }?.endsAtMs }
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
    /** Forget realtime admission failures and leave the fallback window (sim scenario start; each scenario starts clean). */
    data object ResetFallback : Intent

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

    /** Deprecated no-op: the panel count is derived from [AppState.panels]. */
    data class PanelCount(val count: Int) : Intent
    data object DisplayPulse : Intent
    data object AttentionPulse : Intent
    data class SetNight(val night: Boolean) : Intent
    data class SetPerfHud(val on: Boolean) : Intent
    /** Result of [Effect.FallbackSend]. [replyText] may be server content: never log it. */
    data class FallbackResult(val replyText: String?, val hasAudio: Boolean, val error: String?) : Intent
}

/** A transport event entering the store. */
data class Event(val event: TransportEvent) : Input
