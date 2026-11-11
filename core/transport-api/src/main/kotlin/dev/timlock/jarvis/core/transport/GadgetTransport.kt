package dev.timlock.jarvis.core.transport

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.TransportKind
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface GadgetTransport {
    /** REAL or SIM; the UI uses this for sim styling. */
    val kind: TransportKind
    val link: StateFlow<LinkState>
    val events: SharedFlow<TransportEvent>

    suspend fun start()
    suspend fun stop()

    // pairing
    suspend fun pair(token: SdkToken, ui: PairingUiSink): PairOutcome
    suspend fun importPairing(bundle: PairingBundle): ImportResult
    suspend fun unpair()

    // conversation
    suspend fun openCall(threadId: String?): CallHandle
    suspend fun sendDictatedTurn(pcm: ShortArray, sessionId: UUID): FallbackTurn

    /** Display commands arrive as events; replies go back via this. */
    fun replyCommand(invocationId: String, result: CommandResult)
}

interface CallHandle {
    val state: StateFlow<CallPhase>

    /** 24 kHz mono PCM16, [PcmFormat.FRAME_SAMPLES]-sample frames. */
    fun attachMic(source: PcmSource)

    /** write(pcm)/flush(), nonblocking. */
    fun attachSpeaker(sink: PcmSink)
    suspend fun bargeIn()
    suspend fun hangUp()
}
