package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.Level
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbState
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tiny harness: feeds inputs at explicit monotonic times and records the last effects. */
private class H(var s: AppState = AppState()) {
    var t = 1000L
    var fx: List<Effect> = emptyList()
    val allFx = mutableListOf<Effect>()
    val logs = mutableListOf<String>()

    fun send(i: Input, at: Long = t): H {
        t = at
        val r = reduce(s, i, at) { logs += it }
        s = r.state
        fx = r.effects
        allFx += r.effects
        return this
    }
    fun after(ms: Long, i: Input = Intent.Tick) = send(i, t + ms)
    val orb get() = s.orbState
    val machine get() = s.machine

    companion object {
        val online = LinkState.Online("n", "vm")
        fun sim() = H().send(Intent.ChooseMode(Mode.SIM)).send(Intent.BootComplete).send(Intent.LinkChanged(online))
        fun real() = H().send(Intent.ChooseMode(Mode.REAL)).send(Intent.BootComplete).send(Intent.LinkChanged(online))
        /** Sim in LISTENING with a Ready call, PTT held. */
        fun listening() = sim().send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
        fun thinking() = listening().after(1000, Intent.PttUp)
        fun speaking() = thinking().after(500, Intent.SinkFirstAudible)
        fun standby() = speaking().after(1000, Intent.SinkDrained).after(SETTLE_MS)
        fun turnStarted() = Event(TransportEvent.TurnStarted("t", TransportKind.SIM))
        fun turnSettled() = Event(TransportEvent.TurnSettled("t", TransportKind.SIM))
    }
}

private fun cmd(name: String, params: String, id: String = "inv", kind: TransportKind = TransportKind.SIM) =
    Event(TransportEvent.Command(id, name, params, kind))

class ReducerTest {
    // ---- boot / link / mode ---------------------------------------------------------------

    @Test fun bootUntilBootComplete() {
        val h = H().send(Intent.ChooseMode(Mode.SIM))
        assertEquals(OrbState.BOOT, h.orb)
        assertEquals(IDLE, h.send(Intent.BootComplete).orb)
    }

    @Test fun simModeIsIdleAndInterstitialIsMonotonic() {
        val h = H().send(Intent.ChooseMode(Mode.SIM), 1000L).send(Intent.BootComplete)
        assertEquals(IDLE, h.orb)
        assertEquals(1000L + SIM_INTERSTITIAL_MS, h.s.simInterstitialUntil)
        assertNull(h.send(Intent.ClearInterstitial).s.simInterstitialUntil)
    }

    @Test fun realUnpairedAndPairingLost() {
        val h = H().send(Intent.ChooseMode(Mode.REAL)).send(Intent.LinkChanged(LinkState.Unpaired)).send(Intent.BootComplete)
        assertEquals(UNPAIRED, h.orb)
        assertNull(h.s.simInterstitialUntil)
        val h2 = H.real().send(Intent.LinkChanged(LinkState.PairingLost("revoked")))
        assertEquals(UNPAIRED, h2.orb)
    }

    @Test fun realConnectingAndOfflineAreOffline() {
        val h = H().send(Intent.ChooseMode(Mode.REAL)).send(Intent.BootComplete)
        assertEquals(OFFLINE, h.orb)
        assertEquals(IDLE, h.send(Intent.LinkChanged(H.online)).orb)
        assertEquals(OFFLINE, h.send(Intent.LinkChanged(LinkState.Offline("x"))).orb)
    }

    @Test fun orbSinceAndPreviousTracked() {
        val h = H().send(Intent.ChooseMode(Mode.REAL)).send(Intent.BootComplete)
        h.send(Intent.LinkChanged(H.online), 4242L)
        assertEquals(IDLE, h.orb)
        assertEquals(OFFLINE, h.s.orbPrevious)
        assertEquals(4242L, h.s.orbStateSince)
        h.send(Intent.LinkChanged(LinkState.Online("n", "vm2")), 9999L)
        assertEquals(4242L, h.s.orbStateSince)
    }

