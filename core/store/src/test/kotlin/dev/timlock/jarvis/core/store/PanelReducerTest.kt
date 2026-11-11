package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbLayout
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelPlacement
import dev.timlock.jarvis.core.model.LeaderAnchor
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.transport.TransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class PH(var s: AppState = AppState()) {
    var t = 1000L
    var fx: List<Effect> = emptyList()
    val kind get() = if (s.mode == Mode.REAL) TransportKind.REAL else TransportKind.SIM
    fun send(i: Input, at: Long = t): PH {
        t = at
        val r = reduce(s, i, at)
        s = r.state; fx = r.effects
        return this
    }
    fun after(ms: Long, i: Input = Intent.Tick) = send(i, t + ms)
    fun cmd(name: String, params: String, inv: String = "inv") = send(Event(TransportEvent.Command(inv, name, params, kind)))
    fun card(id: String, extra: String = "") = cmd("display.card", """{"title":"T","id":"$id"$extra}""")
    val reply get() = fx.filterIsInstance<Effect.ReplyCommand>().single()
    val ids get() = s.panels.panels.map { it.id }

    companion object {
        val online = LinkState.Online("n", "vm")
        fun sim() = PH().send(Intent.ChooseMode(Mode.SIM)).send(Intent.BootComplete).send(Intent.LinkChanged(online))
        fun real() = PH().send(Intent.ChooseMode(Mode.REAL)).send(Intent.BootComplete).send(Intent.LinkChanged(online))
    }
}

class PanelReducerTest {
    @Test fun commandRepliesPulsesAndAttention() {
        val h = PH.sim().card("a")
        assertEquals("inv", h.reply.invocationId)
        assertEquals(CommandResult.ok("""{"id":"a","shown":true,"truncated":false}"""), h.reply.result)
        assertEquals(h.t + 380, h.s.pulseAtMs) // C9: first docked panel pulses when brackets pop
        assertEquals(h.t, h.s.attentionAtMs)
        assertEquals(1, h.s.panelCount)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        assertTrue(h.s.panels.panels[0].sim)
    }

    @Test fun noAttentionPulseDuringCall() {
        val h = PH.sim().send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
        h.card("a")
        assertEquals(h.t + 380, h.s.pulseAtMs) // layout was CENTERED: full timeline, pulse at bracket pop
        assertEquals(0L, h.s.attentionAtMs)
        assertNull(h.s.panels.panels[0].expiresAtMs)
    }

    @Test fun rejectRepliesError() {
        val h = PH.sim().cmd("display.card", "{}")
        assertEquals(CommandResult.error("title: required"), h.reply.result)
        assertEquals(0, h.s.panelCount)
        assertEquals(0L, h.s.pulseAtMs)
    }

    @Test fun generatedIdsAreDeterministic() {
        val a = PH.sim().cmd("display.card", """{"title":"x"}""")
        val b = PH.sim().cmd("display.card", """{"title":"x"}""")
        assertEquals(a.ids, b.ids)
        assertTrue(Regex("card-[0-9a-f]{4}").matches(a.ids[0]))
        a.cmd("display.card", """{"title":"x"}""")
        assertEquals(2, a.ids.distinct().size)
    }

    @Test fun rateLimitEleventhRejected() {
        val h = PH.sim()
        repeat(10) { h.after(100).card("p$it") ; assertTrue(h.reply.result.ok) }
        h.after(100).card("p11")
        assertEquals(CommandResult.error("rate limited"), h.reply.result)
        h.after(100).cmd("display.clear", "{}")
        assertEquals("rate limited", h.reply.result.error)
        // window slides: the first hit was at t0+100, so at +5100 there is room again
        h.send(Intent.Tick, 1000 + 5100 + 100)
        h.card("q")
        assertTrue(h.reply.result.ok)
    }

    @Test fun clearByIdAndAll() {
        val h = PH.sim().card("a").card("b").card("c")
        h.cmd("display.clear", """{"id":"b"}""")
        assertEquals("""{"id":"b","cleared":1}""", h.reply.result.payloadJson)
        assertEquals(listOf("c", "a"), h.ids)
        h.cmd("display.clear", """{"id":"zz"}""")
        assertEquals("""{"id":"zz","cleared":0}""", h.reply.result.payloadJson)
        h.cmd("display.clear", "{}")
        assertEquals("""{"id":null,"cleared":2}""", h.reply.result.payloadJson)
        assertEquals(0, h.s.panelCount)
    }

