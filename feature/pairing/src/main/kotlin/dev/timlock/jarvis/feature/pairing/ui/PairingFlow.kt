package dev.timlock.jarvis.feature.pairing.ui

import dev.timlock.jarvis.core.transport.PairOutcome
import dev.timlock.jarvis.core.transport.PairingStage
import dev.timlock.jarvis.core.transport.PairingUiSink
import dev.timlock.jarvis.core.transport.SdkToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §4.2 steps: P1 token, P2 Bluetooth readiness, P3 window open, P4 result. */
enum class PairStep { TOKEN, BLUETOOTH, WINDOW, RESULT }

enum class CheckStatus { OK, FAIL, PENDING }

/** P2 checks, in order. [advertisingDetail] carries an unrecognized readiness code (shown as a hint, never a secret). */
data class Readiness(
    val bluetooth: CheckStatus,
    val permissions: CheckStatus,
    val advertising: CheckStatus,
    val advertisingDetail: String? = null,
) {
    val allGreen: Boolean get() = bluetooth == CheckStatus.OK && permissions == CheckStatus.OK && advertising == CheckStatus.OK

    companion object {
        val GREEN = Readiness(CheckStatus.OK, CheckStatus.OK, CheckStatus.OK)
        val UNKNOWN = Readiness(CheckStatus.PENDING, CheckStatus.PENDING, CheckStatus.PENDING)

        /** Maps [dev.timlock.jarvis.core.transport.BlePeripheral.readinessProblem] to the three rows. */
        fun fromProblem(code: String?): Readiness = when (code) {
            null -> GREEN
            "bluetooth_off" -> Readiness(CheckStatus.FAIL, CheckStatus.PENDING, CheckStatus.PENDING)
            "no_permission" -> Readiness(CheckStatus.PENDING, CheckStatus.FAIL, CheckStatus.PENDING)
            "advertise_unsupported" -> Readiness(CheckStatus.OK, CheckStatus.OK, CheckStatus.FAIL)
            else -> Readiness(CheckStatus.OK, CheckStatus.OK, CheckStatus.FAIL, advertisingDetail = code)
        }
    }
}

/** P4 outcomes shown on the result screen (INVALID_SDK_TOKEN goes back to P1 instead). */
enum class ResultKind { PAIRED, ALREADY_PAIRED, WINDOW_CLOSED, BLE_ERROR }

/** How the flow ended; the host decides what to show next. */
enum class FlowExit { CANCELLED, DONE, IMPORT }

data class FlowState(
    val step: PairStep = PairStep.TOKEN,
    /** Last 4 chars of the stored token (never more). Null = nothing stored. */
    val storedLast4: String? = null,
    /** The user chose "Replace" (or typed/provisioned a token): show the entry field instead of the saved card. */
    val replacing: Boolean = false,
    /** Pasted/typed text. Masked on screen; never logged. */
    val entered: String = "",
    /** Amber note on P1 (revoked token hint, INVALID_SDK_TOKEN detail). */
    val tokenNote: String? = null,
    /** Increments when the token field should grab focus. */
    val focusRequest: Int = 0,
    val readiness: Readiness = Readiness.UNKNOWN,
    val bleName: String? = null,
    val timeoutS: Int = 0,
    /** Number of [PairingStage.ORDER] stages reached (0..4). */
    val stagesReached: Int = 0,
    val remainingS: Int? = null,
    val result: ResultKind? = null,
    val nodeId: String? = null,
    val failures: Int = 0,
) {
    val enteredValid: Boolean get() = entered.isNotBlank() && SdkToken(entered).isWellFormed()
    val showSavedCard: Boolean get() = storedLast4 != null && !replacing && entered.isEmpty()
    val suggestImport: Boolean get() = failures >= PairingFlow.FAILURES_BEFORE_IMPORT_HINT
}

/** What the flow needs from the app (kept free of Android/transport/store types so the flow is pure and testable). */
interface PairingDeps {
    /** Simulation: no real token storage, fake readiness. */
    val sim: Boolean
    fun storedToken(): SdkToken?
    fun saveToken(token: SdkToken)
    fun readiness(): Readiness
    suspend fun pair(token: SdkToken, ui: PairingUiSink): PairOutcome
    /** "homelink-xxxxxx" once known (link online / diagnostics), else null. */
    fun nodeId(): String?
    /** P3 entered: the orb goes PAIRING. */
    fun windowOpened()
    /** P3 left: [paired] true on PAIRED / ALREADY_PAIRED. */
    fun windowClosed(paired: Boolean)
}

