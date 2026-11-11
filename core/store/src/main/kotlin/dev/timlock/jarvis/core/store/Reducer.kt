package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.OrbState.BOOT
import dev.timlock.jarvis.core.model.OrbState.CONNECTING
import dev.timlock.jarvis.core.model.OrbState.ERROR
import dev.timlock.jarvis.core.model.OrbState.IDLE
import dev.timlock.jarvis.core.model.OrbState.LISTENING
import dev.timlock.jarvis.core.model.OrbState.OFFLINE
import dev.timlock.jarvis.core.model.OrbState.PAIRING
import dev.timlock.jarvis.core.model.OrbState.SPEAKING
import dev.timlock.jarvis.core.model.OrbState.STANDBY
import dev.timlock.jarvis.core.model.OrbState.THINKING
import dev.timlock.jarvis.core.model.OrbState.UNPAIRED
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.transport.TransportEvent

const val SIM_INTERSTITIAL_MS = 1500L
const val THINK_TIMEOUT_MS = 60_000L
const val STANDBY_WINDOW_MS = 45_000L
/** 80 ms in + 600 ms red + 2.5 s amber (the phases are visual only). */
const val ERROR_TOTAL_MS = 3_100L
const val PTT_CAP_MS = 60_000L
const val PTT_WARN_MS = 55_000L
const val TAP_MS = 250L
const val SETTLE_MS = 200L
const val LAYOUT_RETURN_MS = 1_200L
const val MIC_TAIL_MS = 2_000L

const val REASON_SESSION_DEAD = "session_dead"
const val CAPTION_OFFLINE = "Offline — reconnecting"
const val CAPTION_TAKEOVER = "Call moved to another device"
const val CAPTION_SLOW = "Muse is taking too long"

private val TAKEOVER_MARKERS = listOf("takeover", "taken_over", "another_device", "moved")
private val ACTIVE = setOf(CONNECTING, LISTENING, THINKING, SPEAKING, STANDBY)

/**
 * Pure reducer: (state, input, now) -> state + effects. [now] is monotonic millis.
 * Due deadlines are expired first on every call, so [Intent.Tick] only needs to be a "wake up".
 * [log] receives diagnostics such as MODE_MISMATCH (never server text).
 */
fun reduce(state: AppState, input: Input, now: Long, log: (String) -> Unit = {}): Reduction {
    if (input is Event && input.event.kind.toMode() != state.mode) {
        log("MODE_MISMATCH event=${input.event::class.simpleName} kind=${input.event.kind} mode=${state.mode}")
        return Reduction(state)
    }
    if (input is Intent.ForceOrb && state.mode != Mode.SIM) {
        log("FORCE_IGNORED mode=${state.mode}")
        return Reduction(state)
    }
    val c = Ctx(state, now)
    c.expire()
    c.handle(input)
    c.finish()
    return Reduction(c.s, c.fx)
}

private fun TransportKind.toMode(): Mode = if (this == TransportKind.SIM) Mode.SIM else Mode.REAL

/** The rest state implied by mode + link. In SIM a Connecting link counts as up (sim is "ready" at boot). */
internal fun stableOrb(s: AppState): OrbState = when {
    s.mode == null -> UNPAIRED
    else -> when (s.link) {
        LinkState.Unpaired, is LinkState.PairingLost -> UNPAIRED
        is LinkState.Online -> IDLE
        LinkState.Connecting -> if (s.mode == Mode.SIM) IDLE else OFFLINE
        is LinkState.Offline -> OFFLINE
    }
}

private class Ctx(var s: AppState, val now: Long) {
    val fx = ArrayList<Effect>(4)
    val m: OrbState get() = s.machine
    val callOpen: Boolean get() = s.call != CallStatus.None

    fun emit(e: Effect) { fx += e }
    fun go(o: OrbState) { s = s.copy(machine = o) }
    fun goStable() = go(stableOrb(s))

    // ---- shared transitions -------------------------------------------------------------

