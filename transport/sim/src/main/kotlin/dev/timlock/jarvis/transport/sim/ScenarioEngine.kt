package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure view of the store for `expect` (strings are lowercase wire names). */
data class SimSnapshot(
    val orb: String,
    val panelCount: Int,
    val call: String,
    val link: String,
    val panelIds: List<String>,
)

/** Everything the engine needs from the outside world; the app implements it over the store + SimTransport. */
interface ScenarioHost {
    /** Monotonic millis (virtual time in tests). */
    fun nowMs(): Long
    fun pttDown()
    fun pttUp()
    fun snapshot(): SimSnapshot
    fun emit(event: TransportEvent)
    /** Inject through validation; null = no reply (timeout / no transport). */
    suspend fun command(name: String, paramsJson: String): CommandResult?
    /** Non-blocking speech on the active call; false = no call open. [ms] is already time-scaled. */
    fun speak(text: String, ms: Long): Boolean
    /** [ms] is NOT scaled here; the transport scales it. Returns an error or null. */
    fun fault(kind: SimFault, ms: Long?): String?
    /** Unscaled; the transport applies the time scale when the call opens. */
    fun setAdmission(ms: Long)
    fun remoteStop(): String?
    fun takeover(): String?
    fun repair(): String?
    /** `start: "idle"`: hang up any call, clear panels, reset faults. */
    suspend fun prepareStart()
    fun log(line: String)
    fun onProgress(step: Int, total: Int, asserts: Int, failed: Int)
    fun onDone(asserts: Int, failed: Int)
}

/** Counts of anchor events (derived from store transitions by the app), with the time of the latest one. */
class AnchorTracker(private val clock: () -> Long) {
    private val counts = Anchor.entries.associateWith { MutableStateFlow(0L) }
    private val times = LongArray(Anchor.entries.size)

    fun fire(a: Anchor) {
        times[a.ordinal] = clock()
        counts.getValue(a).value++
    }

    fun count(a: Anchor): Long = counts.getValue(a).value
    fun timeOf(a: Anchor): Long = times[a.ordinal]
    suspend fun awaitCountAbove(a: Anchor, seen: Long): Long = counts.getValue(a).first { it > seen }
}

internal fun jsonLine(vararg kv: Pair<String, Any?>): String = JsonObject(
    kv.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
).toString()

/**
 * Executes one [Scenario] timeline (§7.4). Pure logic over [ScenarioHost]; runs on [scope].
 *
 * Timing rules (all durations are divided by [timescale]; "scheduled time" is ms since the run/loop-iteration
 * epoch, measured on a clock that stops while paused):
 *  - `at: N`      -> scheduled at N (absolute).
 *  - `at: "+N"`   -> previous step's scheduled time + N. A step with neither `at` nor `after` is "+0".
 *  - `after: A`   -> scheduled at (time of the next occurrence of anchor A) + N where `at:"+N"` is optional.
 *                    An occurrence that already happened since the last step that consumed anchor A (or since the
 *                    run started) is used immediately, so a missed event never deadlocks.
 *  - A step whose scheduled time is already past runs immediately; scheduled times never go backwards.
 *  - Blocking ops (`await_ptt`, `wait`) set the next step's reference time to when they actually completed.
 */