    @Test fun expiryTickRemovesPanelAndLayoutReturnsAfterDelay() {
        val h = PH.sim().card("a")
        val due = h.s.nextDeadline()!!
        assertEquals(h.t + 120_000, due)
        h.send(Intent.Tick, due)
        assertEquals(0, h.s.panelCount)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(LAYOUT_RETURN_MS - 1)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(1)
        assertEquals(OrbLayout.CENTERED, h.s.layout)
    }

    @Test fun callEndStartsExpiryClock() {
        val h = PH.sim().send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
        h.card("a")
        assertNull(h.s.panels.panels[0].expiresAtMs)
        h.after(10_000, Intent.HangUp)
        assertEquals(h.t + 120_000, h.s.panels.panels[0].expiresAtMs)
        h.after(120_000)
        assertEquals(0, h.s.panelCount)
    }

    @Test fun panelIntents() {
        val h = PH.sim().card("a").card("b")
        h.send(Intent.PinPanel("a", true))
        assertTrue(h.s.panels.panels.first { it.id == "a" }.pinned)
        h.send(Intent.DismissPanel("b"))
        assertEquals(listOf("a"), h.ids)
        h.cmd("display.timer", """{"label":"x","seconds":5,"id":"tm"}""")
        h.send(Intent.AcknowledgeTimer("tm"))
        assertEquals(listOf("a"), h.ids)
        h.send(Intent.PanelCount(7))
        assertEquals(1, h.s.panelCount)
    }

    @Test fun expandIntent() {
        val items = (1..10).joinToString(",") { "\"i$it\"" }
        val h = PH.sim().cmd("display.list", """{"title":"x","id":"a","items":[$items]}""")
            .cmd("display.list", """{"title":"x","id":"b","items":[$items]}""")
        assertTrue(h.s.panels.panels[1].collapsed)
        h.send(Intent.ExpandPanel("a"))
        assertFalse(h.s.panels.panels[1].collapsed)
        assertTrue(h.s.panels.panels[0].collapsed)
    }

    @Test fun modeChangeWipesPanels() {
        val h = PH.sim().card("a")
        h.send(Intent.ChooseMode(Mode.REAL))
        assertEquals(0, h.s.panelCount)
        h.send(Intent.ChooseMode(Mode.SIM).let { it }).card("b")
        h.send(Intent.ResetMode)
        assertEquals(0, h.s.panelCount)
    }

    @Test fun timerChimesOnceAndExpiresAfterRingWindow() {
        val h = PH.sim().cmd("display.timer", """{"label":"Tea","seconds":10,"id":"tm"}""")
        val ends = h.t + 10_000
        assertEquals(ends, h.s.nextDeadline())
        h.send(Intent.Tick, ends)
        assertEquals(listOf(Effect.Earcon(EarconKind.TIMER_CHIME)), h.fx)
        assertTrue((h.s.panels.panels[0].content as PanelContent.Timer).chimed)
        assertEquals(ends + 60_000, h.s.nextDeadline())
        h.after(1000)
        assertTrue(h.fx.none { it is Effect.Earcon })
        h.send(Intent.Tick, ends + 60_000)
        assertEquals(0, h.s.panelCount)
        assertTrue(h.fx.none { it is Effect.Earcon })
    }

    @Test fun timerSurvivesCallEnd() {
        val h = PH.sim().send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
        h.cmd("display.timer", """{"label":"Tea","seconds":600,"id":"tm"}""")
        val exp = h.s.panels.panels[0].expiresAtMs
        h.after(5000, Intent.HangUp)
        assertEquals(exp, h.s.panels.panels[0].expiresAtMs)
    }