    /** Drops the call and everything tied to it. [hangUp] = we initiate the end (vs. remote). */
    fun teardown(hangUp: Boolean) {
        if (hangUp && callOpen) emit(Effect.HangUpCall)
        if (callOpen || s.ptt != PttState.Up) emit(Effect.MicStop)
        s = s.copy(
            call = CallStatus.None, ptt = PttState.Up, turnInFlight = false, sinkPlaying = false,
            thinkDeadline = null, standbyDeadline = null, settleDeadline = null, pttCapAt = null, pttWarnAt = null,
        )
    }

    /** Call/turn state is deliberately preserved (think timeout keeps the call open). */
    fun enterError(caption: String?) {
        s = s.copy(
            machine = ERROR, priorStable = stableOrb(s), errorDeadline = now + ERROR_TOTAL_MS,
            thinkDeadline = null, standbyDeadline = null, settleDeadline = null,
            pttCapAt = null, pttWarnAt = null, ptt = PttState.Up,
        )
        emit(Effect.Earcon(EarconKind.ERROR))
        caption?.let { emit(Effect.ShowCaption(it)) }
    }

    fun enterThinking() {
        s = s.copy(machine = THINKING, thinkDeadline = now + THINK_TIMEOUT_MS, standbyDeadline = null, settleDeadline = null)
    }

    fun enterSpeaking() {
        s = s.copy(machine = SPEAKING, thinkDeadline = null, errorDeadline = null, standbyDeadline = null)
    }

    /** STANDBY with the 45 s countdown, or the stable state when no call is open. */
    fun enterStandby() {
        if (!callOpen) { goStable(); return }
        s = s.copy(
            machine = STANDBY, thinkDeadline = null, settleDeadline = null,
            standbyDeadline = now + STANDBY_WINDOW_MS, countdownStartMs = now, countdownEndMs = now + STANDBY_WINDOW_MS,
        )
    }

    fun startTalk(target: OrbState) {
        s = s.copy(
            machine = target, ptt = PttState.Held(now), pttCapAt = now + PTT_CAP_MS, pttWarnAt = now + PTT_WARN_MS,
            thinkDeadline = null, standbyDeadline = null, settleDeadline = null,
        )
        emit(Effect.Haptic(HapticKind.TICK))
        emit(Effect.Earcon(EarconKind.CHIRP))
        emit(Effect.MicStart)
        if (target == LISTENING) emit(Effect.MicLive)
    }

    // ---- deadlines ----------------------------------------------------------------------

    fun expire() {
        s.pttWarnAt?.takeIf { it <= now }?.let {
            s = s.copy(pttWarnAt = null)
            if (s.ptt != PttState.Up) emit(Effect.Haptic(HapticKind.TICK))
        }
        s.pttCapAt?.takeIf { it <= now }?.let {
            s = s.copy(pttCapAt = null)
            if (s.ptt != PttState.Up) release()
        }
        s.thinkDeadline?.takeIf { it <= now }?.let {
            s = s.copy(thinkDeadline = null)
            if (m == THINKING) enterError(CAPTION_SLOW)
        }
        s.settleDeadline?.takeIf { it <= now }?.let {
            s = s.copy(settleDeadline = null)
            if (m == SPEAKING && !s.sinkPlaying) enterStandby()
        }
        s.standbyDeadline?.takeIf { it <= now }?.let {
            s = s.copy(standbyDeadline = null)
            if (callOpen && (m == STANDBY || m == IDLE)) {
                teardown(hangUp = true)
                if (m == STANDBY) goStable()
            }
        }
        s.errorDeadline?.takeIf { it <= now }?.let {
            s = s.copy(errorDeadline = null)
            if (m == ERROR) exitError()
        }
        s.layoutReturnAt?.takeIf { it <= now }?.let {
            s = s.copy(layoutReturnAt = null)
            if (s.panelCount == 0 && s.layout == OrbLayout.DOCKED) s = s.copy(layout = OrbLayout.CENTERED, layoutSinceMs = now)
        }
    }

    fun exitError() {
        goStable()
        if (callOpen) {
            // Call survives ERROR (think timeout): late reply can still play; keep the idle window ticking.
            if (stableOrb(s) == IDLE) s = s.copy(standbyDeadline = now + STANDBY_WINDOW_MS) else teardown(hangUp = true)
        }
    }

    // ---- inputs -------------------------------------------------------------------------

