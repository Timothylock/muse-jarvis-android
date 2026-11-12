package dev.timlock.jarvis.transport.sim

import android.util.Log
import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.store.REASON_SESSION_DEAD
import dev.timlock.jarvis.core.transport.PcmFormat
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.transport.CallHandle
import dev.timlock.jarvis.core.transport.FallbackTurn
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.transport.ImportResult
import dev.timlock.jarvis.core.transport.PairOutcome
import dev.timlock.jarvis.core.transport.PairingBundle
import dev.timlock.jarvis.core.transport.PairingUiSink
import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSource
import dev.timlock.jarvis.core.transport.SdkToken
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "JARVIS_SIM"
private const val NODE_ID = "sim-node-0001"
private const val VM_NAME = "Simulated VM"
private const val READY_TIMEOUT_MS = 35_000L
private const val SIM_FALLBACK_LATENCY_MS = 700L
private const val SIM_FALLBACK_REPLY_MS = 2_000

/** Simulated transport (§7.1). M1 skeleton: link, fake pairing and a call that becomes Ready. */
class SimTransport : GadgetTransport {
    override val kind = TransportKind.SIM

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    override suspend fun start() {
        _link.value = LinkState.Connecting
        delay(300)
        _link.value = LinkState.Online(NODE_ID, VM_NAME)
        Log.i(TAG, "sim link online")
    }

    override suspend fun stop() {
        _link.value = LinkState.Offline("stopped")
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    override suspend fun pair(token: SdkToken, ui: PairingUiSink): PairOutcome {
        // TODO(M7): configurable duration / instant option and fake BLE progress stages.
        for (i in 1..4) {
            ui.onProgress("sim-step-$i", i / 4f)
            ui.onWindowRemaining(180 - i * 30)
            delay(500)
        }
        return PairOutcome.PAIRED
    }

    override suspend fun importPairing(bundle: PairingBundle): ImportResult = ImportResult(ok = true)

    override suspend fun unpair() {
        Log.i(TAG, "sim unpair (no-op)")
    }

    // ---- sim controls (M4) ----------------------------------------------------------------

    /** Admission latency for the next call(s), unscaled ms (divided by [timescale] when the call opens). */
    @Volatile var admitMs: Long = 800
    /** Time scale applied to every sim-side duration this transport times itself (0.25-4). */
    @Volatile var timescale: Float = 1f
    /** Receives one JSON line per notable sim action (the controller mirrors them into its event log). */
    @Volatile internal var logSink: ((String) -> Unit)? = null

    @Volatile internal var currentCall: SimCall? = null
        private set
    private val sendFailArmed = AtomicBoolean(false)
    private val admitTimeoutArmed = AtomicBoolean(false)
    private var linkRestoreJob: Job? = null
    private val callSeq = AtomicInteger(0)

    private fun scaled(ms: Long): Long = (ms / timescale.coerceIn(0.25f, 4f)).toLong()
    private fun note(line: String) { Log.i(TAG, line); logSink?.invoke(line) }

    internal fun setLink(l: LinkState) { _link.value = l }
    internal fun emit(e: TransportEvent) { _events.tryEmit(e) }

    /** The open (not ended) call, if any. */
    internal fun activeCall(): SimCall? = currentCall?.takeIf { it.state.value !is CallPhase.Ended }

    override suspend fun openCall(threadId: String?): CallHandle {
        activeCall()?.end("replaced")
        val never = admitTimeoutArmed.getAndSet(false)
        val call = SimCall(scope, scaled(admitMs), never, scaled(READY_TIMEOUT_MS), callSeq.incrementAndGet())
        currentCall = call
        note("{\"cmd\":\"call_open\",\"admit_ms\":${scaled(admitMs)},\"never_ready\":$never}")
        return call
    }

    /** Synthesizes babble for [ms] (already scaled) into the active call's speaker; false when no call is open. */
    internal fun speak(text: String, ms: Long): Boolean {
        val call = activeCall() ?: return false
        return call.speak(ms) { emit(TransportEvent.Transcript(Role.MUSE, text, true, TransportKind.SIM)) }
    }

    /** §7.3 Faults. [ms] (OFFLINE only, unscaled): link returns Online after ms / timescale. Returns an error or null. */
    internal fun applyFault(kind: SimFault, ms: Long?): String? {
        note("{\"cmd\":\"fault\",\"kind\":\"${kind.wire}\"${if (ms != null) ",\"ms\":$ms" else ""}}")
        when (kind) {
            SimFault.OFFLINE -> {
                linkRestoreJob?.cancel()
                setLink(LinkState.Offline("sim_fault"))
                scope.launch { delay(300); activeCall()?.abandon() }
                if (ms != null) linkRestoreJob = scope.launch {
                    delay(scaled(ms))
                    if (_link.value is LinkState.Offline) setLink(LinkState.Online(NODE_ID, VM_NAME))
                }
            }
            SimFault.REVOKE -> {
                linkRestoreJob?.cancel()
                setLink(LinkState.PairingLost("revoked"))
                scope.launch { delay(300); activeCall()?.abandon() }
            }
            SimFault.SEND_FAIL -> sendFailArmed.set(true)
            SimFault.ADMIT_TIMEOUT -> admitTimeoutArmed.set(true)
            SimFault.SESSION_DEAD -> (activeCall() ?: return "no active call").end(REASON_SESSION_DEAD)
        }
        return null
    }