/**
 * Pairing flow state machine (§4.2 P1-P4). Pure Kotlin: all work happens on [scope], state is a [StateFlow].
 * Cancel is allowed from any step. [onExit] is called at most once.
 */
class PairingFlow(
    private val deps: PairingDeps,
    private val scope: CoroutineScope,
    private val onExit: (FlowExit) -> Unit,
    startNote: String? = null,
    /** Re-pair (§4.4): open at P1 with the stored token selected. */
    val repair: Boolean = false,
    private val continueDelayMs: Long = 3000,
    private val readinessPollMs: Long = 800,
) {
    private val _state = MutableStateFlow(
        FlowState(
            storedLast4 = deps.storedToken()?.value?.trim()?.takeLast(4),
            tokenNote = startNote,
        ),
    )
    val state: StateFlow<FlowState> = _state.asStateFlow()
    val sim: Boolean get() = deps.sim

    @Volatile private var token: SdkToken? = null
    private var pollJob: Job? = null
    private var pairJob: Job? = null
    private var continueJob: Job? = null
    private var windowOpen = false
    @Volatile private var tokenProblem: String? = null
    @Volatile private var exited = false

    // ---- P1 ---------------------------------------------------------------------------------------

    fun onTokenChanged(text: String) {
        if (_state.value.step != PairStep.TOKEN) return
        _state.update { it.copy(entered = text, tokenNote = null) }
    }

    fun replaceStored() { _state.update { it.copy(replacing = true) } }

    /** "Use" on the saved card / "Token already saved? (skip)". */
    fun useStored() {
        val t = deps.storedToken() ?: return
        if (_state.value.step != PairStep.TOKEN) return
        token = t
        goBluetooth()
    }

    /** NEXT with a valid entered token: store it, continue. */
    fun next() {
        val s = _state.value
        if (s.step != PairStep.TOKEN) return
        if (!s.enteredValid) {
            if (s.storedLast4 != null && s.entered.isEmpty()) useStored()
            return
        }
        scope.launch {
            val t = SdkToken(s.entered.trim())
            deps.saveToken(t)
            token = t
            _state.update { it.copy(storedLast4 = t.value.takeLast(4)) }
            goBluetooth()
        }
    }

    /**
     * ADB `provision_token`: accepted only while on P1. Stores the token and fills the (masked) field.
     * Returns null on success, else an error text (never the token).
     */
    suspend fun provisionToken(raw: String?): String? {
        if (_state.value.step != PairStep.TOKEN) return NOT_ON_TOKEN_SCREEN
        val t = SdkToken(raw.orEmpty())
        if (!t.isWellFormed()) return "malformed token"
        val clean = SdkToken(t.value.trim())
        deps.saveToken(clean)
        _state.update { it.copy(entered = clean.value, replacing = true, tokenNote = null, storedLast4 = clean.value.takeLast(4)) }
        return null
    }

    // ---- P2 ---------------------------------------------------------------------------------------

    private fun goBluetooth() {
        cancelJobs()
        _state.update { it.copy(step = PairStep.BLUETOOTH, readiness = Readiness.UNKNOWN) }
        pollJob = scope.launch {
            while (true) {
                val r = try { deps.readiness() } catch (e: CancellationException) { throw e } catch (e: Exception) { Readiness.fromProblem("error") }
                _state.update { it.copy(readiness = r) }
                if (r.allGreen) { startWindow(); return@launch }
                delay(readinessPollMs)
            }
        }
    }

    // ---- P3 ---------------------------------------------------------------------------------------

    /** Opens the window (also the "Try again" / "Retry" action on P4). */
    fun startWindow() {
        val t = token ?: return
        continueJob?.cancel()
        tokenProblem = null
        windowOpen = true
        _state.update {
            it.copy(step = PairStep.WINDOW, bleName = null, timeoutS = 0, stagesReached = 0, remainingS = null, result = null, tokenNote = null)
        }
        deps.windowOpened()
        pairJob = scope.launch {
            val outcome = try {
                deps.pair(t, sink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairOutcome.BLE_ERROR
            }
            finish(outcome)
        }
    }

    private val sink = object : PairingUiSink {
        override fun onProgress(stage: String, fraction: Float) {
            val n = PairingStage.ORDER.indexOf(stage) + 1
            if (n > 0) _state.update { if (n > it.stagesReached) it.copy(stagesReached = n) else it }
        }
        override fun onWindowRemaining(seconds: Int) { _state.update { it.copy(remainingS = seconds.coerceAtLeast(0)) } }
        override fun onWindowOpen(bleName: String, timeoutS: Int, hasSdkToken: Boolean) {
            _state.update { it.copy(bleName = bleName, timeoutS = timeoutS, remainingS = timeoutS) }
        }
        override fun onTokenProblem(text: String) { tokenProblem = text }
    }

    // ---- P4 ---------------------------------------------------------------------------------------

    private fun finish(outcome: PairOutcome) {
        closeWindow(paired = outcome == PairOutcome.PAIRED || outcome == PairOutcome.ALREADY_PAIRED)
        when (outcome) {
            PairOutcome.PAIRED -> {
                _state.update { it.copy(step = PairStep.RESULT, result = ResultKind.PAIRED, nodeId = deps.nodeId(), failures = 0) }
                continueJob = scope.launch {
                    // The node id may only be known once the link comes up; refresh while the success screen is showing.
                    var waited = 0L
                    while (waited < continueDelayMs) {
                        val step = minOf(500L, continueDelayMs - waited)
                        delay(step); waited += step
                        deps.nodeId()?.let { id -> _state.update { it.copy(nodeId = id) } }
                    }
                    exit(FlowExit.DONE)
                }
            }
            PairOutcome.ALREADY_PAIRED ->
                _state.update { it.copy(step = PairStep.RESULT, result = ResultKind.ALREADY_PAIRED, nodeId = deps.nodeId()) }
            PairOutcome.INVALID_SDK_TOKEN -> _state.update {
                it.copy(
                    step = PairStep.TOKEN, entered = "", replacing = true, focusRequest = it.focusRequest + 1,
                    tokenNote = tokenProblem ?: "That token was not accepted. Create a new one at gadgets.muse.ai",
                    failures = it.failures + 1,
                )
            }
            PairOutcome.WINDOW_CLOSED -> _state.update { it.copy(step = PairStep.RESULT, result = ResultKind.WINDOW_CLOSED, failures = it.failures + 1) }
            PairOutcome.BLE_ERROR -> _state.update { it.copy(step = PairStep.RESULT, result = ResultKind.BLE_ERROR, failures = it.failures + 1) }
        }
    }

    /** P4 "Continue" (ALREADY_PAIRED, or skipping the PAIRED auto-continue). */
    fun continueAfterResult() {
        val r = _state.value.result
        if (_state.value.step == PairStep.RESULT && (r == ResultKind.PAIRED || r == ResultKind.ALREADY_PAIRED)) exit(FlowExit.DONE)
    }

    /** P4 "Back": return to P1. */
    fun backToToken() {
        cancelJobs()
        _state.update { it.copy(step = PairStep.TOKEN, result = null, replacing = it.replacing || it.storedLast4 == null) }
    }

    /** P4 "Retry" / "Try again". */
    fun retry() {
        if (_state.value.step == PairStep.RESULT) startWindow()
    }

    // ---- exits ------------------------------------------------------------------------------------

    fun cancel() {
        cancelJobs()
        closeWindow(paired = false)
        exit(FlowExit.CANCELLED)
    }

    /** "Import pairing instead" from P1, P2 or P4. */
    fun importInstead() {
        cancelJobs()
        closeWindow(paired = false)
        exit(FlowExit.IMPORT)
    }

    /** Host teardown (the screen left composition); does not call [onExit]. */
    fun close() {
        exited = true
        cancelJobs()
        closeWindow(paired = false)
    }

    private fun closeWindow(paired: Boolean) {
        if (windowOpen) { windowOpen = false; deps.windowClosed(paired) }
    }

    private fun cancelJobs() {
        pollJob?.cancel(); pairJob?.cancel(); continueJob?.cancel()
        pollJob = null; pairJob = null; continueJob = null
    }

    private fun exit(e: FlowExit) {
        if (exited) return
        exited = true
        onExit(e)
    }

    companion object {
        const val FAILURES_BEFORE_IMPORT_HINT = 3
        const val NOT_ON_TOKEN_SCREEN = "not on token screen"
    }
}

/** m:ss for the window countdown. */
fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
