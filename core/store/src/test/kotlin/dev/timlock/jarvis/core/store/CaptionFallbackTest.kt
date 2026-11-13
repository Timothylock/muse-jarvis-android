package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.OrbState.CONNECTING
import dev.timlock.jarvis.core.model.OrbState.ERROR
import dev.timlock.jarvis.core.model.OrbState.IDLE
import dev.timlock.jarvis.core.model.OrbState.LISTENING
import dev.timlock.jarvis.core.model.OrbState.SPEAKING
import dev.timlock.jarvis.core.model.OrbState.STANDBY
import dev.timlock.jarvis.core.model.OrbState.THINKING
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class F(var s: AppState = AppState()) {
    var t = 1000L
    var fx: List<Effect> = emptyList()

    fun send(i: Input, at: Long = t): F {
        t = at
        val r = reduce(s, i, at)
        s = r.state
        fx = r.effects
        return this
    }
    fun after(ms: Long, i: Input = Intent.Tick) = send(i, t + ms)
    val orb get() = s.orbState

    companion object {
        val online = LinkState.Online("n", "vm")
        fun sim() = F().send(Intent.ChooseMode(Mode.SIM)).send(Intent.BootComplete).send(Intent.LinkChanged(online))
        fun transcript(role: Role, text: String, final: Boolean) =
            Event(TransportEvent.Transcript(role, text, final, TransportKind.SIM))

        /** Fallback active and THINKING with FallbackSend requested (second failure). */
        fun fallbackThinking(): F {
            val f = sim().failAttempt()
            check(f.orb == ERROR)
            f.after(ERROR_TOTAL_MS)
            return f.failAttempt()
        }
    }
}

/** One failed realtime attempt (PTT hold 500 ms, then the call never becomes ready). */
private fun F.failAttempt(reason: String = "ready_timeout"): F =
    after(100, Intent.PttDown).after(500, Intent.PttUp).after(500, Intent.CallPhaseChanged(CallPhase.Ended(reason)))

class CaptionFallbackTest {
    // ---- captions ---------------------------------------------------------------------------

    @Test fun transcriptsAndSystemLinesSetTheCaption() {
        val f = F.sim().send(Intent.PttDown).after(10, Intent.CallPhaseChanged(CallPhase.Ready))
        f.after(100, F.transcript(Role.USER, "hello", false))
        assertEquals(dev.timlock.jarvis.core.model.Caption("hello", Role.USER, false, f.t), f.s.caption)
        f.after(10, F.transcript(Role.SYSTEM, "note", true))
        assertNull(f.s.caption!!.role)
        f.after(10, F.transcript(Role.MUSE, "hi there", true))
        assertEquals(Role.MUSE, f.s.caption!!.role)
        assertTrue(f.s.caption!!.final)
    }

    @Test fun partialsContinueTypingInsteadOfRestarting() {
        val f = F.sim().send(Intent.PttDown).after(10, Intent.CallPhaseChanged(CallPhase.Ready))
        f.after(0, F.transcript(Role.USER, "hello", false))
        val t0 = f.t
        f.after(50, F.transcript(Role.USER, "hello wor", false)) // 3 chars typed so far at 60 cps
        assertEquals(t0, f.s.caption!!.atMs)
        // Fully typed, then a long pause: the delta starts typing from the end of the old text, not from scratch.
        f.after(2000, F.transcript(Role.USER, "hello world", false))
        val c = f.s.caption!!
        assertEquals(f.t - 9 * 1000 / 60, c.atMs) // 9 chars already typed
        // A different text restarts.
        f.after(10, F.transcript(Role.USER, "bye", false))
        assertEquals(f.t, f.s.caption!!.atMs)
    }

    @Test fun captionFadesEightSecondsAfterTheCallGoesIdle() {
        val f = F.sim().send(Intent.PttDown).after(10, Intent.CallPhaseChanged(CallPhase.Ready))
        f.after(100, F.transcript(Role.MUSE, "done", true))
        f.after(100, Intent.HangUp)
        assertEquals(IDLE, f.orb)
        assertNotNull(f.s.caption)
        assertEquals(f.t + CAPTION_FADE_MS, f.s.captionClearAt)
        assertEquals(f.t + CAPTION_FADE_MS, f.s.nextDeadline())
        f.after(CAPTION_FADE_MS - 1)
        assertNotNull(f.s.caption)
        f.after(1)
        assertNull(f.s.caption)
        assertNull(f.s.captionClearAt)
    }