    @Test fun mismatchedEventIsDroppedAndLogged() {
        val h = H.real()
        val before = h.s
        h.send(Event(TransportEvent.Diagnostic(Level.INFO, "X", TransportKind.SIM)))
        assertEquals(before, h.s)
        assertTrue(h.logs.single().startsWith("MODE_MISMATCH"))
        assertTrue(h.fx.isEmpty())
    }

    @Test fun matchingEventNotLogged() {
        val h = H.sim().send(H.turnStarted())
        assertTrue(h.logs.isEmpty())
    }

    @Test fun resetReturnsToFirstRun() {
        val h = H.sim().send(Intent.ResetMode)
        assertNull(h.s.mode)
        assertNull(h.s.simInterstitialUntil)
        assertEquals(UNPAIRED, h.orb)
        assertEquals(OrbLayout.FIRST_RUN, h.s.layout)
    }

    @Test fun pairingFlow() {
        val h = H().send(Intent.ChooseMode(Mode.REAL)).send(Intent.LinkChanged(LinkState.Unpaired)).send(Intent.BootComplete)
        assertEquals(PAIRING, h.send(Intent.PairingOpen).orb)
        assertEquals(OrbLayout.PAIRING, h.s.layout)
        assertEquals(UNPAIRED, h.send(Intent.PairingClosed(false)).orb)
        h.send(Intent.PairingOpen)
        h.send(Intent.LinkChanged(LinkState.Connecting))
        assertEquals(PAIRING, h.orb) // link noise does not kick us out of the window
        assertEquals(OFFLINE, h.send(Intent.PairingClosed(true)).orb)
        assertEquals(IDLE, h.send(Intent.LinkChanged(H.online)).orb)
    }

    @Test fun pairingOpensFromIdleAndClosesBackToStable() {
        val h = H.sim()
        assertEquals(PAIRING, h.send(Intent.PairingOpen).orb)
        assertEquals(IDLE, h.send(Intent.PairingClosed(false)).orb)
        val busy = H.listening().send(Intent.PairingOpen)
        assertEquals(LISTENING, busy.orb) // never mid-call
    }

    @Test fun pairingLostTearsDownCallAndFadesPanels() {
        val h = H.real().send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
        h.send(cmd("display.card", "{\"title\":\"Hi\"}", kind = TransportKind.REAL))
        assertTrue(h.s.panels.panels.isNotEmpty())
        h.send(Intent.LinkChanged(LinkState.PairingLost("token revoked")))
        assertEquals(CallStatus.None, h.s.call)
        assertTrue(Effect.HangUpCall in h.fx)
        assertTrue(h.s.panels.panels.none { !PanelManager.isSticky(it) })
    }

    // ---- PTT / call flow ------------------------------------------------------------------

    @Test fun pttDownFromIdleConnectsAndOpensCall() {
        val h = H.sim().send(Intent.PttDown)
        assertEquals(CONNECTING, h.orb)
        assertEquals(CallStatus.Admitting, h.s.call)
        assertEquals(
            listOf(Effect.Haptic(HapticKind.TICK), Effect.Earcon(EarconKind.CHIRP), Effect.MicStart, Effect.OpenCall),
            h.fx,
        )
    }

    @Test fun callReadyWhileHeldGoesListening() {
        val h = H.sim().send(Intent.PttDown).after(600, Intent.CallPhaseChanged(CallPhase.Ready))
        assertEquals(LISTENING, h.orb)
        assertEquals(listOf<Effect>(Effect.MicLive), h.fx)
    }

    @Test fun releaseInListeningGoesThinkingWithSilence() {
        val h = H.listening().after(1000, Intent.PttUp)
        assertEquals(THINKING, h.orb)
        assertEquals(listOf(Effect.MicSilence, Effect.MicStopAfter(2000)), h.fx)
        assertEquals(h.t + THINK_TIMEOUT_MS, h.s.thinkDeadline)
    }

    @Test fun releasedBeforeReadyGoesThinkingWhenReady() {
        val h = H.sim().send(Intent.PttDown).after(400, Intent.PttUp)
        assertEquals(CONNECTING, h.orb) // 400 ms hold = not a tap, waits for the call
        h.after(300, Intent.CallPhaseChanged(CallPhase.Ready))
        assertEquals(THINKING, h.orb)
        assertEquals(listOf(Effect.MicLive, Effect.MicSilence, Effect.MicStopAfter(2000)), h.fx)
    }

