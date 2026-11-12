package dev.timlock.jarvis.transport.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledScenariosTest {
    private val dir = File("src/main/assets/scenarios")
    private val expected = setOf(
        "hello_short", "weather_mid_reply", "card_while_thinking", "planning_outline", "long_list", "panel_flood",
        "sticky_overflow", "multi_turn", "barge_in_long", "slow_think", "error_offline_mid_reply", "token_revoked",
        "admission_fails", "send_failure", "ambient_push_timer", "soak_perf", "invalid_actions", "call_takeover",
    )

    private fun parsed(): Map<String, Scenario> = dir.listFiles { f -> f.name.endsWith(".json") }!!.associate { f ->
        when (val r = ScenarioParser.parse(f.readText())) {
            is ParseResult.Ok -> { assertEquals("file name must equal id", f.nameWithoutExtension, r.scenario.id); r.scenario.id to r.scenario }
            is ParseResult.Error -> error("${f.name}: ${r.message}")
        }
    }

    @Test fun allEighteenParse() {
        assertTrue("scenario dir missing: ${dir.absolutePath}", dir.isDirectory)
        assertEquals(expected, parsed().keys)
    }

    @Test fun everyScenarioExceptSoakEndsWithAnExpect() {
        for ((id, s) in parsed()) {
            if (id == "soak_perf") continue
            assertTrue("$id must have expects", s.steps.any { it.op is Op.Expect })
            assertTrue("$id must end with an expect", s.steps.last().op is Op.Expect)
            assertTrue("$id has no infinite loop", s.steps.none { it.op is Op.Loop })
        }
    }

    @Test fun soakLoopsAndHasNoExpects() {
        val s = parsed().getValue("soak_perf")
        assertTrue(s.steps.any { it.op is Op.Loop })
        assertTrue(s.steps.none { it.op is Op.Expect })
    }
}