    /** Stands in for the re-pair flow after `revoke`: link back Online. */
    internal fun repair() {
        linkRestoreJob?.cancel()
        setLink(LinkState.Online(NODE_ID, VM_NAME))
        note("{\"cmd\":\"repair\"}")
    }

    /** `remote_stop`: the far end ends the call. */
    internal fun remoteStop(): String? {
        val c = activeCall() ?: return "no active call"
        return c.end("remote_stop")
    }
    /** `call_takeover`: another device took the call (the store keys on the "takeover" marker). */
    internal fun takeover(): String? {
        val c = activeCall() ?: return "no active call"
        return c.end("takeover")
    }

    /** Disarm one-shot faults, restore the link, end any call (start: "idle"). */
    internal fun resetFaults() {
        sendFailArmed.set(false)
        admitTimeoutArmed.set(false)
        linkRestoreJob?.cancel()
        if (_link.value !is LinkState.Online && _link.value !is LinkState.Connecting) setLink(LinkState.Online(NODE_ID, VM_NAME))
    }

    private val fallbackSeq = AtomicInteger(0)

    /**
     * Fallback voice path (§4.3.1): a canned reply plus ~2 s of babble PCM after a short simulated round trip.
     * The armed SEND_FAIL fault fails exactly one turn with error "send_failed".
     */
    override suspend fun sendDictatedTurn(pcm: ShortArray, sessionId: UUID): FallbackTurn {
        delay(scaled(SIM_FALLBACK_LATENCY_MS))
        if (sendFailArmed.getAndSet(false)) {
            note("{\"cmd\":\"fallback_turn\",\"ok\":false}")
            return FallbackTurn(replyText = null, error = "send_failed")
        }
        val n = fallbackSeq.incrementAndGet()
        val voice = SimVoice(0xFA11L + n)
        val frames = SIM_FALLBACK_REPLY_MS / 40
        val out = ShortArray(frames * PcmFormat.FRAME_SAMPLES)
        val frame = ShortArray(PcmFormat.FRAME_SAMPLES)
        for (f in 0 until frames) {
            voice.fill(frame)
            frame.copyInto(out, f * PcmFormat.FRAME_SAMPLES)
        }
        note("{\"cmd\":\"fallback_turn\",\"ok\":true,\"samples\":${out.size}}")
        return FallbackTurn(replyText = "Simulated reply over the text path.", replyPcm = out)
    }

    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<CommandResult>>()
    private val commandSeq = AtomicInteger(0)

    /** ADB display injection (§7.7): emits a Command event and awaits the store's reply (1.5 s timeout -> null). */
    suspend fun injectCommand(name: String, paramsJson: String): CommandResult? {
        val id = "sim-${commandSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<CommandResult>()
        pendingCommands[id] = deferred
        try {
            _events.emit(TransportEvent.Command(id, name, paramsJson, TransportKind.SIM))
            return withTimeoutOrNull(1500) { deferred.await() }
        } finally {
            pendingCommands.remove(id)
        }
    }