    @Test fun admissionFailureErrorsThenReturnsIdle() {
        val h = H.sim().send(Intent.PttDown).after(500, Intent.CallPhaseChanged(CallPhase.Ended("admit_failed")))
        assertEquals(ERROR, h.orb)
        assertEquals(CallStatus.None, h.s.call)
        assertTrue(Effect.MicStop in h.fx)
        h.after(ERROR_TOTAL_MS - 1)
        assertEquals(ERROR, h.orb)
        h.after(1)
        assertEquals(IDLE, h.orb)
    }

    @Test fun errorReturnsToOfflineWhenLinkDown() {
        val h = H.sim().send(Intent.PttDown).after(500, Intent.CallPhaseChanged(CallPhase.Ended("admit_failed")))
        h.after(100, Intent.LinkChanged(LinkState.Offline("lost")))
        assertEquals(ERROR, h.orb) // still flashing
        h.after(ERROR_TOTAL_MS)
        assertEquals(OFFLINE, h.orb)
    }

    @Test fun errorReturnsToUnpairedWhenRevoked() {
        val h = H.real().send(Intent.PttDown).after(100, Intent.CallPhaseChanged(CallPhase.Ended("x")))
        h.after(10, Intent.LinkChanged(LinkState.PairingLost("401")))
        h.after(ERROR_TOTAL_MS)
        assertEquals(UNPAIRED, h.orb)
    }

    @Test fun firstAudibleGoesSpeaking() {
        val h = H.thinking().after(800, Intent.SinkFirstAudible)
        assertEquals(SPEAKING, h.orb)
        assertNull(h.s.thinkDeadline)
    }

    @Test fun settledWithoutAudioGoesStandbyWithCountdown() {
        val h = H.thinking().after(500, H.turnSettled())
        assertEquals(STANDBY, h.orb)
        assertEquals(h.t, h.s.countdownStartMs)
        assertEquals(h.t + STANDBY_WINDOW_MS, h.s.countdownEndMs)
    }

    @Test fun drainAppliesSettleBeforeStandby() {
        val h = H.speaking().after(1000, Intent.SinkDrained)
        assertEquals(SPEAKING, h.orb)
        h.after(SETTLE_MS - 1)
        assertEquals(SPEAKING, h.orb)
        h.after(1)
        assertEquals(STANDBY, h.orb)
    }

    @Test fun audioResumingDuringSettleStaysSpeaking() {
        val h = H.speaking().after(1000, Intent.SinkDrained).after(100, Intent.SinkFirstAudible)
        h.after(500)
        assertEquals(SPEAKING, h.orb)
    }

    // ---- sentence-boundary settle (Fable A1 #3 / finding 8) ----------------------------------

    /** SPEAKING with the server turn still in flight (TurnStarted seen, no TurnSettled yet). */
    private fun speakingInFlight() = H.thinking().after(200, H.turnStarted()).after(300, Intent.SinkFirstAudible)

    @Test fun drainWhileTurnInFlightHoldsSpeakingThroughSentencePause() {
        val h = speakingInFlight().after(1000, Intent.SinkDrained)
        assertTrue(h.s.turnInFlight)
        assertEquals(h.t + SETTLE_IN_FLIGHT_MS, h.s.settleDeadline)
        h.after(SETTLE_MS)
        assertEquals(SPEAKING, h.orb) // the 200 ms settle no longer applies mid-turn
        h.after(1000, Intent.SinkFirstAudible) // next sentence
        assertEquals(SPEAKING, h.orb)
        assertNull(h.s.settleDeadline)
    }

    @Test fun inFlightSettleFallsBackToStandbyWithoutTurnSettled() {
        val h = speakingInFlight().after(1000, Intent.SinkDrained)
        h.after(SETTLE_IN_FLIGHT_MS - 1)
        assertEquals(SPEAKING, h.orb)
        h.after(1)
        assertEquals(STANDBY, h.orb)
    }

