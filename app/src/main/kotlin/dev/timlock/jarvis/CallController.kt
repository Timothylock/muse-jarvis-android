package dev.timlock.jarvis

import android.util.Log
import dev.timlock.jarvis.audio.MicSource
import dev.timlock.jarvis.audio.Player
import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.store.Input
import dev.timlock.jarvis.core.store.Intent
import dev.timlock.jarvis.core.transport.CallHandle
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSinkListener
import dev.timlock.jarvis.core.transport.PcmSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Generic executor for the store's call/audio effects. Talks only to [GadgetTransport]/[CallHandle], so the real
 * transport reuses it unchanged. [handle] must be called from ONE coroutine, in effect order.
 *
 * Audio wiring: one app-lifetime [Player] is the speaker for every call (and for fallback replies); one
 * app-lifetime [MicSource] captures, and each call's handle gets its own EOF-able stream from it. Earcons/haptics
 * are separate store effects handled in [AppGraph].
 */
class CallController(
    private val scope: CoroutineScope,
    private val transport: () -> GadgetTransport?,
    private val mic: MicSource,
    private val player: Player,
    private val dispatch: (Input) -> Unit,
) {
    private var activeTransport: GadgetTransport? = null
    private var handle: CallHandle? = null
    private var openJob: Job? = null
    private var stateJob: Job? = null
    private var fallbackJob: Job? = null
    private var sessionId: UUID = UUID.randomUUID()
    private var lastFallbackMs = 0L

    /** Consumed (and cleared) by the next [open]: a dedicated Muse thread for test calls (§7.7); null = SDK default. */
    @Volatile var nextThreadId: String? = null
    /** Marks the next call as a test call (TestInputController). */
    @Volatile var nextCallIsTest: Boolean = false
    private var callSeq = 0
    @Volatile private var track: CallTrack? = null
    @Volatile private var lastTrack: CallTrack? = null

    /** Snapshot of the open call's stats, or null. */
    val current: CallStats? get() = track?.snapshot(monoMillis())
    /** Snapshot of the most recent ended call, or null. */
    val last: CallStats? get() = lastTrack?.snapshot(monoMillis())

    private fun finishTrack(reason: String) {
        val t = track ?: return
        track = null
        if (t.endReason == null) t.endReason = reason
        if (t.endedAtMs == 0L) t.endedAtMs = monoMillis()
        lastTrack = t
    }

    init {
        player.setListener(object : PcmSinkListener {
            override fun onFirstAudible() {
                track?.let { t -> val e = t.turnEndMs; if (e != 0L) { t.firstAudibleMs = monoMillis() - e; t.turnEndMs = 0L } }
                dispatch(Intent.SinkFirstAudible)
            }
            override fun onDrained() { dispatch(Intent.SinkDrained) }
        })
    }

    suspend fun handle(e: Effect) {
        // A mode switch replaces the transport: anything belonging to the old one is dead.
        if (activeTransport !== transport()) { dropCall(); player.flush() }
        when (e) {
            Effect.OpenCall -> open()
            Effect.HangUpCall -> {
                val h = handle
                openJob?.cancel()
                // Not awaited: the transport bounds hangUp at ~5 s and awaiting it here would block every later
                // effect (OpenCall, MicStart) in this one-coroutine pipeline. The bridge itself waits out the old
                // call's runner when the next call opens.
                if (h != null) scope.launch {
                    try { h.hangUp() } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                        Log.w("JARVIS_SIM", "hangUp failed: ${t.javaClass.simpleName}")
                    }
                }
                finishTrack("hangup")
                dropCall()
                player.flush()
            }
            Effect.BargeIn -> {
                fallbackJob?.cancel(); fallbackJob = null
                try { handle?.bargeIn() } catch (t: Throwable) { Log.w("JARVIS_SIM", "bargeIn failed: ${t.javaClass.simpleName}") }
                player.flush()
            }
            Effect.MicStart -> mic.start()
            Effect.MicLive -> mic.live()
            Effect.MicSilence -> { track?.turnEndMs = monoMillis(); mic.silence() }
            is Effect.MicStopAfter -> mic.stopAfter(e.delayMs)
            Effect.MicStop -> mic.stop()
            Effect.FallbackSend -> fallbackSend()
            else -> Unit
        }
    }

    private fun open() {
        dropCall()
        val t = transport()
        if (t == null) {
            dispatch(Intent.CallPhaseChanged(CallPhase.Ended("admission_failed")))
            return
        }
        activeTransport = t
        val threadId = nextThreadId?.takeIf { it.isNotBlank() }
        nextThreadId = null
        val test = nextCallIsTest
        nextCallIsTest = false
        val tr = CallTrack(++callSeq, threadId != null, test, monoMillis())
        track = tr
        openJob = scope.launch {
            val h = try {
                t.openCall(threadId)
            } catch (c: CancellationException) {
                throw c
            } catch (th: Throwable) {
                Log.w("JARVIS_SIM", "openCall failed: ${th.javaClass.simpleName}")
                finishTrack("admission_failed")
                dispatch(Intent.CallPhaseChanged(CallPhase.Ended("admission_failed")))
                return@launch
            }
            h.attachSpeaker(CountingSink(player, tr.samples))
            h.attachMic(CountingSource(mic.openStream(), tr))
            synchronized(this@CallController) { handle = h }
            stateJob = launch {
                h.state.takeWhile { phase ->
                    if (phase == CallPhase.Ready && tr.admissionMs == null) tr.admissionMs = monoMillis() - tr.openedAtMs
                    if (phase is CallPhase.Ended) { tr.endReason = phase.reason; if (track === tr) finishTrack(phase.reason) }
                    dispatch(Intent.CallPhaseChanged(phase))
                    phase !is CallPhase.Ended
                }.collect {}
                synchronized(this@CallController) { if (handle === h) handle = null }
                mic.endStream()
            }
        }
    }

    /** §4.3.1: dictate the utterance captured since the last MicStart, then speak the reply (if it has audio). */
    private fun fallbackSend() {
        val pcm = mic.takeUtterance()
        val now = monoMillis()
        if (now - lastFallbackMs > SESSION_IDLE_MS) sessionId = UUID.randomUUID()
        lastFallbackMs = now
        val sid = sessionId
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            val t = transport()
            val turn = try {
                if (t == null) null else t.sendDictatedTurn(pcm, sid)
            } catch (c: CancellationException) {
                throw c
            } catch (th: Throwable) {
                Log.w("JARVIS_STORE", "fallback failed: ${th.javaClass.simpleName}")
                null
            }
            lastFallbackMs = monoMillis()
            val reply = turn?.replyPcm
            dispatch(Intent.FallbackResult(turn?.replyText, reply != null && reply.isNotEmpty(), if (turn == null) "send_failed" else turn.error))
            if (reply != null) feed(reply)
        }
    }

    /** Writes long reply audio in 1 s pieces, keeping <= ~2 s queued (the Player ring holds 5 s). */
    private suspend fun feed(pcm: ShortArray) {
        var i = 0
        while (i < pcm.size) {
            while (player.queuedMs() > 2000) delay(100)
            val n = minOf(FEED_CHUNK, pcm.size - i)
            player.write(pcm.copyOfRange(i, i + n))
            i += n
        }
    }

    @Synchronized
    private fun dropCall() {
        finishTrack("dropped")
        openJob?.cancel(); openJob = null
        stateJob?.cancel(); stateJob = null
        mic.endStream()
        handle = null
        activeTransport = transport()
    }

    /** Counts frames the call handle pulled from the mic stream (live = not all-zero). No per-frame allocation. */
    private class CountingSource(private val inner: PcmSource, private val t: CallTrack) : PcmSource {
        override suspend fun nextFrame(): ShortArray? {
            val f = inner.nextFrame() ?: return null
            t.frames.incrementAndGet()
            for (x in f) if (x.toInt() != 0) { t.liveFrames.incrementAndGet(); break }
            return f
        }
    }

    /** Forwards everything to [inner]; counts samples written. */
    private class CountingSink(private val inner: PcmSink, private val n: java.util.concurrent.atomic.AtomicLong) : PcmSink {
        override fun write(pcm: ShortArray) { n.addAndGet(pcm.size.toLong()); inner.write(pcm) }
        override fun flush() = inner.flush()
        override fun setListener(listener: PcmSinkListener?) = inner.setListener(listener)
    }

    private companion object {
        const val SESSION_IDLE_MS = 10 * 60 * 1000L
        const val FEED_CHUNK = 24_000
    }
}