    @Test fun persistOnlyInRealModeForStickyAndTimers() {
        val sim = PH.sim().card("a", ""","sticky":true""")
        assertTrue(sim.fx.none { it is Effect.PersistPanels })
        val h = PH.real().card("plain")
        assertTrue(h.fx.none { it is Effect.PersistPanels })
        h.card("keep", ""","sticky":true""")
        val p = h.fx.filterIsInstance<Effect.PersistPanels>().single()
        assertEquals(listOf("keep"), p.stack.panels.map { it.id })
        h.cmd("display.timer", """{"label":"x","seconds":9,"id":"tm"}""")
        assertEquals(setOf("keep", "tm"), h.fx.filterIsInstance<Effect.PersistPanels>().single().stack.panels.map { it.id }.toSet())
        h.send(Intent.DismissPanel("keep"))
        assertEquals(listOf("tm"), h.fx.filterIsInstance<Effect.PersistPanels>().single().stack.panels.map { it.id })
        h.send(Intent.DismissPanel("plain"))
        assertTrue(h.fx.none { it is Effect.PersistPanels })
    }

    @Test fun restorePanelsRealOnly() {
        val src = PH.real().card("keep", ""","sticky":true""")
        val saved = src.s.panels
        val real = PH.real().send(Intent.RestorePanels(saved))
        assertEquals(listOf("keep"), real.ids)
        assertEquals(PanelPlacement.CHIP, real.s.panels.panels[0].placement)
        assertEquals(OrbLayout.CENTERED, real.s.layout) // H1: restored sticky panels are chips
        val sim = PH.sim().send(Intent.RestorePanels(saved))
        assertEquals(0, sim.s.panelCount)
    }

    @Test fun mismatchedKindCommandDropped() {
        val h = PH.sim()
        h.send(Event(TransportEvent.Command("i", "display.card", """{"title":"x"}""", TransportKind.REAL)))
        assertEquals(0, h.s.panelCount)
        assertTrue(h.fx.isEmpty())
    }

    // ---- H1 placement ----------------------------------------------------------------------

    private fun inCall() = PH.sim().send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
    private fun PH.place(id: String) = s.panels.panels.first { it.id == id }.placement
    private val STICKY = ""","sticky":true"""

    @Test fun stickyAmbientPushStaysCenteredAsChip() { // the H1 blocker scenario
        val h = PH.sim().card("w", STICKY)
        assertEquals(PanelPlacement.CHIP, h.place("w"))
        assertEquals(OrbLayout.CENTERED, h.s.layout)
        assertEquals(0, h.s.dockCount)
        assertEquals(h.t, h.s.pulseAtMs) // chip arrival pulses now
        assertEquals(h.t, h.s.attentionAtMs)
        assertEquals(h.t - 380, h.s.panels.panels[0].revealStartMs)
        assertTrue(toOrbSnapshot(h.s).leaders.isEmpty())
    }

    @Test fun timerAmbientIsChipAndNonStickyAmbientDocks() {
        val h = PH.sim().cmd("display.timer", """{"label":"x","seconds":60,"id":"tm"}""")
        assertEquals(PanelPlacement.CHIP, h.place("tm"))
        assertEquals(OrbLayout.CENTERED, h.s.layout)
        h.card("c")
        assertEquals(PanelPlacement.DOCK, h.place("c"))
        assertEquals(OrbLayout.DOCKED, h.s.layout)
    }

    @Test fun duringCallEverythingDocks() {
        val h = inCall().card("a", STICKY).cmd("display.timer", """{"label":"x","seconds":60,"id":"tm"}""")
        assertEquals(PanelPlacement.DOCK, h.place("a"))
        assertEquals(PanelPlacement.DOCK, h.place("tm"))
        assertEquals(OrbLayout.DOCKED, h.s.layout)
    }

    @Test fun chipUpdatedDuringCallPromotesToDock() {
        val h = PH.sim().card("w", STICKY)
        h.send(Intent.PttDown).send(Intent.CallPhaseChanged(CallPhase.Ready))
        assertEquals(PanelPlacement.CHIP, h.place("w")) // opening a call alone does not promote
        assertEquals(OrbLayout.CENTERED, h.s.layout)
        h.after(50).card("w", STICKY)
        assertEquals(PanelPlacement.DOCK, h.place("w"))
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        assertEquals(h.t, h.s.panels.panels[0].revealStartMs) // layout was not docked -> full timeline
        assertEquals(h.t + 380, h.s.pulseAtMs)
    }

    @Test fun pinDoesNotChangePlacement() {
        val h = PH.sim().card("a")
        h.send(Intent.PinPanel("a", true))
        assertEquals(PanelPlacement.DOCK, h.place("a"))
        h.send(Intent.PinPanel("a", false))
        assertEquals(PanelPlacement.DOCK, h.place("a"))
        val c = PH.sim().card("w", STICKY)
        c.send(Intent.PinPanel("w", false))
        assertEquals(PanelPlacement.CHIP, c.place("w"))
    }

