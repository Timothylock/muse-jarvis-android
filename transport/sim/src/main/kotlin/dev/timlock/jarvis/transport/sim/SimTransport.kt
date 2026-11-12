package dev.timlock.jarvis.transport.sim

import android.util.Log
import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.LinkState
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
        _link.value = LinkState.Online("sim-node-0001", "Simulated VM")
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

    override suspend fun openCall(threadId: String?): CallHandle = SimCall(scope)

    override suspend fun sendDictatedTurn(pcm: ShortArray, sessionId: UUID): FallbackTurn {
        // TODO(M5): scripted reply text + babble PCM.
        return FallbackTurn(replyText = "Simulated reply.")
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

class SimCall(scope: CoroutineScope) : CallHandle {
    private val _state = MutableStateFlow<CallPhase>(CallPhase.Admitting)
    override val state: StateFlow<CallPhase> = _state.asStateFlow()

    // TODO(M5): consume mic frames, drive SimVoice into the sink.
    @Volatile var mic: PcmSource? = null
        private set
    @Volatile var speaker: PcmSink? = null
        private set

    init {
        scope.launch {
            delay(600)
            if (_state.value == CallPhase.Admitting) _state.value = CallPhase.Ready
        }
    }

    override fun attachMic(source: PcmSource) { mic = source }
    override fun attachSpeaker(sink: PcmSink) { speaker = sink }
    override suspend fun bargeIn() { /* TODO(M5) */ }
    override suspend fun hangUp() { _state.value = CallPhase.Ended("hangup") }
}
