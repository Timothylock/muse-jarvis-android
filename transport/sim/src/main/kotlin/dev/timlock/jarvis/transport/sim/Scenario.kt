package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.SimFault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Event anchors a step can wait for (§7.4 `after`). */
enum class Anchor(val wire: String) {
    PTT_DOWN("ptt_down"), PTT_UP("ptt_up"), SPEECH_START("speech_start"), SPEECH_END("speech_end");
    companion object { fun parse(s: String): Anchor? = entries.firstOrNull { it.wire == s } }
}

/** When a step runs. See [ScenarioEngine] for the exact semantics. */
sealed interface Timing {
    /** Milliseconds from scenario start (or loop iteration start). */
    data class Abs(val ms: Long) : Timing
    /** Milliseconds after the previous step's scheduled time (also the default: Rel(0)). */
    data class Rel(val ms: Long) : Timing
    /** [offsetMs] after the next occurrence of [anchor] (an occurrence that already happened counts). */
    data class After(val anchor: Anchor, val offsetMs: Long) : Timing
}

data class ExpectSpec(
    val panelCount: Int? = null,
    val orb: String? = null,
    val call: String? = null,
    val link: String? = null,
    val panelIds: List<String>? = null,
    /** Poll for up to this many REAL ms (not scaled) before failing; absorbs store timers such as the 200 ms settle. */
    val withinMs: Long = 0,
)

sealed interface Op {
    data class AwaitPtt(val userText: String?, val holdMs: Long?) : Op
    data class Admission(val ms: Long) : Op
    data class Think(val ms: Long) : Op
    data object TurnStarted : Op
    data object TurnSettled : Op
    data class Speak(val text: String, val ms: Long?) : Op
    data class Command(val name: String, val paramsJson: String) : Op
    data class Transcript(val user: Boolean, val text: String, val final: Boolean) : Op
    data class Fault(val kind: SimFault, val ms: Long?) : Op
    data object RemoteStop : Op
    data object CallTakeover : Op
    data class Wait(val ms: Long) : Op
    data class Loop(val to: Int, val count: Int?) : Op
    data object Repair : Op
    data class Ptt(val down: Boolean) : Op
    data class Expect(val spec: ExpectSpec) : Op

    /** Short label for the event log. */
    val label: String get() = this::class.simpleName?.lowercase() ?: "op"
}

data class Step(val timing: Timing, val op: Op)

data class Scenario(
    val id: String,
    val dp: Int,
    val title: String,
    /** true = "idle" (hang up + clear panels before step 0); false = "keep". */
    val startIdle: Boolean,
    val autoPttMs: Long?,
    val holdMs: Long,
    val admitMs: Long?,
    val thinkMs: Long?,
    val note: String?,
    val steps: List<Step>,
)

sealed interface ParseResult {
    data class Ok(val scenario: Scenario) : ParseResult
    data class Error(val message: String) : ParseResult
}

private class ParseError(msg: String) : Exception(msg, null, false, false)

/** Pure JSON -> [Scenario] parser (§7.4). Any problem rejects the whole scenario with a readable message. */
object ScenarioParser {
    const val DEFAULT_HOLD_MS = 1500L