    @Test fun captionDoesNotFadeDuringACall() {
        val f = F.sim().send(Intent.PttDown).after(10, Intent.CallPhaseChanged(CallPhase.Ready)).after(300, Intent.PttUp)
        f.after(0, Intent.SinkFirstAudible).after(100, F.transcript(Role.MUSE, "reply", true))
        f.after(100, Intent.SinkDrained).after(SETTLE_MS)
        assertEquals(STANDBY, f.orb)
        assertNull(f.s.captionClearAt)
        f.after(20_000)
        assertNotNull(f.s.caption)
        // A new caption while idle re-arms the full 8 s.
        f.after(100, Intent.HangUp)
        val armed = f.s.captionClearAt
        assertEquals(f.t + CAPTION_FADE_MS, armed)
        f.after(5000, F.transcript(Role.SYSTEM, CAPTION_OFFLINE, true))
        assertEquals(f.t + CAPTION_FADE_MS, f.s.captionClearAt)
    }

    @Test fun callSinceIsSetWhenReadyAndZeroWhenItEnds() {
        val f = F.sim().send(Intent.PttDown)
        assertEquals(0L, f.s.callSinceMs)
        f.after(700, Intent.CallPhaseChanged(CallPhase.Ready))
        assertEquals(f.t, f.s.callSinceMs)
        val since = f.s.callSinceMs
        f.after(500, Intent.CallPhaseChanged(CallPhase.Ready)) // duplicate Ready keeps the start
        assertEquals(since, f.s.callSinceMs)
        f.after(500, Intent.HangUp)
        assertEquals(0L, f.s.callSinceMs)
    }

    // ---- fallback switch ----------------------------------------------------------------------

    @Test fun firstAdmissionFailureErrorsSecondSwitchesToFallback() {
        val f = F.sim().failAttempt("admission_failed")
        assertEquals(ERROR, f.orb)
        assertEquals(1, f.s.admitFailures.size)
        assertNull(f.s.fallbackUntil)
        assertFalse(Effect.FallbackSend in f.fx)
        f.after(ERROR_TOTAL_MS)
        assertEquals(IDLE, f.orb)
        f.failAttempt("ready_timeout")
        assertEquals(THINKING, f.orb)
        assertEquals(f.t + FALLBACK_WINDOW_MS, f.s.fallbackUntil)
        assertEquals(Effect.FallbackSend, f.fx.single { it is Effect.FallbackSend })
        assertTrue(Effect.MicStop in f.fx)
        assertTrue(f.fx.indexOf(Effect.FallbackSend) < f.fx.indexOf(Effect.MicStop))
        assertEquals(CAPTION_FALLBACK, f.s.caption!!.text)
        assertNull(f.s.caption!!.role)
        assertEquals(CallStatus.None, f.s.call)
        assertTrue(f.s.thinkDeadline != null)
    }

    @Test fun failuresMoreThanTenMinutesApartDoNotSwitch() {
        val f = F.sim().failAttempt()
        f.after(FALLBACK_WINDOW_MS, Intent.Tick)
        f.failAttempt()
        assertEquals(ERROR, f.orb)
        assertNull(f.s.fallbackUntil)
        assertEquals(1, f.s.admitFailures.size)
    }

    @Test fun realtimeSuccessResetsFailureCount() {
        val f = F.sim().failAttempt()
        f.after(ERROR_TOTAL_MS).after(100, Intent.PttDown).after(300, Intent.CallPhaseChanged(CallPhase.Ready))
        assertTrue(f.s.admitFailures.isEmpty())
        f.after(100, Intent.PttUp).after(100, Intent.HangUp)
        f.failAttempt()
        assertEquals(ERROR, f.orb) // counts as a first failure again
    }

    @Test fun fallbackExpiresAfterTenMinutes() {
        val f = F.fallbackThinking().after(10, Intent.FallbackResult("ok", false, null)).after(10_000)
        assertEquals(IDLE, f.orb)
        f.after(FALLBACK_WINDOW_MS - 10_000 - 20)
        f.after(100, Intent.PttDown)
        assertEquals(CONNECTING, f.orb)
        assertTrue(Effect.OpenCall in f.fx)
    }

    // ---- PTT on the fallback path -------------------------------------------------------------

    private fun idleOnFallback(): F {
        val f = F.fallbackThinking().after(10, Intent.FallbackResult(null, false, "send_failed"))
        assertEquals(ERROR, f.orb)
        return f.after(ERROR_TOTAL_MS)
    }

    @Test fun pttOnFallbackBuffersWithoutOpeningACall() {
        val f = idleOnFallback()
        assertEquals(IDLE, f.orb)
        f.after(100, Intent.PttDown)
        assertEquals(LISTENING, f.orb)
        assertEquals(CallStatus.None, f.s.call)
        assertTrue(Effect.MicStart in f.fx)
        assertFalse(Effect.OpenCall in f.fx)
        assertFalse(Effect.MicLive in f.fx)
        f.after(1000, Intent.PttUp)
        assertEquals(THINKING, f.orb)
        assertEquals(listOf<Effect>(Effect.FallbackSend, Effect.MicStop), f.fx)
        assertEquals(f.t + THINK_TIMEOUT_MS, f.s.thinkDeadline)
    }

