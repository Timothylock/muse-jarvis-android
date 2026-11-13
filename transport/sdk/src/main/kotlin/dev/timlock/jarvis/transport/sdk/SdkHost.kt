package dev.timlock.jarvis.transport.sdk

import android.os.Process
import android.util.Log
import dev.timlock.jarvis.core.secure.Redactor

/**
 * The object Python (jarvis_bridge.py) calls back into. Public methods only (Chaquopy reflection). Every method is
 * short and non-blocking: it runs on the python-loop thread or an SDK worker thread.
 */
class SdkHost internal constructor(private val t: SdkTransport) {
    /** Python logging line (INFO+). Redacted here, once, before it reaches logcat. */
    fun log(line: String) {
        for (l in line.lineSequence()) if (l.isNotBlank()) Log.i(SdkTransport.TAG, Redactor.redact(l))
    }

    fun credRead(name: String): String? = t.store.read(name)?.toString(Charsets.UTF_8)
    fun credWrite(name: String, text: String) = t.store.write(name, text.toByteArray(Charsets.UTF_8))
    fun credDelete(name: String) = t.store.delete(name)

    fun onLink(kind: String, a: String?, b: String?) = t.onLink(kind, a, b)
    fun onCommand(invocationId: String, name: String, paramsJson: String) = t.onCommand(invocationId, name, paramsJson)
    fun onDiag(key: String, value: String) = t.onDiag(key, value)
    fun onDiagnostic(level: String, code: String) = t.onDiagnostic(level, code)

    fun onCallPhase(callId: String, phase: String, reason: String?) {
        t.call(callId)?.onPhase(phase, reason)
        t.onCallPhaseDiag(phase, reason)
    }
    fun onTurn(callId: String, started: Boolean, id: String) = t.onTurn(started, id)
    fun onTranscript(callId: String, role: String, text: String, final: Boolean) = t.onTranscript(role, text, final)
    fun onAdvisory(callId: String, count: Int) = t.onAdvisory(count)
    fun pcmOut(callId: String, pcm: ByteArray) { t.call(callId)?.onPcm(pcm) }
    fun pcmFlush(callId: String) { t.call(callId)?.onFlush() }
    fun onFallback(reqId: String, reply: String?, error: String?) = t.onFallback(reqId, reply, error)

    /**
     * Called once at the top of the python-loop thread (Fable A1 #1b): the SDK play pump on that loop is the only
     * realtime clock of the reply audio, so it must not be outranked by GL/Compose. THREAD_PRIORITY_AUDIO (-16),
     * deliberately NOT URGENT_AUDIO: the loop also parses JSON and runs the service session.
     */
    fun onLoopThread() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (e: Throwable) {
            Log.w(SdkTransport.TAG, "loop_priority_failed ${e.javaClass.simpleName}")
        }
    }

    /** Same for the bridge's python-audio-out thread (it only crosses JNI into Player.write). */
    fun onAudioOutThread() = onLoopThread()

    /** pair_selftest: the loopback phone's writes enter here, then take the same path as GATT writes. */
    fun pairSelftestWrite(packet: ByteArray) { t.selfTestListener?.onWrite(packet) }

    /** Self-test helpers for the Python byte[] <-> bytes conversions. */
    fun sampleBytes(): ByteArray = SAMPLE
    fun checkBytes(b: ByteArray): Boolean = b.contentEquals(SAMPLE)

    private companion object {
        val SAMPLE = byteArrayOf(-1, 0, 127, -128)
    }
}
