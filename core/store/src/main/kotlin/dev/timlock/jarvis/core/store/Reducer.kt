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
import dev.timlock.jarvis.core.model.Caption
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelPlacement
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.PanelType
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.model.UserConfig
import dev.timlock.jarvis.core.transport.TransportEvent

const val SIM_INTERSTITIAL_MS = 1500L
const val THINK_TIMEOUT_MS = UserConfig.DEFAULT_THINK_TIMEOUT_MS
const val STANDBY_WINDOW_MS = UserConfig.DEFAULT_STANDBY_MS
/** 80 ms in + 600 ms red + 2.5 s amber (the phases are visual only). */
const val ERROR_TOTAL_MS = 3_100L
const val PTT_CAP_MS = 60_000L
const val PTT_WARN_MS = 55_000L
const val TAP_MS = 250L
const val SETTLE_MS = 200L
/**
 * Settle after a drain while the server turn is still in flight (Fable A1 #3): a dry spell between sentences is a
 * pause, not the end of the reply. Matches the Player's continuation window; TurnSettled ends it early, and the
 * deadline itself is the fallback so a missing turn_settled cannot strand SPEAKING.
 */
const val SETTLE_IN_FLIGHT_MS = 2_500L
const val LAYOUT_RETURN_MS = 1_200L
const val MIC_TAIL_MS = 2_000L
const val DISPLAY_RATE_WINDOW_MS = 5_000L
const val DISPLAY_RATE_MAX = 10