    @Test fun turnSettledAfterDrainGoesStandbyAtOnce() {
        val h = speakingInFlight().after(1000, Intent.SinkDrained).after(300, H.turnSettled())
        assertEquals(STANDBY, h.orb)
        assertFalse(h.s.turnInFlight)
        assertNull(h.s.settleDeadline)
        assertEquals(h.t, h.s.countdownStartMs)
    }

    @Test fun turnSettledWhileStillPlayingKeepsSpeakingThenShortSettle() {
        val h = speakingInFlight().after(500, H.turnSettled())
        assertEquals(SPEAKING, h.orb) // audio still audible
        h.after(800, Intent.SinkDrained)
        assertEquals(h.t + SETTLE_MS, h.s.settleDeadline) // turn over: back to the short settle
        h.after(SETTLE_MS)
        assertEquals(STANDBY, h.orb)
    }

    @Test fun pttDownInStandbyListensWithoutNewCall() {
        val h = H.standby().after(5000, Intent.PttDown)
        assertEquals(LISTENING, h.orb)
        assertFalse(Effect.OpenCall in h.fx)
        assertTrue(Effect.MicLive in h.fx)
        assertNull(h.s.standbyDeadline)
        assertEquals(0L, h.s.countdownEndMs)
    }

    @Test fun bargeInDuringSpeaking() {
        val h = H.speaking().after(700, Intent.PttDown)
        assertEquals(LISTENING, h.orb)
        assertEquals(Effect.BargeIn, h.fx.first())
        assertTrue(Effect.MicStart in h.fx && Effect.MicLive in h.fx)
        assertFalse(h.s.sinkPlaying)
    }

    @Test fun standbyWindowElapsedHangsUpToIdle() {
        val h = H.standby()
        h.after(STANDBY_WINDOW_MS - 1)
        assertEquals(STANDBY, h.orb)
        h.after(1)
        assertEquals(IDLE, h.orb)
        assertEquals(CallStatus.None, h.s.call)
        assertTrue(Effect.HangUpCall in h.fx)
    }

    @Test fun hangUpEndsCallFromStandbyAndSpeaking() {
        val h = H.standby().after(100, Intent.HangUp)
        assertEquals(IDLE, h.orb)
        assertTrue(Effect.HangUpCall in h.fx)
        val h2 = H.speaking().after(100, Intent.HangUp)
        assertEquals(IDLE, h2.orb)
        assertFalse(h2.s.sinkPlaying)
    }

    @Test fun takeoverGoesIdleWithCaptionNotError() {
        val h = H.standby().after(100, Intent.CallPhaseChanged(CallPhase.Ended("call_takeover")))
        assertEquals(IDLE, h.orb)
        assertEquals(listOf<Effect>(Effect.MicStop, Effect.ShowCaption(CAPTION_TAKEOVER)), h.fx)
    }

    @Test fun remoteStopGoesIdleQuietly() {
        val h = H.standby().after(100, Intent.CallPhaseChanged(CallPhase.Ended("remote_stop")))
        assertEquals(IDLE, h.orb)
        assertFalse(h.fx.any { it is Effect.Earcon })
    }

    @Test fun sessionDeadErrorsThenIdle() {
        val h = H.thinking().after(100, Intent.CallPhaseChanged(CallPhase.Ended(REASON_SESSION_DEAD)))
        assertEquals(ERROR, h.orb)
        h.after(ERROR_TOTAL_MS)
        assertEquals(IDLE, h.orb)
        assertEquals(CallStatus.None, h.s.call)
        h.after(10, Intent.PttDown) // next PTT opens a new call
        assertTrue(Effect.OpenCall in h.fx)
    }

    @Test fun revokedFromIdleAndStandbyGoesUnpaired() {
        assertEquals(UNPAIRED, H.real().send(Intent.LinkChanged(LinkState.PairingLost("401"))).orb)
        val h = H.standby().send(Intent.LinkChanged(LinkState.PairingLost("401")))
        assertEquals(UNPAIRED, h.orb)
        assertTrue(Effect.HangUpCall in h.fx)
    }