    @Test fun callEndDemotesPersistentPanelsToChips() {
        val h = inCall().card("s", STICKY).card("n").cmd("display.timer", """{"label":"x","seconds":600,"id":"tm"}""")
        h.send(Intent.PinPanel("n", true)) // pinned during the call: still DOCK
        h.after(5000, Intent.HangUp)
        assertEquals(PanelPlacement.CHIP, h.place("s"))
        assertEquals(PanelPlacement.CHIP, h.place("n"))
        assertEquals(PanelPlacement.CHIP, h.place("tm"))
        assertEquals(h.t - 380, h.s.panels.panels.first { it.id == "s" }.revealStartMs)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
    }

    @Test fun lastDockLeavingStartsReturnTimerEvenWithChipsPresent() {
        val h = inCall().card("s", STICKY).card("n")
        h.after(1000, Intent.HangUp)
        assertEquals(PanelPlacement.CHIP, h.place("s"))
        assertEquals(PanelPlacement.DOCK, h.place("n"))
        h.send(Intent.DismissPanel("n"))
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(LAYOUT_RETURN_MS - 1)
        assertEquals(OrbLayout.DOCKED, h.s.layout)
        h.after(1)
        assertEquals(OrbLayout.CENTERED, h.s.layout)
        assertEquals(listOf("s"), h.ids)
    }

    @Test fun callEndWithOnlyStickyReturnsToCentered() {
        val h = inCall().card("s", STICKY)
        h.after(1000, Intent.HangUp)
        assertEquals(PanelPlacement.CHIP, h.place("s"))
        h.after(LAYOUT_RETURN_MS)
        assertEquals(OrbLayout.CENTERED, h.s.layout)
    }

    @Test fun revealStartFirstVsSubsequentDock() {
        val h = PH.sim().card("a")
        assertEquals(h.t, h.s.panels.panels[0].revealStartMs)
        assertEquals(h.t + 380, h.s.pulseAtMs)
        h.after(2000).card("b")
        val b = h.s.panels.panels.first { it.id == "b" }
        assertEquals(h.t - 380, b.revealStartMs)
        assertEquals(h.t, h.s.pulseAtMs) // revealStart + 380
        h.after(100).card("b") // in-place update: no new timeline, pulse now
        assertEquals(h.t - 480, h.s.panels.panels.first { it.id == "b" }.revealStartMs)
        assertEquals(h.t, h.s.pulseAtMs)
    }

    @Test fun leadersForThreePanelStack() {
        val h = PH.sim().card("a", ""","body":"x"""").card("b").cmd("display.stats", """{"title":"s","id":"c","metrics":[{"label":"a","value":"1"}]}""")
        val ps = h.s.panels.panels
        assertEquals(listOf("c", "b", "a"), ps.map { it.id })
        val e = ps.map { PanelHeights.estimatePx(it) }
        val l = toOrbSnapshot(h.s).leaders
        assertEquals(3, l.size)
        assertEquals(64f + e[0] / 2f, l[0].yPx, 0.01f)
        assertEquals(64f + e[0] + 16 + e[1] / 2f, l[1].yPx, 0.01f)
        assertEquals(64f + e[0] + 16 + e[1] + 16 + e[2] / 2f, l[2].yPx, 0.01f)
        assertEquals(ps[1].revealStartMs, l[1].revealStartMs)
        assertEquals(listOf(0L, 0L, 0L), l.map { it.pulseAtMs })
        h.after(500).card("b")
        val l2 = toOrbSnapshot(h.s).leaders
        assertEquals(h.t, l2[1].pulseAtMs) // C15 data pulse on in-place update
        assertEquals(0L, l2[0].pulseAtMs)
    }

    @Test fun leadersSkipChips() {
        val h = inCall().card("s", STICKY).card("n")
        h.after(10, Intent.HangUp)
        h.card("z")
        val l = toOrbSnapshot(h.s).leaders
        assertEquals(2, l.size) // n and z docked; s is a chip
        assertEquals(64f + PanelHeights.estimatePx(h.s.panels.panels.first { it.id == "z" }) / 2f, l[0].yPx, 0.01f)
    }
}
