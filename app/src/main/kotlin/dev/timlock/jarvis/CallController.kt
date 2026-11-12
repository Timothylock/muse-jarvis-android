package dev.timlock.jarvis

import android.util.Log
import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.store.Input
import dev.timlock.jarvis.core.store.Intent
import dev.timlock.jarvis.core.transport.CallHandle
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSinkListener
import dev.timlock.jarvis.transport.sim.VirtualSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Generic executor for the store's call/audio effects. Talks only to [GadgetTransport]/[CallHandle], so the real
 * transport reuses it unchanged. [handle] must be called from ONE coroutine, in effect order.
 */
class CallController(
    private val scope: CoroutineScope,
    private val transport: () -> GadgetTransport?,
    private val dispatch: (Input) -> Unit,
) {
    private var activeTransport: GadgetTransport? = null
    private var handle: CallHandle? = null
    private var speaker: PcmSink? = null
    private var openJob: Job? = null
    private var stateJob: Job? = null

    /** M4: virtual sink. TODO(M5): AudioTrack Player. */
    private fun createSpeaker(): PcmSink = VirtualSink(scope)

    suspend fun handle(e: Effect) {
        // A mode switch replaces the transport: anything belonging to the old one is dead.
        if (activeTransport !== transport()) dropCall()
        when (e) {
            Effect.OpenCall -> open()
            Effect.HangUpCall -> {
                val h = handle
                openJob?.cancel()
                try { h?.hangUp() } catch (t: Throwable) { Log.w("JARVIS_SIM", "hangUp failed: ${t.javaClass.simpleName}") }
                dropCall()
            }
            Effect.BargeIn -> {
                try { handle?.bargeIn() } catch (t: Throwable) { Log.w("JARVIS_SIM", "bargeIn failed: ${t.javaClass.simpleName}") }
                speaker?.flush()
            }
            // TODO(M5): mic capture. No mic is attached in M4.
            Effect.MicStart, Effect.MicLive, Effect.MicSilence, Effect.MicStop, is Effect.MicStopAfter ->
                Log.d("JARVIS_STORE", "mic effect ${e::class.simpleName} (no mic in M4)")
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
        openJob = scope.launch {
            val h = try {
                t.openCall(null)
            } catch (c: CancellationException) {
                throw c
            } catch (th: Throwable) {
                Log.w("JARVIS_SIM", "openCall failed: ${th.javaClass.simpleName}")
                dispatch(Intent.CallPhaseChanged(CallPhase.Ended("admission_failed")))
                return@launch
            }
            val sink = createSpeaker()
            sink.setListener(object : PcmSinkListener {
                override fun onFirstAudible() { dispatch(Intent.SinkFirstAudible) }
                override fun onDrained() { dispatch(Intent.SinkDrained) }
            })
            h.attachSpeaker(sink)
            synchronized(this@CallController) {
                handle = h
                speaker = sink
            }
            stateJob = launch {
                h.state.takeWhile { phase ->
                    dispatch(Intent.CallPhaseChanged(phase))
                    phase !is CallPhase.Ended
                }.collect {}
                synchronized(this@CallController) { if (handle === h) { handle = null; speaker = null } }
            }
        }
    }

    @Synchronized
    private fun dropCall() {
        openJob?.cancel(); openJob = null
        stateJob?.cancel(); stateJob = null
        speaker?.setListener(null)
        handle = null
        speaker = null
        activeTransport = transport()
    }
}