    fun handle(input: Input) {
        when (input) {
            is Intent.ChooseMode -> {
                s = s.copy(
                    mode = input.mode, link = LinkState.Connecting,
                    simInterstitialUntil = if (input.mode == Mode.SIM) now + SIM_INTERSTITIAL_MS else null,
                )
                restart()
            }
            Intent.ResetMode -> {
                s = s.copy(mode = null, link = LinkState.Unpaired, simInterstitialUntil = null)
                restart()
            }
            is Intent.LinkChanged -> linkChanged(input.link)
            Intent.BootComplete -> if (m == BOOT) goStable()
            Intent.ClearInterstitial -> s = s.copy(simInterstitialUntil = null)
            Intent.Tick -> Unit
            Intent.PttDown -> pttDown()
            Intent.PttUp -> pttUp()
            Intent.HangUp -> hangUp()
            is Intent.ForceOrb -> s = s.copy(forcedOrb = input.state)
            is Intent.CallPhaseChanged -> when (val p = input.phase) {
                CallPhase.Admitting -> if (!callOpen) s = s.copy(call = CallStatus.Admitting)
                CallPhase.Ready -> callReady()
                is CallPhase.Ended -> callEnded(p.reason)
            }
            Intent.SinkFirstAudible -> sinkFirstAudible()
            Intent.SinkDrained -> {
                s = s.copy(sinkPlaying = false)
                if (m == SPEAKING) s = s.copy(settleDeadline = now + SETTLE_MS)
            }
            Intent.PairingOpen -> if (m == UNPAIRED) go(PAIRING)
            is Intent.PairingClosed -> if (m == PAIRING) {
                if (input.paired) go(if (stableOrb(s) == IDLE) IDLE else OFFLINE) else go(UNPAIRED)
            }
            is Intent.PanelCount -> s = s.copy(panelCount = input.count.coerceAtLeast(0))
            is Intent.DismissPanel, is Intent.PinPanel, is Intent.ExpandPanel,
            is Intent.AcknowledgeTimer, is Intent.RestorePanels -> Unit // TODO(M3): panel manager
            Intent.DisplayPulse -> s = s.copy(pulseAtMs = now)
            Intent.AttentionPulse -> s = s.copy(attentionAtMs = now)
            is Intent.SetNight -> s = s.copy(night = input.night)
            is Event -> event(input.event)
        }
    }

    /** Mode changed or reset: drop any call and re-derive the rest state (once booted). */
    fun restart() {
        if (m == BOOT) return
        teardown(hangUp = true)
        s = s.copy(errorDeadline = null)
        goStable()
    }

    fun linkChanged(l: LinkState) {
        s = s.copy(link = l)
        when (m) {
            BOOT, PAIRING, ERROR -> Unit // ERROR resolves from the link when it exits
            OFFLINE, UNPAIRED -> goStable()
            IDLE, STANDBY -> if (stableOrb(s) != IDLE) { teardown(hangUp = true); goStable() }
            CONNECTING, LISTENING, THINKING, SPEAKING -> if (stableOrb(s) != IDLE) {
                teardown(hangUp = true)
                enterError(null)
            }
        }
    }

    fun pttDown() {
        if (s.ptt != PttState.Up) return // latched: the release is handled by PttUp
        when (m) {
            OFFLINE -> {
                s = s.copy(shakeAtMs = now)
                emit(Effect.ShowCaption(CAPTION_OFFLINE))
            }
            IDLE -> when (s.call) {
                CallStatus.Ready -> startTalk(LISTENING)
                CallStatus.Admitting -> startTalk(CONNECTING)
                CallStatus.None -> {
                    startTalk(CONNECTING)
                    s = s.copy(call = CallStatus.Admitting)
                    emit(Effect.OpenCall)
                }
            }
            STANDBY -> startTalk(LISTENING)
            SPEAKING -> {
                emit(Effect.BargeIn)
                s = s.copy(sinkPlaying = false, turnInFlight = false)
                startTalk(LISTENING)
            }
            else -> Unit // BOOT, UNPAIRED, PAIRING, CONNECTING, LISTENING, THINKING, ERROR
        }
    }

    fun pttUp() {
        when (val p = s.ptt) {
            PttState.Up -> return
            is PttState.Held -> if (now - p.sinceMs < TAP_MS) { s = s.copy(ptt = PttState.Latched); return }
            PttState.Latched -> Unit
        }
        release()
    }