const val REASON_SESSION_DEAD = "session_dead"
const val CAPTION_OFFLINE = "Offline — reconnecting"
const val CAPTION_TAKEOVER = "Call moved to another device"
const val CAPTION_SLOW = "Muse is taking too long"
const val CAPTION_FALLBACK = "Couldn't reach Muse's voice — trying text"
const val CAPTION_FALLBACK_ERROR = "Couldn't send that — try again"
/** Captions clear this long after the call goes idle (§2.5). */
const val CAPTION_FADE_MS = 8_000L
/** C10 typewriter speed; the reducer uses it only to continue partials without re-typing (the UI types at the same rate). */
const val CAPTION_CPS = 60L
/** §4.3.1: this many realtime admission failures within [FALLBACK_WINDOW_MS] switch PTT to the fallback path for that long. */
const val FALLBACK_FAILURES = 2
const val FALLBACK_WINDOW_MS = 600_000L
/** SPEAKING-lite for a text-only fallback reply. */
const val SPEAKING_LITE_MS_PER_WORD = 60L

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
            callSinceMs = 0L, fallbackTurn = false,
            thinkDeadline = null, standbyDeadline = null, settleDeadline = null, pttCapAt = null, pttWarnAt = null,
        )
    }

    /** Call/turn state is deliberately preserved (think timeout keeps the call open). */
    fun enterError(caption: String?) {
        s = s.copy(
            machine = ERROR, priorStable = stableOrb(s), errorDeadline = now + ERROR_TOTAL_MS,
            thinkDeadline = null, standbyDeadline = null, settleDeadline = null,
            pttCapAt = null, pttWarnAt = null, ptt = PttState.Up, fallbackTurn = false,
        )
        emit(Effect.Earcon(EarconKind.ERROR))
        caption?.let { setCaption(it, null, true) }
    }

    /**
     * Sets [AppState.caption] (and keeps emitting [Effect.ShowCaption]). Text that extends the previous caption of the
     * same role continues typing (C10) — also after a final: the bridge folds a completed STT item into the hold's
     * text and the next delta extends it, which must not retype from character 0 (Fable audit A2 #2).
     */
    fun setCaption(text: String, role: Role?, final: Boolean) {
        val prev = s.caption
        val r = if (role == Role.SYSTEM) null else role
        var at = now
        if (prev != null && prev.role == r && text.startsWith(prev.text)) {
            val typed = minOf(prev.text.length.toLong(), (now - prev.atMs).coerceAtLeast(0) * CAPTION_CPS / 1000)
            at = now - typed * 1000 / CAPTION_CPS
        }
        s = s.copy(caption = Caption(text, r, final, at), captionClearAt = null)
        emit(Effect.ShowCaption(text, role, final))
    }

    /** Default non-sticky lifetime for card/list panels (sticky-by-default is applied on arrival instead). */
    val panelTtl: Long get() = s.config.panelTtlMs ?: UserConfig.DEFAULT_PANEL_TTL_MS

    fun setConfig(cfg: UserConfig) {
        val c = cfg.sanitized()
        s = s.copy(config = c)
        if (c.maxPanels < s.panels.panels.size) {
            // Evict surplus panels: non-sticky oldest first, then oldest sticky.
            var list = s.panels.panels
            while (list.size > c.maxPanels) {
                val victim = list.lastOrNull { !PanelManager.isSticky(it) } ?: list.last()
                list = list.filter { it !== victim }
            }
            s = s.copy(panels = PanelManager.layout(s.panels.copy(panels = list)))
        }
    }

    fun fallbackActive(): Boolean = s.fallbackUntil?.let { now < it } == true

    fun enterThinking() {
        s = s.copy(machine = THINKING, thinkDeadline = now + s.config.thinkTimeoutMs, standbyDeadline = null, settleDeadline = null)
    }

    fun enterSpeaking() {
        s = s.copy(machine = SPEAKING, thinkDeadline = null, errorDeadline = null, standbyDeadline = null)
    }

    /** STANDBY with the 45 s countdown, or the stable state when no call is open. */
    fun enterStandby() {
        if (!callOpen) { goStable(); return }
        s = s.copy(
            machine = STANDBY, thinkDeadline = null, settleDeadline = null,
            standbyDeadline = now + s.config.standbyMs, countdownStartMs = now, countdownEndMs = now + s.config.standbyMs,
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
        if (target == LISTENING && !s.fallbackTurn) emit(Effect.MicLive)
    }

    // ---- deadlines ----------------------------------------------------------------------

    fun expire() {
        expirePanels()
        s.captionClearAt?.takeIf { it <= now }?.let { s = s.copy(captionClearAt = null, caption = null) }
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
            var p = PanelManager.applyLifetimes(s.panels, callOpen, now, panelTtl)
            if (wasOpen && !callOpen) p = PanelManager.demoteAtCallEnd(p, now) // H1: persistent panels become chips
            s = s.copy(panels = p)
        }
    }

    fun exitError() {
        goStable()
        if (callOpen) {
            // Call survives ERROR (think timeout): late reply can still play; keep the idle window ticking.
            if (stableOrb(s) == IDLE) s = s.copy(standbyDeadline = now + s.config.standbyMs) else teardown(hangUp = true)
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
            Intent.ResetFallback -> s = s.copy(admitFailures = emptyList(), fallbackUntil = null)
            Intent.SinkDrained -> {
                s = s.copy(sinkPlaying = false)
                if (m == SPEAKING) s = s.copy(settleDeadline = now + if (s.turnInFlight) SETTLE_IN_FLIGHT_MS else SETTLE_MS)
            }
            // The flow may open from the rest states (UNPAIRED, re-pair from OFFLINE, sim/idle with no call).
            Intent.PairingOpen -> if (m == UNPAIRED || m == OFFLINE || (m == IDLE && !callOpen)) go(PAIRING)
            is Intent.PairingClosed -> if (m == PAIRING) {
                if (input.paired) go(if (stableOrb(s) == IDLE) IDLE else OFFLINE) else goStable()
            }
            is Intent.PanelCount -> Unit // deprecated: panelCount is derived from the panel stack
            is Intent.DismissPanel -> s = s.copy(panels = PanelManager.dismiss(s.panels, input.id))
            is Intent.AcknowledgeTimer -> s = s.copy(panels = PanelManager.dismiss(s.panels, input.id))
            is Intent.PinPanel -> s = s.copy(panels = PanelManager.pin(s.panels, input.id, input.pinned, now, callOpen, panelTtl, s.config.maxPanels))
            is Intent.ExpandPanel -> s = s.copy(panels = PanelManager.expand(s.panels, input.id))
            is Intent.RestorePanels -> restorePanels(input.stack)
            Intent.DisplayPulse -> s = s.copy(pulseAtMs = now)
            Intent.AttentionPulse -> s = s.copy(attentionAtMs = now)
            is Intent.SetConfig -> setConfig(input.config)
            is Intent.SetNight -> s = s.copy(night = input.night)
            is Intent.SetPerfHud -> s = s.copy(perfHud = input.on)
            is Intent.SetTestInput -> s = s.copy(testInputEnabled = input.enabled, testInputActive = input.active)
            is Intent.FallbackResult -> fallbackResult(input)
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
        val newlyLost = l is LinkState.PairingLost && s.link !is LinkState.PairingLost
        s = s.copy(link = l)
        // §4.4: active (non-sticky) panels fade away when Muse unpairs us; the call teardown follows from stableOrb below.
        if (newlyLost && s.panels.panels.any { !PanelManager.isSticky(it) }) {
            s = s.copy(panels = PanelManager.layout(s.panels.copy(panels = s.panels.panels.filter { PanelManager.isSticky(it) })))
        }
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
                setCaption(CAPTION_OFFLINE, null, true)
            }
            IDLE -> when (s.call) {
                CallStatus.Ready -> startTalk(LISTENING)
                CallStatus.Admitting -> startTalk(CONNECTING)
                CallStatus.None -> if (fallbackActive()) {
                    // §4.3.1: no call on the fallback path; the mic just buffers the utterance.
                    s = s.copy(fallbackTurn = true)
                    startTalk(LISTENING)
                } else {
                    startTalk(CONNECTING)
                    s = s.copy(call = CallStatus.Admitting)
                    emit(Effect.OpenCall)
                }
            }
            STANDBY -> startTalk(LISTENING)
            SPEAKING -> {
                emit(Effect.BargeIn)
                s = s.copy(sinkPlaying = false, turnInFlight = false, settleDeadline = null)
                if (s.call == CallStatus.None && fallbackActive()) s = s.copy(fallbackTurn = true)
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
            if (s.fallbackTurn) {
                emit(Effect.FallbackSend)
                emit(Effect.MicStop)
            } else {
                emit(Effect.MicSilence)
                emit(Effect.MicStopAfter(MIC_TAIL_MS))
            }
        }
    }

    fun callReady() {
        // A realtime success resets the fallback bookkeeping (§4.3.1).
        s = s.copy(
            callSinceMs = if (s.call == CallStatus.Ready) s.callSinceMs else now,
            call = CallStatus.Ready, admitFailures = emptyList(), fallbackUntil = null,
        )
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
        val pttBefore = s.ptt
        val pttSince = (pttBefore as? PttState.Held)?.sinceMs ?: now
        teardown(hangUp = false)
        val live = m in ACTIVE || m == IDLE
        val lower = reason.lowercase()
        when {
            TAKEOVER_MARKERS.any { lower.contains(it) } -> {
                setCaption(CAPTION_TAKEOVER, null, true)
                if (m in ACTIVE || m == ERROR) goStable()
            }
            wasAdmitting -> {
                recordAdmitFailure()
                if (live && fallbackActive()) startFallbackAfterFailure(pttBefore, pttSince) else if (live) enterError(null)
            }
            reason == REASON_SESSION_DEAD -> if (live) enterError(null)
            else -> if (m in ACTIVE) goStable() // remote stop / idle timeout
        }
    }

    /** §4.3.1: [FALLBACK_FAILURES] admission failures within [FALLBACK_WINDOW_MS] switch to the fallback path for that long. */
    fun recordAdmitFailure() {
        val recent = s.admitFailures.filter { now - it < FALLBACK_WINDOW_MS } + now
        s = s.copy(admitFailures = recent)
        if (recent.size >= FALLBACK_FAILURES) s = s.copy(fallbackUntil = now + FALLBACK_WINDOW_MS)
    }

    /** Admission failed and fallback applies: keep/redo the utterance via chat + TTS instead of ERROR. */
    fun startFallbackAfterFailure(ptt: PttState, heldSince: Long) {
        setCaption(CAPTION_FALLBACK, null, true)
        s = s.copy(fallbackTurn = true)
        // teardown() requested MicStop; the buffered utterance must survive until FallbackSend / release.
        fx.removeAll { it == Effect.MicStop }
        if (ptt != PttState.Up) {
            // Still talking: keep buffering on the fallback path, PttUp sends.
            s = s.copy(ptt = ptt, pttCapAt = heldSince + PTT_CAP_MS, pttWarnAt = heldSince + PTT_WARN_MS)
            go(LISTENING)
        } else {
            enterThinking()
            emit(Effect.FallbackSend)
            emit(Effect.MicStop)
        }
    }

    fun fallbackResult(r: Intent.FallbackResult) {
        if (!s.fallbackTurn || (m != THINKING && m != SPEAKING)) return // stale (think timeout, hang-up, new turn)
        s = s.copy(fallbackTurn = false)
        val text = r.replyText?.takeIf { it.isNotBlank() }
        if (m == SPEAKING) { // audio started before the result arrived
            text?.let { setCaption(it, Role.MUSE, true) }
            return
        }
        when {
            r.error != null -> enterError(CAPTION_FALLBACK_ERROR)
            r.hasAudio -> text?.let { setCaption(it, Role.MUSE, true) } // wait for SinkFirstAudible / SinkDrained
            text == null -> enterStandby() // empty turn -> stable state (no call on this path)
            else -> textOnlyReply(text)
        }
    }

    /**
     * Fallback text-only reply (§4.3.1 step 4): caption + SPEAKING-lite (60 ms/word). Never logged.
     * Owner direction (fix batch 2026-11-12): NO local panel — the caption bar already shows the text; panels
     * come only from explicit display.* commands.
     */
    fun textOnlyReply(text: String) {
        setCaption(text, Role.MUSE, true)
        enterSpeaking()
        val words = text.trim().split(Regex("\\s+")).size.coerceAtLeast(1)
        s = s.copy(settleDeadline = now + words * SPEAKING_LITE_MS_PER_WORD)
    }

    fun hangUp() {
        if (!callOpen) {
            // Fallback path has no call: a double-tap while talking cancels the buffered utterance.
            if (s.fallbackTurn && m == LISTENING) {
                s = s.copy(ptt = PttState.Up, pttCapAt = null, pttWarnAt = null, fallbackTurn = false)
                emit(Effect.MicStop)
                goStable()
            }
            return
        }
        teardown(hangUp = true)
        if (m in ACTIVE) goStable()
    }

    fun sinkFirstAudible() {
        s = s.copy(sinkPlaying = true, settleDeadline = null)
        when (m) {
            THINKING -> enterSpeaking()
            // H19 (kept deliberately): while a call is open, audible reply frames arriving in ERROR (late reply after the
            // think timeout), STANDBY or IDLE (server-initiated / unsolicited speech) move the orb to SPEAKING even though
            // the §3.1 diagram has no such edge; hiding speech the user can hear would be worse than the extra edge.
            ERROR, STANDBY, IDLE -> if (callOpen) enterSpeaking()
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
                // THINKING: text-only / empty turn. SPEAKING: the reply audio already drained and we were holding
                // SPEAKING through a possible sentence pause (SETTLE_IN_FLIGHT_MS) — the turn is over, stand by now.
                if ((m == THINKING || m == SPEAKING) && !s.sinkPlaying) enterStandby()
            }
            is TransportEvent.Transcript -> setCaption(e.text, e.role, e.final)
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
        }.take(s.config.maxPanels)
        val withLifetimes = PanelManager.applyLifetimes(PanelStack(keep), callOpen, now, panelTtl)
        // A timer that ended while the process was dead was marked chimed above; give it a fresh
        // ring window from the restore so it is seen as rung instead of expiring in the same step.
        val adjusted = withLifetimes.copy(panels = withLifetimes.panels.map {
            val c = it.content
            if (c is PanelContent.Timer && c.chimed && c.endsAtMs <= now) it.copy(expiresAtMs = now + PanelManager.TIMER_RING_MS) else it
        })
        val restored = PanelManager.layout(adjusted)
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
                val panel = v.panel.copy(sim = s.mode == Mode.SIM).let { if (s.config.panelTtlMs == null && it.type != PanelType.TIMER) it.copy(sticky = true) else it }
                val before = s.panels.panels.firstOrNull { it.id == panel.id }
                val dockedBefore = s.layout == OrbLayout.DOCKED
                val next = PanelManager.upsert(s.panels, panel, now, callOpen, panelTtl, dockedBefore = dockedBefore, maxPanels = s.config.maxPanels)
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
        // Captions fade 8 s after the call goes idle; never while a call is open or a turn is active.
        if (s.caption == null) {
            if (s.captionClearAt != null) s = s.copy(captionClearAt = null)
        } else if (callOpen || m in ACTIVE) {
            if (s.captionClearAt != null) s = s.copy(captionClearAt = null)
        } else if (s.captionClearAt == null) {
            s = s.copy(captionClearAt = now + CAPTION_FADE_MS)
        }
        // Displayed orb: forced override (sim) or the machine.
        val shown = s.forcedOrb ?: s.machine
        if (shown != s.orbState) s = s.copy(orbPrevious = s.orbState, orbState = shown, orbStateSince = now)
    }
}
