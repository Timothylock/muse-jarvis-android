package dev.timlock.jarvis

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
import dev.timlock.jarvis.core.transport.SdkToken
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** M1 stand-in for the real transport: always unpaired. TODO(M6): replace with SdkTransport. */
class UnpairedRealStub : GadgetTransport {
    override val kind = TransportKind.REAL
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Unpaired)
    override val events: SharedFlow<TransportEvent> = MutableSharedFlow<TransportEvent>().asSharedFlow()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun pair(token: SdkToken, ui: PairingUiSink) = PairOutcome.BLE_ERROR
    override suspend fun importPairing(bundle: PairingBundle) = ImportResult(false, "not available in M1")
    override suspend fun unpair() = Unit
    override suspend fun openCall(threadId: String?): CallHandle = throw UnsupportedOperationException("unpaired")
    override suspend fun sendDictatedTurn(pcm: ShortArray, sessionId: UUID) = FallbackTurn(null, null, "unpaired")
    override fun replyCommand(invocationId: String, result: CommandResult) = Unit
}