    @Test fun linkLostIdleGoesOfflineAndBackUp() {
        val h = H.real().send(Intent.LinkChanged(LinkState.Offline("x")))
        assertEquals(OFFLINE, h.orb)
        assertEquals(IDLE, h.send(Intent.LinkChanged(H.online)).orb)
    }

    @Test fun linkLostMidTurnErrorsThenOffline() {
        val h = H.listening().after(100, Intent.LinkChanged(LinkState.Offline("lost")))
        assertEquals(ERROR, h.orb)
        assertEquals(CallStatus.None, h.s.call)
        assertEquals(PttState.Up, h.s.ptt)
        h.after(ERROR_TOTAL_MS)
        assertEquals(OFFLINE, h.orb)
    }

    @Test fun serverInitiatedTurnFromStandbyThinks() {
        val h = H.standby().after(100, H.turnStarted())
        assertEquals(THINKING, h.orb)
        assertTrue(h.s.turnInFlight)
    }

    @Test fun turnStartedKeepsThinkingDeadline() {
        val h = H.thinking()
        val d = h.s.thinkDeadline
        h.after(1000, H.turnStarted())
        assertEquals(THINKING, h.orb)
        assertEquals(d, h.s.thinkDeadline)
    }

    // ---- timers ---------------------------------------------------------------------------

    @Test fun thinkTimeoutErrorsKeepsCallAndLateReplyPlays() {
        val h = H.thinking()
        h.after(THINK_TIMEOUT_MS - 1)
        assertEquals(THINKING, h.orb)
        h.after(1)
        assertEquals(ERROR, h.orb)
        assertEquals(CallStatus.Ready, h.s.call)
        assertEquals(Effect.ShowCaption(CAPTION_SLOW), h.fx.last())
        h.after(500, Intent.SinkFirstAudible)
        assertEquals(SPEAKING, h.orb)
        h.after(ERROR_TOTAL_MS) // old error deadline must not yank us out of SPEAKING
        assertEquals(SPEAKING, h.orb)
    }

    @Test fun thinkTimeoutErrorExitsToIdleKeepingCallWithIdleWindow() {
        val h = H.thinking().after(THINK_TIMEOUT_MS)
        h.after(ERROR_TOTAL_MS)
        assertEquals(IDLE, h.orb)
        assertEquals(CallStatus.Ready, h.s.call)
        assertEquals(h.t + STANDBY_WINDOW_MS, h.s.standbyDeadline)
        // PTT with the surviving call goes straight to LISTENING.
        assertEquals(LISTENING, h.after(10, Intent.PttDown).orb)
        assertFalse(Effect.OpenCall in h.fx)
    }

    @Test fun pttHoldCapAutoReleases() {
        val h = H.listening()
        val start = h.t
        h.send(Intent.Tick, start + PTT_WARN_MS)
        assertEquals(listOf<Effect>(Effect.Haptic(HapticKind.TICK)), h.fx)
        assertEquals(LISTENING, h.orb)
        h.send(Intent.Tick, start + PTT_CAP_MS)
        assertEquals(THINKING, h.orb)
        assertEquals(PttState.Up, h.s.ptt)
        assertTrue(Effect.MicSilence in h.fx)
    }

    @Test fun tapLatchesAndSecondTapEnds() {
        val h = H.listening()
        h.after(100, Intent.PttUp)
        assertEquals(PttState.Latched, h.s.ptt)
        assertTrue(h.s.latched)
        assertEquals(LISTENING, h.orb)
        h.after(2000, Intent.PttDown) // second tap: down is ignored...
        assertEquals(LISTENING, h.orb)
        h.after(80, Intent.PttUp) // ...up ends the turn
        assertEquals(THINKING, h.orb)
        assertFalse(h.s.latched)
    }

    @Test fun tapDuringConnectingLatchesThenListens() {
        val h = H.sim().send(Intent.PttDown).after(100, Intent.PttUp)
        assertEquals(CONNECTING, h.orb)
        assertTrue(h.s.latched)
        h.after(500, Intent.CallPhaseChanged(CallPhase.Ready))
        assertEquals(LISTENING, h.orb)
    }

    @Test fun holdAtExactlyTapThresholdIsARelease() {
        val h = H.listening().after(TAP_MS, Intent.PttUp)
        assertEquals(THINKING, h.orb)
    }

