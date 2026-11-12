package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioEngineTest {
    private class FakeHost(val sched: TestCoroutineScheduler) : ScenarioHost {
        val anchors = AnchorTracker { sched.currentTime }
        val rec = ArrayList<Pair<Long, String>>()
        val logs = ArrayList<String>()
        var snap = SimSnapshot("idle", 0, "none", "online", emptyList())
        var done: Pair<Int, Int>? = null
        var speakOk = true
        override fun nowMs() = sched.currentTime
        override fun pttDown() { rec += sched.currentTime to "ptt_down"; anchors.fire(Anchor.PTT_DOWN) }
        override fun pttUp() { rec += sched.currentTime to "ptt_up"; anchors.fire(Anchor.PTT_UP) }
        override fun snapshot() = snap
        override fun emit(event: TransportEvent) {
            rec += sched.currentTime to when (event) {
                is TransportEvent.TurnStarted -> "turn_started ${event.id}"
                is TransportEvent.TurnSettled -> "turn_settled ${event.id}"
                is TransportEvent.Transcript -> "transcript ${if (event.role == Role.USER) "user" else "muse"} ${event.text}"
                else -> "event"
            }
        }
        override suspend fun command(name: String, paramsJson: String): CommandResult? { rec += sched.currentTime to "command $name"; return CommandResult.ok() }
        override fun speak(text: String, ms: Long): Boolean { rec += sched.currentTime to "speak $text $ms"; return speakOk }
        override fun fault(kind: SimFault, ms: Long?): String? { rec += sched.currentTime to "fault ${kind.wire} $ms"; return null }
        override fun setAdmission(ms: Long) { rec += sched.currentTime to "admission $ms" }
        override fun remoteStop(): String? { rec += sched.currentTime to "remote_stop"; return null }
        override fun takeover(): String? { rec += sched.currentTime to "takeover"; return null }
        override fun repair(): String? { rec += sched.currentTime to "repair"; return null }
        override suspend fun prepareStart() { rec += sched.currentTime to "prepare" }
        override fun log(line: String) { logs += line }
        override fun onProgress(step: Int, total: Int, asserts: Int, failed: Int) {}
        override fun onDone(asserts: Int, failed: Int) { done = asserts to failed }
        fun count(prefix: String) = rec.count { it.second.startsWith(prefix) }
        fun times(prefix: String) = rec.filter { it.second.startsWith(prefix) }.map { it.first }
    }

    private fun scenario(vararg steps: String, head: String = "") = (ScenarioParser.parse(
        """{"id":"t",$head "steps":[${steps.joinToString(",")}]}""",
    ) as? ParseResult.Ok ?: error("bad scenario")).scenario

    private fun TestScope.go(h: FakeHost, sc: Scenario, ts: Float = 1f, auto: Long? = null): ScenarioEngine {
        val e = ScenarioEngine(backgroundScope, h, h.anchors, ts, auto)
        e.start(sc)
        runCurrent()
        return e
    }

    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - currentTime); runCurrent() }

    @Test fun absoluteAndRelativeOrdering() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"at":1000,"op":"turn_started"}""",
            """{"at":"+250","op":"turn_settled"}""",
            """{"at":500,"op":"command","name":"display.card","params":{"title":"x"}}""", // in the past: runs right after
            """{"op":"remote_stop"}""",
        ))
        at(999); assertEquals(1 /* prepare */, h.rec.size)
        at(1000); assertEquals(listOf(1000L), h.times("turn_started"))
        at(1250)
        assertEquals(listOf(1250L), h.times("turn_settled"))
        assertEquals(listOf(1250L), h.times("command"))
        assertEquals(listOf(1250L), h.times("remote_stop"))
        assertEquals(0 to 0, h.done)
        assertTrue(h.logs.any { it.contains("scenario_done") })
    }

    @Test fun prepareStartOnlyForIdle() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario("""{"op":"turn_started"}""", head = """"start":"keep","""))
        assertEquals(0, h.count("prepare"))
    }

    @Test fun stepsWithoutTimingFollowPrevious() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario("""{"at":300,"op":"turn_started"}""", """{"op":"turn_settled"}"""))
        at(300)
        assertEquals(listOf(300L), h.times("turn_started")); assertEquals(listOf(300L), h.times("turn_settled"))
    }

    @Test fun timescaleDividesEverything() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"at":1000,"op":"turn_started"}""",
            """{"at":"+1000","op":"speak","text":"hello","ms":4000}""",
            """{"op":"admission","ms":600}""",
        ), ts = 2f)
        at(500); assertEquals(1, h.count("turn_started"))
        at(1000)
        assertEquals(listOf(1000L), h.times("speak"))
        assertTrue(h.rec.any { it.second == "speak hello 2000" })
        // admission ms is passed through unscaled: the transport applies the scale
        assertTrue(h.rec.any { it.second == "admission 600" })
    }

    @Test fun timescaleIsClamped() = runTest {
        assertEquals(4f, ScenarioEngine(backgroundScope, FakeHost(testScheduler), AnchorTracker { 0 }, 99f).timescale)
        assertEquals(0.25f, ScenarioEngine(backgroundScope, FakeHost(testScheduler), AnchorTracker { 0 }, 0.01f).timescale)
    }

    @Test fun afterAnchorWaitsAndAppliesOffset() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"after":"speech_start","at":"+600","op":"turn_started"}""",
            """{"after":"speech_end","op":"turn_settled"}""",
        ))
        at(5000); assertEquals(0, h.count("turn_started"))
        h.anchors.fire(Anchor.SPEECH_START)
        at(5599); assertEquals(0, h.count("turn_started"))
        at(5600); assertEquals(listOf(5600L), h.times("turn_started"))
        at(7000); assertEquals(0, h.count("turn_settled"))
        h.anchors.fire(Anchor.SPEECH_END); runCurrent()
        assertEquals(listOf(7000L), h.times("turn_settled"))
    }

    @Test fun alreadyHappenedAnchorDoesNotDeadlock() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"op":"wait","ms":1000}""",
            """{"after":"ptt_up","op":"turn_started"}""",
            """{"after":"ptt_up","at":"+2000","op":"turn_settled"}""",
        ))
        at(200); h.anchors.fire(Anchor.PTT_UP)
        at(1000)
        assertEquals(listOf(1000L), h.times("turn_started")) // event at 200 is in the past: run immediately
        // the same occurrence is consumed; the next `after ptt_up` waits for a NEW one
        at(3000); assertEquals(0, h.count("turn_settled"))
        h.anchors.fire(Anchor.PTT_UP)
        at(5000); assertEquals(listOf(5000L), h.times("turn_settled"))
    }

    @Test fun anchorsBeforeRunStartAreIgnored() = runTest {
        val h = FakeHost(testScheduler)
        h.anchors.fire(Anchor.PTT_UP)
        go(h, scenario("""{"after":"ptt_up","op":"turn_started"}"""))
        at(500); assertEquals(0, h.count("turn_started"))
    }

    @Test fun awaitPttManualBlocksUntilPress() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"at":0,"op":"await_ptt","user_text":"hello"}""",
            """{"at":"+100","op":"turn_started"}""",
        ))
        at(5000); assertEquals(0, h.count("turn_started")); assertEquals(0, h.count("ptt_down"))
        h.pttDown(); runCurrent()
        at(5100); assertEquals(listOf(5100L), h.times("turn_started"))
        assertEquals(0, h.count("transcript"))
        h.pttUp(); runCurrent()
        assertEquals(listOf("transcript user hello"), h.rec.map { it.second }.filter { it.startsWith("transcript") })
    }

    @Test fun awaitPttAutoPressesAndReleases() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"at":0,"op":"await_ptt","user_text":"hello","hold_ms":1000}""",
            """{"after":"ptt_up","op":"turn_started"}""",
        ), auto = 500)
        at(499); assertEquals(0, h.count("ptt_down"))
        at(500); assertEquals(listOf(500L), h.times("ptt_down"))
        at(1499); assertEquals(0, h.count("ptt_up"))
        at(1500)
        assertEquals(listOf(1500L), h.times("ptt_up"))
        assertEquals(listOf(1500L), h.times("turn_started"))
        assertEquals(listOf(1500L), h.times("transcript user hello"))
    }

    @Test fun autoPttMsInScenarioAndHoldScaled() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario("""{"op":"await_ptt"}""", head = """"auto_ptt_ms":800,"hold_ms":600,"""), ts = 2f)
        at(400); assertEquals(listOf(400L), h.times("ptt_down"))
        at(700); assertEquals(listOf(700L), h.times("ptt_up"))
    }

    @Test fun pttOpOnlyActsInAutoMode() = runTest {
        val manual = FakeHost(testScheduler)
        go(manual, scenario("""{"op":"ptt","value":"down"}"""))
        assertEquals(0, manual.count("ptt_down"))
        val auto = FakeHost(testScheduler)
        go(auto, scenario("""{"op":"ptt","value":"down"}""", """{"at":"+100","op":"ptt","value":"up"}"""), auto = 1)
        at(100)
        assertEquals(listOf(0L), auto.times("ptt_down")); assertEquals(listOf(100L), auto.times("ptt_up"))
    }

    @Test fun speakWithoutCallWarns() = runTest {
        val h = FakeHost(testScheduler).apply { speakOk = false }
        go(h, scenario("""{"op":"speak","text":"abcd"}"""))
        assertTrue(h.logs.any { it.contains("skipped") })
        assertEquals("speak abcd 285", h.rec.first { it.second.startsWith("speak") }.second) // 4 chars at 14 cps
    }

    @Test fun expectPassAndFailCounting() = runTest {
        val h = FakeHost(testScheduler).apply { snap = SimSnapshot("thinking", 2, "ready", "online", listOf("a", "b")) }
        go(h, scenario(
            """{"op":"expect","orb":"THINKING","panel_count":2,"call":"ready","link":"online","panel_ids":["b","a"]}""",
            """{"op":"expect","orb":"standby"}""",
            """{"op":"expect","panel_count":3,"call":"none"}""",
        ))
        assertEquals(3 to 2, h.done)
        val expects = h.logs.filter { it.contains("\"cmd\":\"expect\"") }
        assertEquals(3, expects.size)
        assertTrue(expects[0].contains("\"ok\":true"))
        assertTrue(expects[1].contains("\"ok\":false") && expects[1].contains("SIM_ASSERT_FAIL orb expected standby got thinking"))
        assertTrue(expects[2].contains("panel_count expected 3 got 2") && expects[2].contains("call expected none got ready"))
        val done = h.logs.last()
        assertTrue(done.contains("\"cmd\":\"scenario_done\"") && done.contains("\"asserts\":3") && done.contains("\"failed\":2"))
    }

    @Test fun expectWithinPollsUntilItPasses() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario("""{"op":"expect","orb":"standby","within_ms":1000}""", """{"op":"expect","orb":"never","within_ms":300}"""))
        at(300); h.snap = h.snap.copy(orb = "standby")
        at(2000)
        assertEquals(2 to 1, h.done)
        assertTrue(h.logs.filter { it.contains("\"cmd\":\"expect\"") }.first().contains("\"ok\":true"))
    }

    @Test fun loopWithCount() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"op":"turn_started"}""",
            """{"at":"+100","op":"speak","text":"x","ms":10}""",
            """{"op":"loop","to":0,"count":2}""",
            """{"op":"turn_settled"}""",
        ))
        at(5000)
        assertEquals(3, h.count("turn_started")) // 1 + 2 repeats
        assertEquals(1, h.count("turn_settled"))
        assertEquals(0 to 0, h.done)
    }

    @Test fun infiniteLoopRunsUntilStopped() = runTest {
        val h = FakeHost(testScheduler)
        val e = go(h, scenario("""{"at":"+200","op":"turn_started"}""", """{"op":"loop"}"""))
        at(2000)
        val n = h.count("turn_started")
        assertTrue("n=$n", n in 5..10)
        e.stop(); runCurrent()
        at(4000)
        assertEquals(n, h.count("turn_started"))
        assertEquals(null, h.done) // stopped, not finished
    }

    @Test fun pauseFreezesTheTimeline() = runTest {
        val h = FakeHost(testScheduler)
        val e = go(h, scenario("""{"at":1000,"op":"turn_started"}"""))
        at(400); e.pause(true)
        at(5000); assertEquals(0, h.count("turn_started"))
        e.pause(false); runCurrent()
        at(5599); assertEquals(0, h.count("turn_started"))
        at(5600); assertEquals(listOf(5600L), h.times("turn_started"))
    }

    @Test fun turnIdsAndFaultAndCallOps() = runTest {
        val h = FakeHost(testScheduler)
        go(h, scenario(
            """{"op":"turn_started"}""", """{"op":"turn_settled"}""", """{"op":"turn_started"}""",
            """{"op":"fault","kind":"offline","ms":8000}""", """{"op":"call_takeover"}""", """{"op":"repair"}""",
            """{"op":"transcript","role":"muse","text":"hey"}""",
        ))
        val r = h.rec.map { it.second }
        assertTrue(r.contains("turn_started sim-turn-1")); assertTrue(r.contains("turn_settled sim-turn-1")); assertTrue(r.contains("turn_started sim-turn-2"))
        assertTrue(r.contains("fault offline 8000")); assertTrue(r.contains("takeover")); assertTrue(r.contains("repair"))
        assertTrue(r.contains("transcript muse hey"))
        assertFalse(h.logs.any { it.contains("scenario_error") })
    }
}
