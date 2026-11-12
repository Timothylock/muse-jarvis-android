package dev.timlock.jarvis.transport.sdk

import android.content.Context
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.secure.CredentialStore
import dev.timlock.jarvis.core.transport.CallHandle
import dev.timlock.jarvis.core.transport.FallbackTurn
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.transport.ImportResult
import dev.timlock.jarvis.core.transport.PairOutcome
import dev.timlock.jarvis.core.transport.PairingBundle
import dev.timlock.jarvis.core.transport.PairingUiSink
import dev.timlock.jarvis.core.transport.SdkToken
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * M6 CONTRACT STUB — track S replaces the body; the constructor and public members are fixed.
 * Real transport: the upstream musegadget SDK embedded with Chaquopy (§6.5). The ONLY code that knows Python exists.
 */
class SdkTransport(
    private val context: Context,
    private val store: CredentialStore,
    private val scope: CoroutineScope,
) : GadgetTransport {
    override val kind = TransportKind.REAL
    private val _link = MutableStateFlow<LinkState>(LinkState.Unpaired)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()
    private val _diag = MutableStateFlow(SdkDiagnostics())
    /** Read-only diagnostics for `cmd diag` and the perf HUD (never contains secrets or server text). */
    val diagnostics: StateFlow<SdkDiagnostics> = _diag.asStateFlow()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun pair(token: SdkToken, ui: PairingUiSink) = PairOutcome.BLE_ERROR // M7
    /** Validates + stores via [dev.timlock.jarvis.core.secure.PairingImport], then restarts the service. */
    override suspend fun importPairing(bundle: PairingBundle) = ImportResult(false, "stub")
    /** Deletes pairing.json only (identity is kept, §4.4), then restarts the service loop. */
    override suspend fun unpair() = Unit
    override suspend fun openCall(threadId: String?): CallHandle = throw UnsupportedOperationException("stub")
    override suspend fun sendDictatedTurn(pcm: ShortArray, sessionId: UUID) = FallbackTurn(null, null, "stub")
    override fun replyCommand(invocationId: String, result: CommandResult) = Unit
}

/** Snapshot for diagnostics. All fields are non-secret. */
data class SdkDiagnostics(
    val pythonStarted: Boolean = false,
    val pythonStartMs: Long = -1,
    val sdkVersion: String? = null,
    val sdkCommit: String? = null,
    val nodeId: String? = null,
    val paired: Boolean = false,
    val tokenHint: String? = null,
    val tlsRoots: Int = -1,
    /** e.g. "ok 200", "ok 404", "fail SSLError" — unauthenticated HTTPS reachability of the API host. */
    val httpsProbe: String? = null,
    val commandCount: Int = 0,
    val describeChars: Int = 0,
    /** Result of the secure-storage hook self-test (write/read/delete of a scratch name through Python). */
    val storageRoundTrip: Boolean? = null,
    /** Last service-loop outcome name (Outcome.value) or bridge state, e.g. "unpaired_poll", "closed". */
    val lastOutcome: String? = null,
    val restarts: Int = 0,
    val callPhase: String? = null,
    val advisoryErrors: Int = 0,
)