    fun parse(json: String): ParseResult = try {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw ParseError("top level must be an object")
        ParseResult.Ok(scenario(root))
    } catch (e: ParseError) {
        ParseResult.Error(e.message ?: "parse error")
    } catch (e: Exception) {
        ParseResult.Error("invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
    }

    private fun scenario(o: JsonObject): Scenario {
        val id = str(o, "id", required = true)!!
        if (!Regex("[A-Za-z0-9._-]{1,60}").matches(id)) throw ParseError("id: invalid '$id'")
        val start = str(o, "start") ?: "idle"
        if (start != "idle" && start != "keep") throw ParseError("start: must be idle|keep")
        val stepsEl = o["steps"] as? JsonArray ?: throw ParseError("steps: required array")
        val steps = stepsEl.mapIndexed { i, el -> step(i, el) }
        steps.forEachIndexed { i, s ->
            val op = s.op
            if (op is Op.Loop && op.to !in 0 until steps.size) throw ParseError("step $i: loop.to out of range")
        }
        return Scenario(
            id = id, dp = long(o, "dp")?.toInt() ?: 1, title = str(o, "title") ?: id,
            startIdle = start == "idle", autoPttMs = long(o, "auto_ptt_ms"),
            holdMs = long(o, "hold_ms") ?: DEFAULT_HOLD_MS, admitMs = long(o, "admit_ms"), thinkMs = long(o, "think_ms"),
            note = str(o, "note"), steps = steps,
        )
    }

    private fun step(i: Int, el: JsonElement): Step {
        val o = el as? JsonObject ?: throw ParseError("step $i: must be an object")
        try {
            return Step(timing(o), op(o))
        } catch (e: ParseError) {
            throw ParseError("step $i: ${e.message}")
        }
    }

    private fun timing(o: JsonObject): Timing {
        val after = str(o, "after")?.let { Anchor.parse(it) ?: throw ParseError("after: unknown anchor '$it'") }
        val at = o["at"]
        if (after != null) {
            if (at == null) return Timing.After(after, 0)
            val s = (at as? JsonPrimitive)?.takeIf { it.isString }?.content
            val off = s?.let { plus(it) } ?: throw ParseError("at: with 'after' must be \"+N\"")
            return Timing.After(after, off)
        }
        if (at == null) return Timing.Rel(0)
        val p = at as? JsonPrimitive ?: throw ParseError("at: must be a number or \"+N\"")
        return if (p.isString) Timing.Rel(plus(p.content) ?: throw ParseError("at: bad value '${p.content}'"))
        else Timing.Abs(p.content.toDoubleOrNull()?.takeIf { it >= 0 }?.toLong() ?: throw ParseError("at: bad number"))
    }

    private fun plus(s: String): Long? = Regex("\\+(\\d+)").matchEntire(s.trim())?.groupValues?.get(1)?.toLongOrNull()

    private fun op(o: JsonObject): Op {
        val name = str(o, "op", required = true)!!
        return when (name) {
            "await_ptt" -> Op.AwaitPtt(str(o, "user_text"), long(o, "hold_ms"))
            "admission" -> Op.Admission(long(o, "ms", required = true)!!)
            "think" -> Op.Think(long(o, "ms") ?: 0)
            "turn_started" -> Op.TurnStarted
            "turn_settled" -> Op.TurnSettled
            "speak" -> Op.Speak(str(o, "text", required = true)!!, long(o, "ms"))
            "command" -> {
                val n = str(o, "name", required = true)!!
                val params = o["params"] ?: JsonObject(emptyMap())
                if (params !is JsonObject) throw ParseError("command.params must be an object")
                Op.Command(n, params.toString())
            }
            "transcript" -> {
                val role = str(o, "role", required = true)!!.lowercase()
                if (role != "user" && role != "muse") throw ParseError("transcript.role must be user|muse")
                Op.Transcript(role == "user", str(o, "text", required = true)!!, bool(o, "final") ?: true)
            }
            "fault" -> {
                val k = str(o, "kind", required = true)!!
                Op.Fault(SimFault.parse(k) ?: throw ParseError("fault.kind unknown '$k'"), long(o, "ms"))
            }
            "remote_stop" -> Op.RemoteStop
            "call_takeover" -> Op.CallTakeover
            "wait" -> Op.Wait(long(o, "ms", required = true)!!)
            "loop" -> Op.Loop(long(o, "to")?.toInt() ?: 0, long(o, "count")?.toInt()?.also { if (it < 0) throw ParseError("loop.count must be >= 0") })
            "repair" -> Op.Repair
            "ptt" -> Op.Ptt(
                when (str(o, "value", required = true)) { "down" -> true; "up" -> false; else -> throw ParseError("ptt.value must be down|up") },
            )
            "expect" -> Op.Expect(expect(o))
            else -> throw ParseError("unknown op '$name'")
        }
    }

    private fun expect(o: JsonObject): ExpectSpec {
        val call = str(o, "call")?.lowercase()
        if (call != null && call !in setOf("none", "admitting", "ready")) throw ParseError("expect.call must be none|admitting|ready")
        val link = str(o, "link")?.lowercase()
        if (link != null && link !in setOf("online", "offline", "unpaired", "pairing_lost")) throw ParseError("expect.link invalid '$link'")
        val ids = (o["panel_ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ParseError("expect.panel_ids must be strings") }
        if (o["panel_ids"] != null && ids == null) throw ParseError("expect.panel_ids must be an array")
        return ExpectSpec(long(o, "panel_count")?.toInt(), str(o, "orb")?.lowercase(), call, link, ids, long(o, "within_ms") ?: 0)
    }

    // ---- field helpers --------------------------------------------------------------------

    private fun str(o: JsonObject, k: String, required: Boolean = false): String? {
        val el = o[k]
        if (el == null) { if (required) throw ParseError("$k: required"); return null }
        val p = el as? JsonPrimitive
        if (p == null || !p.isString) throw ParseError("$k: must be a string")
        return p.content
    }

    private fun long(o: JsonObject, k: String, required: Boolean = false): Long? {
        val el = o[k]
        if (el == null) { if (required) throw ParseError("$k: required"); return null }
        val p = el as? JsonPrimitive
        val v = p?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        if (v == null || v < 0) throw ParseError("$k: must be a non-negative number")
        return v.toLong()
    }

    private fun bool(o: JsonObject, k: String): Boolean? {
        val el = o[k] ?: return null
        val p = el as? JsonPrimitive
        return when (p?.takeIf { !it.isString }?.content) { "true" -> true; "false" -> false; else -> throw ParseError("$k: must be boolean") }
    }
}
