package dev.timlock.jarvis.transport.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.Level
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.secure.CredentialNames
import dev.timlock.jarvis.core.secure.CredentialStore
import dev.timlock.jarvis.core.secure.PairingImport
import dev.timlock.jarvis.core.secure.Redactor
import dev.timlock.jarvis.core.transport.CallHandle
import dev.timlock.jarvis.core.transport.FallbackTurn
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.transport.ImportResult
import dev.timlock.jarvis.core.transport.PairOutcome
import dev.timlock.jarvis.core.transport.PairingBundle
import dev.timlock.jarvis.core.transport.PairingUiSink
import dev.timlock.jarvis.core.transport.SdkToken
import dev.timlock.jarvis.core.transport.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real transport: the upstream musegadget SDK (vendored, unmodified) embedded with Chaquopy (§6.5). The ONLY code
 * that knows Python exists. All Python-side behavior lives in jarvis_bridge.py; this class owns lifecycle, state
 * flows and the Kotlin callbacks ([SdkHost]).
 */
class SdkTransport(
    private val context: Context,
    internal val store: CredentialStore,
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

    private val app: Context = context.applicationContext
    private val host = SdkHost(this)
    private val startLock = Mutex()
    @Volatile private var bridge: PyObject? = null
    private val calls = ConcurrentHashMap<String, SdkCallHandle>()
    private val fallbacks = ConcurrentHashMap<String, CompletableDeferred<FallbackTurn>>()

    /** Set by [stop]; a transport is single-use, so a start racing a stop must not bring Python up again. */
    @Volatile private var stopped = false

    override suspend fun start() = startLock.withLock {
        if (bridge != null || stopped) return@withLock
        withContext(Dispatchers.IO) { startBlocking() }
    }

    private fun startBlocking() {
        val t0 = SystemClock.elapsedRealtime()
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(app))
            val pyMs = SystemClock.elapsedRealtime() - t0
            _diag.update { it.copy(pythonStarted = true, pythonStartMs = pyMs) }
            Log.i(TAG, "Python started in $pyMs ms")
            _link.value = if (store.exists(CredentialNames.PAIRING)) LinkState.Connecting else LinkState.Unpaired
            _diag.update { it.copy(paired = store.exists(CredentialNames.PAIRING)) }
            val module = Python.getInstance().getModule("jarvis_bridge")
            val stateDir = File(app.noBackupFilesDir, "sdk-state").apply { mkdirs() }
            module.callAttr("start", host, stateDir.absolutePath, versionName())
            bridge = module
            Log.i(TAG, "SDK imported + bridge started in ${SystemClock.elapsedRealtime() - t0} ms total")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "start failed: " + Redactor.redact(t.toString()).take(400))
            _events.tryEmit(TransportEvent.Diagnostic(Level.ERROR, "python_start_failed", kind))
            _link.value = LinkState.Offline("internal")
        }
    }

    override suspend fun stop() {
        stopped = true
        calls.values.toList().forEach { it.onPhase("ended", "transport_stopped") }
        calls.clear()
        // Wait out an in-flight start (Python start takes seconds): otherwise bridge is still null here and the
        // service loop keeps running after a switch to SIM, breaking §7.6 rule 7 (never both transports).
        // NonCancellable: the caller's cancellation must not skip stopping Python.
        withContext(NonCancellable) {
            startLock.withLock {
                val b = bridge ?: return@withLock
                bridge = null
                withContext(Dispatchers.IO) { runCatching { b.callAttr("stop") } }
            }
        }
        _link.value = LinkState.Offline("stopped")
    }

    override suspend fun pair(token: SdkToken, ui: PairingUiSink) = PairOutcome.BLE_ERROR // TODO(M7): BLE pairing

    /** Validates + stores via [PairingImport], then restarts the service loop. */
    override suspend fun importPairing(bundle: PairingBundle): ImportResult {
        val r = withContext(Dispatchers.IO) { PairingImport.apply(store, bundle.bytes) }
        return when (r) {
            is PairingImport.Result.Ok -> { kick(); ImportResult(true) }
            is PairingImport.Result.Invalid -> ImportResult(false, r.reason)
            is PairingImport.Result.IdentityConflict -> ImportResult(false, "identity_conflict")
        }
    }

    /** Deletes pairing.json only (identity is kept, §4.4), then restarts the service loop. */
    override suspend fun unpair() {
        withContext(Dispatchers.IO) { store.delete(CredentialNames.PAIRING) }
        kick()
    }

    /** Re-reads credentials: the Python supervisor restarts the service immediately (no backoff). */
    private suspend fun kick() {
        val paired = store.exists(CredentialNames.PAIRING)
        _diag.update { it.copy(paired = paired) }
        val b = bridge
        if (b == null) {
            _link.value = if (paired) LinkState.Connecting else LinkState.Unpaired
            return
        }
        withContext(Dispatchers.IO) { runCatching { b.callAttr("kick") } }
    }

    override suspend fun openCall(threadId: String?): CallHandle {
        val b = bridge ?: throw IllegalStateException("not_started")
        val id = UUID.randomUUID().toString()
        val handle = SdkCallHandle(id, b, scope) { calls.remove(it) }
        calls[id] = handle
        _diag.update { it.copy(callPhase = "admitting") }
        try {
            withContext(Dispatchers.IO) { b.callAttr("open_call", id, threadId) }
        } catch (c: CancellationException) {
            calls.remove(id); throw c
        } catch (t: Throwable) {
            calls.remove(id)
            Log.w(TAG, "openCall failed: " + Redactor.redact(t.toString()).take(200))
            throw t
        }
        return handle
    }

    override suspend fun sendDictatedTurn(pcm: ShortArray, sessionId: UUID): FallbackTurn {
        val b = bridge ?: return FallbackTurn(null, null, "offline")
        val req = UUID.randomUUID().toString()
        val done = CompletableDeferred<FallbackTurn>()
        fallbacks[req] = done
        try {
            // The SDK caps a voice note at 2 MiB of WAV. TODO(M6): chunk/trim smarter for 60 s holds.
            val wav = Wav.encode(if (pcm.size > MAX_FALLBACK_SAMPLES) pcm.copyOf(MAX_FALLBACK_SAMPLES) else pcm)
            withContext(Dispatchers.IO) { b.callAttr("dictated", req, wav, sessionId.toString()) }
            return try {
                withTimeout(70_000) { done.await() }
            } catch (e: TimeoutCancellationException) {
                FallbackTurn(null, null, "send_failed")
            }
        } finally {
            fallbacks.remove(req)
        }
    }

    override fun replyCommand(invocationId: String, result: CommandResult) {
        val b = bridge ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { b.callAttr("reply_command", invocationId, result.ok, result.payloadJson, result.error) }
        }
    }

    // ---- callbacks from Python (SdkHost) ----
    internal fun call(id: String): SdkCallHandle? = calls[id]

    internal fun onLink(kind: String, a: String?, b: String?) {
        _link.value = when (kind) {
            "unpaired" -> LinkState.Unpaired
            "connecting" -> LinkState.Connecting
            "online" -> LinkState.Online(a ?: "", b ?: "")
            "offline" -> LinkState.Offline(a ?: "offline")
            "pairing_lost" -> LinkState.PairingLost(a ?: "revoked")
            else -> return
        }
        _diag.update { it.copy(paired = _link.value !is LinkState.Unpaired && store.exists(CredentialNames.PAIRING)) }
    }

    internal fun onCommand(invocationId: String, name: String, paramsJson: String) {
        val ok = _events.tryEmit(TransportEvent.Command(invocationId, name, paramsJson, this.kind))
        if (!ok) replyCommand(invocationId, CommandResult.error("display busy"))
    }

    internal fun onDiagnostic(level: String, code: String) {
        val lv = runCatching { Level.valueOf(level) }.getOrDefault(Level.ERROR)
        _events.tryEmit(TransportEvent.Diagnostic(lv, code, kind))
    }

    internal fun onTurn(started: Boolean, id: String) {
        _events.tryEmit(if (started) TransportEvent.TurnStarted(id, kind) else TransportEvent.TurnSettled(id, kind))
    }

    internal fun onTranscript(role: String, text: String, final: Boolean) {
        _events.tryEmit(TransportEvent.Transcript(if (role == "user") Role.USER else Role.MUSE, text, final, kind))
    }

    internal fun onAdvisory(count: Int) {
        _diag.update { it.copy(advisoryErrors = count) }
        _events.tryEmit(TransportEvent.AdvisoryError(count, kind))
    }

    internal fun onCallPhaseDiag(phase: String, reason: String?) {
        _diag.update { it.copy(callPhase = if (reason != null) "$phase:$reason" else phase) }
    }

    internal fun onFallback(reqId: String, reply: String?, error: String?) {
        fallbacks[reqId]?.complete(FallbackTurn(reply, null, error))
    }

    internal fun onDiag(key: String, value: String) {
        _diag.update { d ->
            when (key) {
                "sdkVersion" -> d.copy(sdkVersion = value)
                "sdkCommit" -> d.copy(sdkCommit = value)
                "nodeId" -> d.copy(nodeId = value)
                "tokenHint" -> d.copy(tokenHint = value.ifEmpty { null })
                "tlsRoots" -> d.copy(tlsRoots = value.toIntOrNull() ?: -1)
                "httpsProbe" -> d.copy(httpsProbe = value)
                "commandCount" -> d.copy(commandCount = value.toIntOrNull() ?: 0)
                "describeChars" -> d.copy(describeChars = value.toIntOrNull() ?: 0)
                "storageRoundTrip" -> d.copy(storageRoundTrip = value == "true")
                "lastOutcome" -> d.copy(lastOutcome = value)
                "restarts" -> d.copy(restarts = value.toIntOrNull() ?: 0)
                else -> d
            }
        }
        if (key == "tlsRoots" || key == "httpsProbe" || key == "storageRoundTrip" || key == "describeChars" || key == "commandCount") {
            Log.i(TAG, "diag $key=$value")
        }
    }

    private fun versionName(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

    internal companion object {
        const val TAG = "JARVIS_SDK"
        const val MAX_FALLBACK_SAMPLES = (2 * 1024 * 1024 - Wav.HEADER_BYTES) / 2
    }
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
