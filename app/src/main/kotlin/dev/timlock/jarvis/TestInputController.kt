package dev.timlock.jarvis

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.timlock.jarvis.audio.MicSource
import dev.timlock.jarvis.audio.Resample
import dev.timlock.jarvis.audio.WavReader
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.CallStatus
import dev.timlock.jarvis.core.store.Intent
import dev.timlock.jarvis.core.store.PttState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * §7.7 test-input hooks: feeds a WAV from `files/test_wav/<name>.wav` as the mic input of one PTT turn (real call in
 * REAL mode, free-play in SIM mode), plus WAV generation via Android TTS. One injection at a time. Results never
 * contain captions or server text (only [captionChars], a length).
 */
class TestInputController(
    private val context: Context,
    private val store: AppStore,
    private val mic: MicSource,
    private val calls: CallController,
    private val dev: DevSettings,
) {
    private val running = AtomicBoolean(false)

    fun wavDir(): File = (context.getExternalFilesDir("test_wav") ?: File(context.filesDir, "test_wav")).also { it.mkdirs() }

    private fun fail(cmd: String, error: String) = JSONObject().put("cmd", cmd).put("ok", false).put("error", error)

    fun list(): JSONObject {
        val arr = JSONArray()
        wavDir().listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }?.forEach { f ->
            val o = JSONObject().put("name", f.name.removeSuffix(".wav")).put("bytes", f.length())
            try {
                val w = WavReader.parse(f.readBytes())
                o.put("sampleRate", w.sampleRate).put("durationMs", w.durationMs)
            } catch (e: IllegalArgumentException) {
                o.put("error", e.message)
            }
            arr.put(o)
        }
        return JSONObject().put("cmd", "list_wav").put("ok", true).put("files", arr)
    }

    suspend fun inject(name: String, threadId: String?, hangUpAfter: Boolean, timeoutMs: Long): JSONObject {
        val cmd = "inject_wav"
        if (!NAME_RE.matches(name)) return fail(cmd, "bad name (A-Za-z0-9._- , 1-40 chars)")
        if (!running.compareAndSet(false, true)) return fail(cmd, "busy")
        var armed = false
        try {
            val s0 = store.state.value
            val mode = s0.mode ?: return fail(cmd, "no mode chosen")
            if (mode == Mode.REAL) {
                if (!dev.testInputEnabled()) return fail(cmd, "test input disabled (dev_setting test_input on)")
                if (s0.link !is LinkState.Online) return fail(cmd, "link not online")
            }
            if (s0.ptt != PttState.Up) return fail(cmd, "busy")
            // STANDBY = an ordinary (non-test) call is still open; injecting would land in it. Hang up first.
            if (s0.orbState == OrbState.STANDBY) return fail(cmd, "orb state standby: a call is open (hang up first)")
            if (s0.orbState in BAD_ORBS) return fail(cmd, "orb state ${s0.orbState.name.lowercase()} not allowed")
            val file = File(wavDir(), "$name.wav")
            if (!file.isFile) return fail(cmd, "no such wav")
            val pcm = try {
                val w = WavReader.parse(file.readBytes())
                Resample.to24k(w.pcm, w.sampleRate)
            } catch (e: IllegalArgumentException) {
                return fail(cmd, e.message ?: "wav: invalid")
            }
            val wavMs = pcm.size * 1000L / 24_000
            if (wavMs > MAX_WAV_MS) return fail(cmd, "wav too long (max 55 s)")
            if (wavMs < MIN_WAV_MS) return fail(cmd, "wav too short (min 0.3 s)")

            var err: String? = null
            for (i in 0 until 30) {
                err = mic.armInjection(pcm) { store.dispatch(Intent.PttUp) }
                if (err != "mic busy") break
                delay(100) // a previous capture tail (2 s) may still hold the mic
            }
            if (err != null) return fail(cmd, err)
            armed = true
            val enabled = s0.testInputEnabled || mode == Mode.REAL
            store.dispatch(Intent.SetTestInput(enabled, active = true))
            calls.nextThreadId = threadId?.takeIf { it.isNotBlank() } ?: dev.testThreadId()
            calls.nextCallIsTest = true

            val path = mutableListOf<OrbState>()
            var sawThinking = false
            var sawSpeaking = false
            var seenActive = false
            var captionChars = 0
            // The collector must be attached before PttDown so no early transition is missed.
            val outcome = coroutineScope {
              val waiter = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(timeoutMs) {
                store.state.mapNotNull { s ->
                    if (path.lastOrNull() != s.orbState) path.add(s.orbState)
                    s.caption?.let { captionChars = maxOf(captionChars, it.text.length) }
                    when (s.orbState) {
                        OrbState.CONNECTING, OrbState.LISTENING -> seenActive = true
                        OrbState.THINKING -> { seenActive = true; sawThinking = true }
                        OrbState.SPEAKING -> { seenActive = true; sawThinking = true; sawSpeaking = true }
                        else -> Unit
                    }
                    when {
                        s.orbState == OrbState.ERROR && seenActive -> "error"
                        seenActive && s.orbState in ENDED_ORBS -> "ended"
                        s.orbState == OrbState.STANDBY && sawThinking -> if (sawSpeaking) "replied" else "settled_no_audio"
                        else -> null
                    }
                }.first()
              } ?: "timeout" }
              store.dispatch(Intent.PttDown)
              waiter.await()
            }

            return finish(cmd, outcome, name, wavMs, mode, path, captionChars, hangUpAfter)
        } finally {
            if (armed) mic.cancelInjection()
            calls.nextThreadId = null
            calls.nextCallIsTest = false
            val s = store.state.value
            store.dispatch(Intent.SetTestInput(s.testInputEnabled, active = false))
            running.set(false)
        }
    }

    private suspend fun finish(
        cmd: String, outcome: String, name: String, wavMs: Long, mode: Mode,
        path: List<OrbState>, captionChars: Int, hangUpAfter: Boolean,
    ): JSONObject {
        mic.cancelInjection()
        if (store.state.value.ptt != PttState.Up) store.dispatch(Intent.PttUp)
        if (hangUpAfter && store.state.value.call != CallStatus.None) {
            store.dispatch(Intent.HangUp)
            withTimeoutOrNull(3_000) { while (calls.current != null) delay(50) }
        }
        val stats = calls.current ?: calls.last
        return JSONObject().put("cmd", cmd).put("ok", outcome == "replied").put("outcome", outcome)
            .put("name", name).put("durationMs", wavMs).put("mode", if (mode == Mode.REAL) "real" else "sim")
            .put("stats", stats?.toJson() ?: JSONObject.NULL)
            .put("orbPath", JSONArray().also { a -> path.forEach { a.put(it.name.lowercase()) } })
            .put("captionChars", captionChars)
    }

    /** Synthesizes `"Test input. " + text` (label unless [label] is false) into test_wav/<name>.wav via Android TTS. */
    suspend fun makeWav(name: String, text: String, label: Boolean): JSONObject {
        val cmd = "make_wav"
        if (!NAME_RE.matches(name)) return fail(cmd, "bad name (A-Za-z0-9._- , 1-40 chars)")
        val t = text.trim()
        if (t.isEmpty()) return fail(cmd, "missing text")
        if (t.length > MAX_TEXT) return fail(cmd, "text too long (max $MAX_TEXT chars)")
        val spoken = if (label) "Test input. $t" else t
        val out = File(wavDir(), "$name.wav")
        out.delete()
        val err = try {
            withTimeout(TTS_TIMEOUT_MS) { synthesize(spoken, out) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "tts timeout"
        }
        if (err != null) { out.delete(); return fail(cmd, err) }
        return try {
            val w = WavReader.parse(out.readBytes())
            JSONObject().put("cmd", cmd).put("ok", true).put("name", name)
                .put("sampleRate", w.sampleRate).put("durationMs", w.durationMs)
        } catch (e: IllegalArgumentException) {
            out.delete(); fail(cmd, e.message ?: "wav: invalid")
        }
    }

    /** Returns null on success or an error string. Always shuts the engine down. */
    private suspend fun synthesize(spoken: String, out: File): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            val finished = AtomicBoolean(false)
            fun done(r: String?) {
                if (finished.compareAndSet(false, true)) {
                    try { tts?.shutdown() } catch (_: Throwable) {}
                    if (cont.isActive) cont.resume(r)
                }
            }
            cont.invokeOnCancellation { if (finished.compareAndSet(false, true)) try { tts?.shutdown() } catch (_: Throwable) {} }
            try {
                tts = TextToSpeech(context) { status ->
                    val e = tts
                    if (status != TextToSpeech.SUCCESS || e == null) return@TextToSpeech done("no tts engine")
                    if (e.engines.isEmpty()) return@TextToSpeech done("no tts engine")
                    try {
                        e.language = Locale.US
                        val id = UUID.randomUUID().toString()
                        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { done(null) }
                            @Deprecated("deprecated in API") override fun onError(utteranceId: String?) { done("tts error") }
                            override fun onError(utteranceId: String?, errorCode: Int) { done("tts error $errorCode") }
                        })
                        if (e.synthesizeToFile(spoken, Bundle(), out, id) != TextToSpeech.SUCCESS) done("tts synth failed")
                    } catch (t: Throwable) {
                        Log.w("JARVIS_SIM", "tts failed: ${t.javaClass.simpleName}")
                        done("tts failed: ${t.javaClass.simpleName}")
                    }
                }
            } catch (t: Throwable) {
                done("no tts engine")
            }
        }
    }

    private companion object {
        val NAME_RE = Regex("[A-Za-z0-9._-]{1,40}")
        val BAD_ORBS = setOf(OrbState.UNPAIRED, OrbState.OFFLINE, OrbState.PAIRING, OrbState.BOOT)
        val ENDED_ORBS = setOf(OrbState.IDLE, OrbState.OFFLINE, OrbState.UNPAIRED)
        const val MAX_WAV_MS = 55_000L
        const val MIN_WAV_MS = 300L
        const val MAX_TEXT = 300
        const val TTS_TIMEOUT_MS = 20_000L
    }
}