    @Test fun pttWhileOfflineShakesAndDoesNotRecord() {
        val h = H.real().send(Intent.LinkChanged(LinkState.Offline("x"))).after(10, Intent.PttDown)
        assertEquals(OFFLINE, h.orb)
        assertEquals(h.t, h.s.shakeAtMs)
        assertEquals(listOf<Effect>(Effect.ShowCaption(CAPTION_OFFLINE)), h.fx)
        assertEquals(PttState.Up, h.s.ptt)
        assertEquals(CallStatus.None, h.s.call)
    }

    // ---- forced state / layout ------------------------------------------------------------

    @Test fun forcedStateHoldsUntilAuto() {
        val h = H.sim().send(Intent.ForceOrb(THINKING))
        assertEquals(THINKING, h.orb)
        assertEquals(IDLE, h.s.machine)
        h.send(Intent.LinkChanged(LinkState.Offline("x"))) // machine moves, display holds
        assertEquals(THINKING, h.orb)
        assertEquals(OFFLINE, h.s.machine)
        h.send(Intent.ForceOrb(null))
        assertEquals(OFFLINE, h.orb)
        assertEquals(THINKING, h.s.orbPrevious)
    }

    @Test fun forceIgnoredInRealMode() {
        val h = H.real().send(Intent.ForceOrb(SPEAKING))
        assertEquals(IDLE, h.orb)
        assertNull(h.s.forcedOrb)
    }

    @Test fun layoutDocksImmediatelyAndReturnsAfterDelay() {
        val h = H.sim()
        assertEquals(OrbLayout.CENTERED, h.s.layout)
        h.send(cmd("display.card", "{\"title\":\"A\",\"id\":\"a\"}"))
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(5000, Intent.DismissPanel("a"))
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(LAYOUT_RETURN_MS - 1)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(1)
        assertEquals(OrbLayout.CENTERED, h.s.layout)
        assertEquals(h.t, h.s.layoutSinceMs)
        assertNull(h.s.nextDeadline())
    }

    @Test fun panelReturningCancelsLayoutReturn() {
        val h = H.sim().send(cmd("display.card", "{\"title\":\"A\",\"id\":\"a\"}")).after(100, Intent.DismissPanel("a"))
        h.after(500, cmd("display.card", "{\"title\":\"B\",\"id\":\"b\"}"))
        h.after(5000)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        assertNull(h.s.layoutReturnAt)
    }

    @Test fun pulsesAndNightFeedSnapshot() {
        val h = H.sim().after(10, Intent.DisplayPulse).after(10, Intent.AttentionPulse).send(Intent.SetNight(true))
        val snap = toOrbSnapshot(h.s)
        assertEquals(1010L, snap.pulseAtMs)
        assertEquals(1020L, snap.attentionAtMs)
        assertTrue(snap.night && snap.sim)
        assertEquals(IDLE, snap.state)
    }

    @Test fun nextDeadlineReportsEarliest() {
        val h = H.listening()
        assertEquals(h.s.pttWarnAt, h.s.nextDeadline())
        assertTrue(h.s.nextDeadline()!! < h.s.pttCapAt!!)
    }

    @Test fun advisoryErrorDoesNotChangeOrb() {
        val h = H.standby().after(10, Event(TransportEvent.AdvisoryError(2, TransportKind.SIM)))
        assertEquals(STANDBY, h.orb)
        assertEquals(2, h.s.advisoryErrors)
    }

    @Test fun setTestInputOnlySetsFlags() {
        val h = H.sim()
        val before = h.s
        assertFalse(before.testInputEnabled || before.testInputActive)
        h.send(Intent.SetTestInput(enabled = true, active = true))
        assertTrue(h.s.testInputEnabled && h.s.testInputActive)
        assertTrue(h.fx.isEmpty())
        assertEquals(before.copy(testInputEnabled = true, testInputActive = true), h.s)
        h.send(Intent.SetTestInput(enabled = true, active = false))
        assertTrue(h.s.testInputEnabled)
        assertFalse(h.s.testInputActive)
    }
}