    override fun replyCommand(invocationId: String, result: CommandResult) {
        Log.i(TAG, "{\"cmd\":\"reply\",\"id\":\"$invocationId\",\"ok\":${result.ok}}")
        pendingCommands[invocationId]?.complete(result)
    }
}

/**
 * Simulated call. Admitting -> Ready after [admitMs] (already scaled), or never Ready when [neverReady] (it ends
 * with Ended("ready_timeout") after [readyTimeoutMs]). Speech is babble written to the attached speaker.
 */
class SimCall internal constructor(
    private val scope: CoroutineScope,
    admitMs: Long,
    neverReady: Boolean,
    readyTimeoutMs: Long,
    private val seed: Int,
) : CallHandle {
    private val _state = MutableStateFlow<CallPhase>(CallPhase.Admitting)
    override val state: StateFlow<CallPhase> = _state.asStateFlow()

    @Volatile var mic: PcmSource? = null
        private set
    private val micJob = SupervisorJob(scope.coroutineContext[Job])
    @Volatile var speaker: PcmSink? = null
        private set

    private val speechJob = SupervisorJob(scope.coroutineContext[Job])
    private val speechScope = scope + speechJob
    private val speechLock = Mutex()
    private var speechCount = 0

    init {
        scope.launch {
            if (neverReady) {
                delay(readyTimeoutMs)
                end("ready_timeout")
            } else {
                delay(admitMs)
                _state.compareAndSet(CallPhase.Admitting, CallPhase.Ready)
            }
        }
    }

    /**
     * §7.1: the real mic records (for reactivity) but SimCall discards the audio. It must keep pulling so the
     * paced source runs; stops on end of stream or when the call ends.
     */
    override fun attachMic(source: PcmSource) {
        mic = source
        (scope + micJob).launch {
            while (isActive && _state.value !is CallPhase.Ended) {
                source.nextFrame() ?: break
            }
        }
    }
    override fun attachSpeaker(sink: PcmSink) { speaker = sink }

    /** Cancel speech synthesis and flush the speaker (§4.3 barge-in). */
    override suspend fun bargeIn() { abandon() }
    override suspend fun hangUp() { end("hangup") }

    /** Stops speech and flushes the speaker without ending the call. */
    internal fun abandon() {
        speechJob.cancelChildren()
        speaker?.flush()
    }

    /** Ends the call with [reason] (no-op if already ended). Always returns null (for `?: return` chaining). */
    internal fun end(reason: String): String? {
        val prev = _state.getAndUpdate { if (it is CallPhase.Ended) it else CallPhase.Ended(reason) }
        if (prev !is CallPhase.Ended) { abandon(); micJob.cancel() }
        return null
    }

    /**
     * Queue [ms] of babble (speeches play one after another). [onStart] runs when this speech's first frame is
     * written (waits for Ready first). Returns false if the call already ended.
     */
    internal fun speak(ms: Long, onStart: () -> Unit): Boolean {
        if (_state.value is CallPhase.Ended) return false
        val n = ++speechCount
        speechScope.launch {
            _state.first { it !is CallPhase.Admitting }
            if (_state.value is CallPhase.Ended) return@launch
            speechLock.withLock {
                onStart()
                synthesize(ms, seed * 7919L + n)
            }
        }
        return true
    }

    private suspend fun synthesize(ms: Long, voiceSeed: Long) {
        val voice = SimVoice(voiceSeed)
        val frame = ShortArray(PcmFormat.FRAME_SAMPLES)
        val frames = ((ms + FRAME_MS - 1) / FRAME_MS).toInt().coerceAtLeast(1)
        val t0 = System.nanoTime()
        for (f in 0 until frames) {
            val aheadMs = f * FRAME_MS - (System.nanoTime() - t0) / 1_000_000
            if (aheadMs > MAX_AHEAD_MS) delay(aheadMs - MAX_AHEAD_MS)
            voice.fill(frame)
            speaker?.write(frame.copyOf())
        }
        // Stay "speaking" until the last frame has played so queued speeches don't overlap.
        val remain = frames * FRAME_MS - (System.nanoTime() - t0) / 1_000_000
        if (remain > 0) delay(remain)
    }

    private companion object {
        const val FRAME_MS = 40L
        const val MAX_AHEAD_MS = 200L
    }
}