    /** Talk ends (release, latched tap or hold cap). In CONNECTING we wait for the call to be ready. */
    fun release() {
        s = s.copy(ptt = PttState.Up, pttCapAt = null, pttWarnAt = null)
        if (m == LISTENING) {
            enterThinking()
            emit(Effect.MicSilence)
            emit(Effect.MicStopAfter(MIC_TAIL_MS))
        }
    }

    fun callReady() {
        s = s.copy(call = CallStatus.Ready)
        if (m != CONNECTING) return
        emit(Effect.MicLive)
        if (s.ptt == PttState.Up) {
            // Released before ready: buffer is flushed, then silence; straight to THINKING.
            enterThinking()
            emit(Effect.MicSilence)
            emit(Effect.MicStopAfter(MIC_TAIL_MS))
        } else {
            go(LISTENING)
        }
    }

    fun callEnded(reason: String) {
        if (!callOpen) return
        val wasAdmitting = s.call == CallStatus.Admitting
        teardown(hangUp = false)
        val live = m in ACTIVE || m == IDLE
        val lower = reason.lowercase()
        when {
            TAKEOVER_MARKERS.any { lower.contains(it) } -> {
                emit(Effect.ShowCaption(CAPTION_TAKEOVER))
                if (m in ACTIVE || m == ERROR) goStable()
            }
            reason == REASON_SESSION_DEAD || wasAdmitting -> if (live) enterError(null) // TODO(M5): fallback caption
            else -> if (m in ACTIVE) goStable() // remote stop / idle timeout
        }
    }

    fun hangUp() {
        if (!callOpen) return
        teardown(hangUp = true)
        if (m in ACTIVE) goStable()
    }

    fun sinkFirstAudible() {
        s = s.copy(sinkPlaying = true, settleDeadline = null)
        when (m) {
            THINKING -> enterSpeaking()
            ERROR, STANDBY, IDLE -> if (callOpen) enterSpeaking() // late reply after think timeout / unsolicited speech
            else -> Unit
        }
    }

    fun event(e: TransportEvent) {
        when (e) {
            is TransportEvent.TurnStarted -> {
                s = s.copy(turnInFlight = true)
                if (m == STANDBY || (m == IDLE && callOpen)) enterThinking() // server-initiated turn
            }
            is TransportEvent.TurnSettled -> {
                s = s.copy(turnInFlight = false)
                if (m == THINKING && !s.sinkPlaying) enterStandby() // text-only / empty turn
            }
            is TransportEvent.Transcript -> emit(Effect.ShowCaption(e.text, e.role, e.final))
            is TransportEvent.AdvisoryError -> s = s.copy(advisoryErrors = e.count)
            is TransportEvent.Command -> Unit // TODO(M3/M4): panel manager
            is TransportEvent.Diagnostic -> Unit
        }
    }

    // ---- derived fields -----------------------------------------------------------------

    fun finish() {
        if (m != STANDBY && (s.countdownStartMs != 0L || s.countdownEndMs != 0L)) {
            s = s.copy(countdownStartMs = 0L, countdownEndMs = 0L)
        }
        // Layout: DOCKED while panels exist; 1.2 s delay before returning to CENTERED.
        val target = when {
            s.mode == null -> OrbLayout.FIRST_RUN
            m == PAIRING -> OrbLayout.PAIRING
            s.panelCount > 0 || s.layout == OrbLayout.DOCKED -> OrbLayout.DOCKED
            else -> OrbLayout.CENTERED
        }
        val ret = when {
            target != OrbLayout.DOCKED || s.panelCount > 0 -> null
            else -> s.layoutReturnAt ?: (now + LAYOUT_RETURN_MS)
        }
        s = if (target != s.layout) s.copy(layout = target, layoutSinceMs = now, layoutReturnAt = ret) else s.copy(layoutReturnAt = ret)
        // Displayed orb: forced override (sim) or the machine.
        val shown = s.forcedOrb ?: s.machine
        if (shown != s.orbState) s = s.copy(orbPrevious = s.orbState, orbState = shown, orbStateSince = now)
    }
}
