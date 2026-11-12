package dev.timlock.jarvis.transport.sdk

import android.util.Log
import com.chaquo.python.PyObject
import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.transport.CallHandle
import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One realtime voice call (a Python CallRunner/VoiceCall). Mic frames are pumped to Python; speaker PCM arrives via
 * [onPcm]/[onFlush]; phase changes via [onPhase]. Never blocks the caller beyond the stated bounds.
 */
class SdkCallHandle internal constructor(
    private val callId: String,
    private val bridge: PyObject,
    private val scope: CoroutineScope,
    private val onEnded: (String) -> Unit,
) : CallHandle {
    private val _state = MutableStateFlow<CallPhase>(CallPhase.Admitting)
    override val state: StateFlow<CallPhase> = _state.asStateFlow()

    @Volatile private var sink: PcmSink? = null
    @Volatile private var pump: Job? = null

    override fun attachSpeaker(sink: PcmSink) { this.sink = sink }

    override fun attachMic(source: PcmSource) {
        pump?.cancel()
        pump = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && _state.value !is CallPhase.Ended) {
                    val frame = source.nextFrame()
                    if (frame == null) { bridge.callAttr("call_eof", callId); break }
                    bridge.callAttr("call_push", callId, PcmCodec.toLe(frame))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(SdkTransport.TAG, "mic pump stopped: ${t.javaClass.simpleName}")
            }
        }
    }

    override suspend fun bargeIn() = bounded(2_000) { bridge.callAttr("call_barge_in", callId) }

    override suspend fun hangUp() {
        bounded(5_000) { bridge.callAttr("call_hang_up", callId) }
        onPhase("ended", "hangup")
    }

    private suspend fun bounded(ms: Long, f: () -> Unit) {
        val job = scope.launch(Dispatchers.IO) {
            try { f() } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                Log.w(SdkTransport.TAG, "call op failed: ${t.javaClass.simpleName}")
            }
        }
        withTimeoutOrNull(ms) { job.join() }
    }

    // ---- from Python (via SdkHost) ----
    internal fun onPhase(phase: String, reason: String?) {
        when (phase) {
            "ready" -> _state.update { if (it is CallPhase.Admitting) CallPhase.Ready else it }
            "ended" -> {
                var first = false
                _state.update { if (it is CallPhase.Ended) it else { first = true; CallPhase.Ended(reason ?: "failed") } }
                if (first) { pump?.cancel(); onEnded(callId) }
            }
        }
    }

    internal fun onPcm(le: ByteArray) { sink?.write(PcmCodec.fromLe(le)) }
    internal fun onFlush() { sink?.flush() }
}