    @Test fun tapLatchWorksOnFallbackToo() {
        val f = idleOnFallback().after(100, Intent.PttDown).after(100, Intent.PttUp)
        assertEquals(LISTENING, f.orb)
        assertTrue(f.s.latched)
        f.after(1500, Intent.PttDown) // ignored while latched
        f.after(100, Intent.PttUp)
        assertEquals(THINKING, f.orb)
        assertTrue(Effect.FallbackSend in f.fx)
    }

    @Test fun admissionFailureWhileStillHoldingKeepsListeningAndSendsOnRelease() {
        val f = F.sim().failAttempt().after(ERROR_TOTAL_MS)
        f.after(100, Intent.PttDown).after(600, Intent.CallPhaseChanged(CallPhase.Ended("admission_failed")))
        assertEquals(LISTENING, f.orb)
        assertFalse(Effect.MicStop in f.fx)
        assertFalse(Effect.FallbackSend in f.fx)
        assertTrue(f.s.ptt is PttState.Held)
        f.after(800, Intent.PttUp)
        assertEquals(THINKING, f.orb)
        assertTrue(Effect.FallbackSend in f.fx)
    }

    // ---- fallback turn results -----------------------------------------------------------------

    @Test fun audioReplyWaitsForSinkAndEndsIdleWithoutACall() {
        val f = F.fallbackThinking().after(300, Intent.FallbackResult("Hello there", true, null))
        assertEquals(THINKING, f.orb)
        assertEquals("Hello there", f.s.caption!!.text)
        assertEquals(Role.MUSE, f.s.caption!!.role)
        f.after(100, Intent.SinkFirstAudible)
        assertEquals(SPEAKING, f.orb)
        f.after(2000, Intent.SinkDrained)
        assertEquals(SPEAKING, f.orb)
        f.after(SETTLE_MS)
        assertEquals(IDLE, f.orb)
        assertEquals(CallStatus.None, f.s.call)
    }

    @Test fun audioBeforeResultStillWorks() {
        val f = F.fallbackThinking().after(100, Intent.SinkFirstAudible)
        assertEquals(SPEAKING, f.orb)
        f.after(100, Intent.FallbackResult("late text", true, null))
        assertEquals(SPEAKING, f.orb)
        assertEquals("late text", f.s.caption!!.text)
        assertFalse(f.s.fallbackTurn)
    }

    @Test fun textOnlyReplyShowsCaptionOnlyAndSpeaksLite() {
        val f = F.fallbackThinking().after(300, Intent.FallbackResult("one two three", false, null))
        assertEquals(SPEAKING, f.orb)
        assertEquals(Role.MUSE, f.s.caption!!.role)
        assertEquals("one two three", f.s.caption!!.text)
        // Fix batch 2026-11-12: a plain text reply lives in the caption bar only; no panel is created.
        assertTrue(f.s.panels.panels.isEmpty())
        assertEquals(OrbLayout.CENTERED, f.s.layout)
        assertEquals(f.t + 3 * SPEAKING_LITE_MS_PER_WORD, f.s.settleDeadline)
        f.after(3 * SPEAKING_LITE_MS_PER_WORD - 1)
        assertEquals(SPEAKING, f.orb)
        f.after(1)
        assertEquals(IDLE, f.orb)
        assertEquals(CallStatus.None, f.s.call)
    }

    @Test fun errorResultErrorsWithCaptionThenIdle() {
        val f = F.fallbackThinking().after(300, Intent.FallbackResult(null, false, "send_failed"))
        assertEquals(ERROR, f.orb)
        assertEquals(CAPTION_FALLBACK_ERROR, f.s.caption!!.text)
        f.after(ERROR_TOTAL_MS)
        assertEquals(IDLE, f.orb)
    }

    @Test fun thinkTimeoutStillAppliesAndLateResultIsIgnored() {
        val f = F.fallbackThinking().after(THINK_TIMEOUT_MS)
        assertEquals(ERROR, f.orb)
        assertEquals(CAPTION_SLOW, f.s.caption!!.text)
        f.after(10, Intent.FallbackResult("late", false, null))
        assertEquals(ERROR, f.orb)
        assertTrue(f.s.panels.panels.isEmpty())
    }

    @Test fun strayResultWithoutAFallbackTurnIsIgnored() {
        val f = F.sim().send(Intent.FallbackResult("x", false, null))
        assertEquals(IDLE, f.orb)
        assertNull(f.s.caption)
    }

    @Test fun hangUpOnFallbackCancelsTheBufferedUtterance() {
        val f = idleOnFallback().after(100, Intent.PttDown).after(100, Intent.PttUp)
        assertTrue(f.s.latched)
        f.after(100, Intent.HangUp)
        assertEquals(IDLE, f.orb)
        assertEquals(PttState.Up, f.s.ptt)
        assertTrue(Effect.MicStop in f.fx)
        assertFalse(Effect.FallbackSend in f.fx)
    }
}
