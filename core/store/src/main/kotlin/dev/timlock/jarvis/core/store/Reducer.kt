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
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelPlacement
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.PanelType
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
const val DISPLAY_RATE_WINDOW_MS = 5_000L
const val DISPLAY_RATE_MAX = 10

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
    c.lifetimes(state.call != CallStatus.None)
    c.finish()
    if (state.mode == Mode.REAL && c.s.mode == Mode.REAL) {
        val before = persistable(state.panels)
        val after = persistable(c.s.panels)
        if (before != after) c.fx += Effect.PersistPanels(PanelStack(after))
    }
    return Reduction(c.s, c.fx)
}

/** Sticky/pinned panels and timers, minus transient layout/expiry bookkeeping (§5.5). */
private fun persistable(st: PanelStack): List<dev.timlock.jarvis.core.model.Panel> =
    st.panels.filter { PanelManager.isSticky(it) || it.type == PanelType.TIMER }
        .map { it.copy(collapsed = false, expiresAtMs = null, newRows = emptySet(), placement = PanelPlacement.CHIP, revealStartMs = 0L) }

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
        expirePanels()
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
            if (s.dockCount == 0 && s.layout == OrbLayout.DOCKED) s = s.copy(layout = OrbLayout.CENTERED, layoutSinceMs = now)
        }
    }

    /** Panel deadlines: timer zero chime (once) and panel expiry. */
    fun expirePanels() {
        var chimes = 0
        val marked = s.panels.panels.map { p ->
            val c = p.content
            if (c is PanelContent.Timer && !c.chimed && c.endsAtMs <= now) { chimes++; p.copy(content = c.copy(chimed = true)) } else p
        }
        if (chimes > 0) {
            s = s.copy(panels = s.panels.copy(panels = marked))
            repeat(chimes) { emit(Effect.Earcon(EarconKind.TIMER_CHIME)) }
        }
        val (kept, _) = PanelManager.expire(s.panels, now)
        if (kept !== s.panels) s = s.copy(panels = kept)
    }

    /** Recompute expiries when the call opens or closes (§5.5: the call is the context). */
    fun lifetimes(wasOpen: Boolean) {
        if (wasOpen != callOpen) {
            var p = PanelManager.applyLifetimes(s.panels, callOpen, now)
            if (wasOpen && !callOpen) p = PanelManager.demoteAtCallEnd(p, now) // H1: persistent panels become chips
            s = s.copy(panels = p)
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
                if (input.mode != s.mode) s = s.copy(panels = PanelStack()) // sim panels never persist into real (§7.2)
                s = s.copy(
                    mode = input.mode, link = LinkState.Connecting,
                    simInterstitialUntil = if (input.mode == Mode.SIM) now + SIM_INTERSTITIAL_MS else null,
                )
                restart()
            }
            Intent.ResetMode -> {
                s = s.copy(panels = PanelStack())
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
            is Intent.PanelCount -> Unit // deprecated: panelCount is derived from the panel stack
            is Intent.DismissPanel -> s = s.copy(panels = PanelManager.dismiss(s.panels, input.id))
            is Intent.AcknowledgeTimer -> s = s.copy(panels = PanelManager.dismiss(s.panels, input.id))
            is Intent.PinPanel -> s = s.copy(panels = PanelManager.pin(s.panels, input.id, input.pinned, now, callOpen))
            is Intent.ExpandPanel -> s = s.copy(panels = PanelManager.expand(s.panels, input.id))
            is Intent.RestorePanels -> restorePanels(input.stack)
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
            is TransportEvent.Command -> command(e)
            is TransportEvent.Diagnostic -> Unit
        }
    }

    // ---- panels (§5, §2.5) ----------------------------------------------------------------

    fun restorePanels(saved: PanelStack) {
        if (s.mode != Mode.REAL) return
        val keep = saved.panels.filter { PanelManager.isSticky(it) || it.type == PanelType.TIMER }.map {
            val c = it.content
            val content = if (c is PanelContent.Timer && c.endsAtMs <= now) c.copy(chimed = true) else it.content
            it.copy(
                content = content, sim = false, collapsed = false,
                placement = if (callOpen) PanelPlacement.DOCK else PanelPlacement.CHIP,
                revealStartMs = now - PanelManager.BRACKET_STAGE_MS,
            )
        }.take(PanelManager.MAX_PANELS)
        val restored = PanelManager.layout(PanelManager.applyLifetimes(PanelStack(keep), callOpen, now))
        s = s.copy(panels = PanelManager.expire(restored, now).first)
    }

    fun command(e: TransportEvent.Command) {
        val hits = s.displayHits.filter { now - it < DISPLAY_RATE_WINDOW_MS }
        if (hits.size >= DISPLAY_RATE_MAX) {
            s = s.copy(displayHits = hits)
            emit(Effect.ReplyCommand(e.invocationId, CommandResult.error("rate limited")))
            return
        }
        s = s.copy(displayHits = hits + now)
        var seq = s.idSeq
        val taken = s.panels.panels.map { it.id }.toSet()
        val v = DisplayValidator.validate(e.name, e.paramsJson, now) { type ->
            var id: String
            do {
                seq++
                id = type.name.lowercase() + "-" + "%04x".format((seq * 40503) and 0xFFFF)
            } while (id in taken)
            id
        }
        s = s.copy(idSeq = seq)
        when (v) {
            is Validation.Reject -> emit(Effect.ReplyCommand(e.invocationId, CommandResult.error(v.error)))
            is Validation.Clear -> {
                val before = s.panels.panels.size
                val next = if (v.id == null) PanelManager.clearAll(s.panels) else PanelManager.dismiss(s.panels, v.id)
                s = s.copy(panels = next)
                val n = before - next.panels.size
                emit(Effect.ReplyCommand(e.invocationId, CommandResult.ok(DisplayValidator.clearPayload(v.id, n))))
            }
            is Validation.Show -> {
                val panel = v.panel.copy(sim = s.mode == Mode.SIM)
                val before = s.panels.panels.firstOrNull { it.id == panel.id }
                val dockedBefore = s.layout == OrbLayout.DOCKED
                val next = PanelManager.upsert(s.panels, panel, now, callOpen, dockedBefore = dockedBefore)
                val after = next.panels.first { it.id == panel.id }
                // C9: a DOCK arrival (or chip->dock promotion) pulses when the brackets pop, possibly in the future.
                val arrived = before == null || before.placement != after.placement
                val pulse = if (arrived && after.placement == PanelPlacement.DOCK) after.revealStartMs + PanelManager.BRACKET_STAGE_MS else now
                s = s.copy(panels = next, pulseAtMs = pulse)
                if (!callOpen) s = s.copy(attentionAtMs = now) // ambient push (§2.6)
                emit(Effect.ReplyCommand(e.invocationId, CommandResult.ok(DisplayValidator.showPayload(panel.id, v.truncated, v.ignored))))
            }
        }
    }

    // ---- derived fields -----------------------------------------------------------------

    fun finish() {
        val placed = PanelManager.reconcilePlacement(s.panels, callOpen, now)
        if (placed !== s.panels) s = s.copy(panels = placed)
        if (m != STANDBY && (s.countdownStartMs != 0L || s.countdownEndMs != 0L)) {
            s = s.copy(countdownStartMs = 0L, countdownEndMs = 0L)
        }
        // Layout: DOCKED while DOCK-placed panels exist (chips never dock the orb, H1); 1.2 s delay before returning to CENTERED.
        val target = when {
            s.mode == null -> OrbLayout.FIRST_RUN
            m == PAIRING -> OrbLayout.PAIRING
            s.dockCount > 0 || s.layout == OrbLayout.DOCKED -> OrbLayout.DOCKED
            else -> OrbLayout.CENTERED
        }
        val ret = when {
            target != OrbLayout.DOCKED || s.dockCount > 0 -> null
            else -> s.layoutReturnAt ?: (now + LAYOUT_RETURN_MS)
        }
        s = if (target != s.layout) s.copy(layout = target, layoutSinceMs = now, layoutReturnAt = ret) else s.copy(layoutReturnAt = ret)
        // Displayed orb: forced override (sim) or the machine.
        val shown = s.forcedOrb ?: s.machine
        if (shown != s.orbState) s = s.copy(orbPrevious = s.orbState, orbState = shown, orbStateSince = now)
    }
}
