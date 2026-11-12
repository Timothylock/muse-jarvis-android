package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.SimFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioParserTest {
    private fun ok(json: String): Scenario = (ScenarioParser.parse(json) as? ParseResult.Ok)?.scenario
        ?: error("expected Ok for $json: ${ScenarioParser.parse(json)}")
    private fun err(json: String): String = (ScenarioParser.parse(json) as? ParseResult.Error)?.message ?: error("expected Error for $json")
    private fun one(step: String) = ok("""{"id":"t","steps":[$step]}""").steps.single()

    @Test fun topLevelDefaults() {
        val s = ok("""{"id":"x","steps":[]}""")
        assertEquals("x", s.id); assertEquals("x", s.title); assertEquals(1, s.dp)
        assertTrue(s.startIdle); assertEquals(1500L, s.holdMs); assertEquals(null, s.autoPttMs)
    }

    @Test fun topLevelFields() {
        val s = ok("""{"id":"x","dp":1,"title":"T","start":"keep","auto_ptt_ms":700,"hold_ms":900,"admit_ms":300,"think_ms":400,"note":"n","steps":[]}""")
        assertFalse(s.startIdle); assertEquals(700L, s.autoPttMs); assertEquals(900L, s.holdMs)
        assertEquals(300L, s.admitMs); assertEquals(400L, s.thinkMs); assertEquals("n", s.note); assertEquals("T", s.title)
    }

    @Test fun timingForms() {
        assertEquals(Timing.Abs(1200), one("""{"at":1200,"op":"turn_started"}""").timing)
        assertEquals(Timing.Rel(300), one("""{"at":"+300","op":"turn_started"}""").timing)
        assertEquals(Timing.Rel(0), one("""{"op":"turn_started"}""").timing)
        assertEquals(Timing.After(Anchor.PTT_UP, 0), one("""{"after":"ptt_up","op":"turn_started"}""").timing)
        assertEquals(Timing.After(Anchor.SPEECH_START, 6000), one("""{"after":"speech_start","at":"+6000","op":"turn_started"}""").timing)
        assertEquals(Timing.After(Anchor.SPEECH_END, 0), one("""{"after":"speech_end","op":"turn_settled"}""").timing)
        assertEquals(Timing.After(Anchor.PTT_DOWN, 0), one("""{"after":"ptt_down","op":"turn_settled"}""").timing)
    }

    @Test fun allOps() {
        assertEquals(Op.AwaitPtt("hi", 800), one("""{"op":"await_ptt","user_text":"hi","hold_ms":800}""").op)
        assertEquals(Op.Admission(600), one("""{"op":"admission","ms":600}""").op)
        assertEquals(Op.Think(1800), one("""{"op":"think","ms":1800}""").op)
        assertEquals(Op.TurnStarted, one("""{"op":"turn_started"}""").op)
        assertEquals(Op.TurnSettled, one("""{"op":"turn_settled"}""").op)
        assertEquals(Op.Speak("hello", 2000), one("""{"op":"speak","text":"hello","ms":2000}""").op)
        assertEquals(Op.Speak("hello", null), one("""{"op":"speak","text":"hello"}""").op)
        val c = one("""{"op":"command","name":"display.card","params":{"title":"Hi"}}""").op as Op.Command
        assertEquals("display.card", c.name); assertEquals("""{"title":"Hi"}""", c.paramsJson)
        assertEquals(Op.Transcript(true, "x", true), one("""{"op":"transcript","role":"user","text":"x"}""").op)
        assertEquals(Op.Transcript(false, "y", false), one("""{"op":"transcript","role":"muse","text":"y","final":false}""").op)
        assertEquals(Op.Fault(SimFault.OFFLINE, 8000), one("""{"op":"fault","kind":"offline","ms":8000}""").op)
        assertEquals(Op.Fault(SimFault.SEND_FAIL, null), one("""{"op":"fault","kind":"send_fail"}""").op)
        assertEquals(Op.RemoteStop, one("""{"op":"remote_stop"}""").op)
        assertEquals(Op.CallTakeover, one("""{"op":"call_takeover"}""").op)
        assertEquals(Op.Wait(500), one("""{"op":"wait","ms":500}""").op)
        assertEquals(Op.Loop(0, null), one("""{"op":"loop"}""").op)
        assertEquals(Op.Repair, one("""{"op":"repair"}""").op)
        assertEquals(Op.Ptt(true), one("""{"op":"ptt","value":"down"}""").op)
        assertEquals(Op.Ptt(false), one("""{"op":"ptt","value":"up"}""").op)
    }

    @Test fun loopFields() {
        val s = ok("""{"id":"x","steps":[{"op":"turn_started"},{"op":"loop","to":0,"count":3}]}""")
        assertEquals(Op.Loop(0, 3), s.steps[1].op)
    }

    @Test fun expectFields() {
        val e = (one("""{"op":"expect","panel_count":2,"orb":"Standby","call":"ready","link":"online","panel_ids":["a","b"],"within_ms":500}""").op as Op.Expect).spec
        assertEquals(ExpectSpec(2, "standby", "ready", "online", listOf("a", "b"), 500), e)
    }

    @Test fun errors() {
        assertTrue(err("not json").startsWith("invalid JSON"))
        assertTrue(err("[]").contains("object"))
        assertTrue(err("""{"steps":[]}""").contains("id"))
        assertTrue(err("""{"id":"x"}""").contains("steps"))
        assertTrue(err("""{"id":"x","start":"later","steps":[]}""").contains("start"))
        assertTrue(err("""{"id":"x","steps":[{"op":"bogus"}]}""").contains("step 0"))
        assertTrue(err("""{"id":"x","steps":[{"op":"speak"}]}""").contains("text"))
        assertTrue(err("""{"id":"x","steps":[{"at":"soon","op":"turn_started"}]}""").contains("at"))
        assertTrue(err("""{"id":"x","steps":[{"after":"never","op":"turn_started"}]}""").contains("anchor"))
        assertTrue(err("""{"id":"x","steps":[{"after":"ptt_up","at":500,"op":"turn_started"}]}""").contains("+N"))
        assertTrue(err("""{"id":"x","steps":[{"op":"fault","kind":"meteor"}]}""").contains("kind"))
        assertTrue(err("""{"id":"x","steps":[{"op":"loop","to":5}]}""").contains("range"))
        assertTrue(err("""{"id":"x","steps":[{"op":"expect","call":"maybe"}]}""").contains("call"))
        assertTrue(err("""{"id":"x","steps":[{"op":"ptt","value":"sideways"}]}""").contains("value"))
        assertTrue(err("""{"id":"x","steps":[{"op":"command","name":"display.card","params":[1]}]}""").contains("params"))
        assertTrue(err("""{"id":"x","steps":[{"op":"wait","ms":-5}]}""").contains("ms"))
    }
}