class ScenarioEngine(
    scope: CoroutineScope,
    private val host: ScenarioHost,
    private val anchors: AnchorTracker,
    timescale: Float = 1f,
    private val forcedAutoPttMs: Long? = null,
) {
    val timescale: Float = timescale.coerceIn(0.25f, 4f)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val sc = scope + job

    @Volatile var asserts = 0; private set
    @Volatile var failed = 0; private set

    private var epoch = 0L
    private var pausedAccum = 0L
    private var pauseStart = -1L
    private val consumed = HashMap<Anchor, Long>()
    private var turnSeq = 0
    private var total = 0
    @Volatile private var pttHeldByEngine = false

    private fun scale(ms: Long): Long = (ms / timescale).toLong()
    private fun elapsed(): Long = (if (pauseStart >= 0) pauseStart else host.nowMs()) - epoch - pausedAccum
    private fun rebase() { epoch = host.nowMs(); pausedAccum = 0; if (pauseStart >= 0) pauseStart = epoch }

    fun pause(paused: Boolean) {
        if (paused && pauseStart < 0) pauseStart = host.nowMs()
        else if (!paused && pauseStart >= 0) { pausedAccum += host.nowMs() - pauseStart; pauseStart = -1 }
    }

    /** Cancels the run (no scenario_done line). Releases a PTT the engine is holding. */
    fun stop() {
        job.cancel()
        if (pttHeldByEngine) { pttHeldByEngine = false; host.pttUp() }
    }

    fun start(sc0: Scenario): Job = sc.launch {
        try {
            run(sc0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log(jsonLine("cmd" to "scenario_error", "id" to sc0.id, "error" to (e.message ?: e::class.simpleName)))
        }
        host.log(jsonLine("cmd" to "scenario_done", "id" to sc0.id, "asserts" to asserts, "failed" to failed))
        host.onDone(asserts, failed)
    }

    private suspend fun run(s: Scenario) {
        if (s.startIdle) host.prepareStart()
        s.admitMs?.let { host.setAdmission(it) }
        for (a in Anchor.entries) consumed[a] = anchors.count(a)
        rebase()
        val autoPtt = forcedAutoPttMs ?: s.autoPttMs
        total = s.steps.size
        val loops = HashMap<Int, Int>()
        var prev = 0L
        var i = 0
        while (i < s.steps.size) {
            val step = s.steps[i]
            host.onProgress(i + 1, s.steps.size, asserts, failed)
            val op = step.op
            var target = when (val t = step.timing) {
                is Timing.Abs -> scale(t.ms)
                is Timing.Rel -> prev + scale(t.ms)
                is Timing.After -> awaitAnchor(t.anchor) + scale(t.offsetMs)
            }
            target = maxOf(prev, target)
            sleepUntil(target)
            prev = target
            host.log(jsonLine("cmd" to "step", "scenario" to s.id, "step" to i, "op" to op.label))
            var next = i + 1
            when (op) {
                is Op.AwaitPtt -> { awaitPtt(op, s, autoPtt); prev = maxOf(prev, elapsed()) }
                is Op.Admission -> host.setAdmission(op.ms)
                is Op.Think -> host.log(jsonLine("cmd" to "think", "ms" to op.ms))
                Op.TurnStarted -> host.emit(TransportEvent.TurnStarted("sim-turn-${++turnSeq}", TransportKind.SIM))
                Op.TurnSettled -> host.emit(TransportEvent.TurnSettled("sim-turn-$turnSeq", TransportKind.SIM))
                is Op.Speak -> {
                    val ms = op.ms ?: (op.text.length * 1000L / 14)
                    if (host.speak(op.text, scale(ms))) host.log(jsonLine("cmd" to "speak", "ms" to ms))
                    else host.log(jsonLine("cmd" to "speak", "warn" to "no open call, skipped"))
                }
                is Op.Command -> {
                    val r = host.command(op.name, op.paramsJson)
                    host.log(jsonLine("cmd" to "command", "scenario" to s.id, "step" to i, "name" to op.name,
                        "ok" to (r?.ok ?: false), "error" to (if (r == null) "no reply" else r.error)))
                }
                is Op.Transcript -> host.emit(TransportEvent.Transcript(if (op.user) Role.USER else Role.MUSE, op.text, op.final, TransportKind.SIM))
                is Op.Fault -> host.fault(op.kind, op.ms)?.let { host.log(jsonLine("cmd" to "fault", "ok" to false, "error" to it)) }
                Op.RemoteStop -> host.remoteStop()?.let { host.log(jsonLine("cmd" to "remote_stop", "ok" to false, "error" to it)) }
                Op.CallTakeover -> host.takeover()?.let { host.log(jsonLine("cmd" to "call_takeover", "ok" to false, "error" to it)) }
                Op.Repair -> host.repair()?.let { host.log(jsonLine("cmd" to "repair", "ok" to false, "error" to it)) }
                is Op.Wait -> { sleepUntil(elapsed() + scale(op.ms)); prev = maxOf(prev, elapsed()) }
                is Op.Loop -> {
                    val done = loops[i] ?: 0
                    if (op.count == null || done < op.count) {
                        loops[i] = done + 1
                        delay(10)
                        rebase()
                        prev = 0
                        next = op.to
                    }
                }
                is Op.Ptt -> pttOp(op, autoPtt)
                is Op.Expect -> expect(op.spec, s.id, i)
            }
            i = next
        }
    }

    private suspend fun awaitAnchor(a: Anchor): Long {
        val c = anchors.awaitCountAbove(a, consumed.getValue(a))
        consumed[a] = c
        return (anchors.timeOf(a) - epoch - pausedAccum).coerceAtLeast(0)
    }

    private suspend fun sleepUntil(target: Long) {
        while (true) {
            val d = target - elapsed()
            if (d <= 0) return
            delay(minOf(d, 50))
        }
    }

    private suspend fun awaitPtt(op: Op.AwaitPtt, s: Scenario, autoPtt: Long?) {
        val upBaseline = anchors.count(Anchor.PTT_UP)
        if (autoPtt != null) {
            sleepUntil(elapsed() + scale(autoPtt))
            val downBaseline = anchors.count(Anchor.PTT_DOWN)
            host.pttDown()
            pttHeldByEngine = true
            withTimeoutOrNull(1000) { anchors.awaitCountAbove(Anchor.PTT_DOWN, downBaseline) }
            consumed[Anchor.PTT_DOWN] = anchors.count(Anchor.PTT_DOWN)
            val hold = scale(op.holdMs ?: s.holdMs)
            sc.launch {
                sleepUntil(elapsed() + hold)
                if (pttHeldByEngine) { pttHeldByEngine = false; host.pttUp() }
            }
        } else {
            host.log(jsonLine("cmd" to "await_ptt", "hint" to "press PTT"))
            awaitAnchor(Anchor.PTT_DOWN)
        }
        op.userText?.let { text ->
            sc.launch {
                anchors.awaitCountAbove(Anchor.PTT_UP, upBaseline)
                host.emit(TransportEvent.Transcript(Role.USER, text, true, TransportKind.SIM))
            }
        }
    }

    private fun pttOp(op: Op.Ptt, autoPtt: Long?) {
        if (autoPtt == null) {
            host.log(jsonLine("cmd" to "ptt", "hint" to (if (op.down) "PRESS PTT NOW" else "RELEASE PTT")))
            return
        }
        if (op.down) { pttHeldByEngine = true; host.pttDown() } else { pttHeldByEngine = false; host.pttUp() }
    }

    private suspend fun expect(e: ExpectSpec, id: String, step: Int) {
        val deadline = host.nowMs() + e.withinMs
        var err: String?
        while (true) {
            err = check(e, host.snapshot())
            if (err == null || host.nowMs() >= deadline) break
            delay(25)
        }
        asserts++
        if (err != null) failed++
        host.log(
            if (err == null) jsonLine("cmd" to "expect", "scenario" to id, "step" to step, "ok" to true)
            else jsonLine("cmd" to "expect", "scenario" to id, "step" to step, "ok" to false, "error" to err),
        )
        host.onProgress(step + 1, total, asserts, failed)
    }

    companion object {
        /** null = pass. */
        fun check(e: ExpectSpec, s: SimSnapshot): String? {
            val errs = ArrayList<String>()
            e.orb?.let { if (!it.equals(s.orb, true)) errs += "SIM_ASSERT_FAIL orb expected $it got ${s.orb}" }
            e.panelCount?.let { if (it != s.panelCount) errs += "SIM_ASSERT_FAIL panel_count expected $it got ${s.panelCount}" }
            e.call?.let { if (it != s.call) errs += "SIM_ASSERT_FAIL call expected $it got ${s.call}" }
            e.link?.let { if (it != s.link) errs += "SIM_ASSERT_FAIL link expected $it got ${s.link}" }
            e.panelIds?.let { if (it.toSet() != s.panelIds.toSet()) errs += "SIM_ASSERT_FAIL panel_ids expected $it got ${s.panelIds}" }
            return if (errs.isEmpty()) null else errs.joinToString("; ")
        }
    }
}
