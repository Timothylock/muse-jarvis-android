package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.transport.PcmFormat
import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSinkListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A [PcmSink] that plays nothing: it tracks a wall-clock playhead over the written 24 kHz PCM so that
 * `onFirstAudible` fires when the first sample after silence is "played" (immediately on the first write when
 * idle) and `onDrained` when the playhead passes the last written sample. write() never blocks; flush() discards
 * everything queued (no onDrained for flushed audio). Thread-safe; listener callbacks are delivered in order
 * from a single [scope] coroutine. Stand-in for the AudioTrack Player until M5.
 */
class VirtualSink(private val scope: CoroutineScope) : PcmSink {
    private val lock = Any()
    @Volatile private var listener: PcmSinkListener? = null
    private var playing = false
    private var endNs = 0L
    private var gen = 0
    private var drainJob: Job? = null

    private class Ev(val gen: Int, val first: Boolean)
    private val events = Channel<Ev>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (e in events) {
                val stale = synchronized(lock) { e.gen != gen }
                if (stale) continue
                val l = listener ?: continue
                if (e.first) l.onFirstAudible() else l.onDrained()
            }
        }
    }

    override fun write(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val durNs = pcm.size * 1_000_000_000L / PcmFormat.SAMPLE_RATE
        synchronized(lock) {
            val now = System.nanoTime()
            if (playing && endNs <= now) {
                // The playhead already passed the last sample: drained, then a new utterance starts.
                events.trySend(Ev(gen, false))
                playing = false
            }
            if (!playing) {
                playing = true
                endNs = now + durNs
                events.trySend(Ev(gen, true))
                startDrainWatcher(gen)
            } else {
                endNs += durNs
            }
        }
    }

    private fun startDrainWatcher(forGen: Int) {
        drainJob?.cancel()
        drainJob = scope.launch {
            while (true) {
                val remainingNs = synchronized(lock) {
                    if (gen != forGen || !playing) return@launch
                    endNs - System.nanoTime()
                }
                if (remainingNs <= 0) {
                    synchronized(lock) {
                        if (gen == forGen && playing && endNs <= System.nanoTime()) {
                            playing = false
                            events.trySend(Ev(gen, false))
                        }
                    }
                    return@launch
                }
                delay((remainingNs + 999_999) / 1_000_000)
            }
        }
    }

    override fun flush() {
        synchronized(lock) {
            gen++
            playing = false
            endNs = 0
            drainJob?.cancel()
            drainJob = null
        }
    }

    override fun setListener(listener: PcmSinkListener?) { this.listener = listener }
}
