package dev.timlock.jarvis.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.transport.PcmSink
import dev.timlock.jarvis.core.transport.PcmSinkListener
import kotlin.math.exp

private const val TAG = "JARVIS_STORE"

/**
 * Speaker (DESIGN §4.3 step 6, §6.4). write() enqueues into a 5 s ring and never blocks; the `audio-out` thread
 * feeds a streaming 24 kHz mono AudioTrack (USAGE_ASSISTANT / SPEECH, ~120 ms buffer) with non-blocking writes,
 * analyzing each chunk with a [LevelAnalyzer] and storing the level frames keyed by sample position. Each tick
 * the frame under the playback head (AudioTrack.getTimestamp; fallback: write position - estimated latency) is
 * published to `bus.out`, so visuals line up with the sound (Q3). flush() = pause()+flush()+play() and drops the ring.
 *
 * onFirstAudible fires when the head passes the first sample of an utterance (after idle); onDrained when the
 * head reaches the last written sample. Callbacks run on the audio-out thread.
 */
class Player(private val bus: AudioVisualBus) : PcmSink {
    private val lock = java.lang.Object()
    @Volatile private var listener: PcmSinkListener? = null

    // ring of queued (not yet handed to the track) samples
    private val ring = ShortArray(RING_SAMPLES)
    private var rHead = 0
    private var rCount = 0
    private var epoch = 0
    private var released = false
    private var thread: Thread? = null
    private var overflowLogged = false

    // out-thread state (guarded by lock where shared with flush())
    private var track: AudioTrack? = null
    private var lastAcceptNs = 0L
    private var accepted = 0L            // samples accepted by the track since the last flush
    private var latencyFrames = LATENCY_FRAMES
    private val analyzer = LevelAnalyzer(RATE)
    private val levels = LevelFrameRing()
    private val tsHolder = AudioTimestamp()
    private val pubBands = FloatArray(LevelAnalyzer.BANDS)
    private var utter = Utter.IDLE
    private var firstPos = 0L
    private var decayEnv = 0f
    private var decayActive = false
    private var lastOnsets = 0
    private var lastStrength = 0f

    private enum class Utter { IDLE, PENDING, PLAYING }

    override fun setListener(listener: PcmSinkListener?) { this.listener = listener }

    /** Milliseconds of audio queued in the ring (not yet given to the track). */
    fun queuedMs(): Long = synchronized(lock) { rCount * 1000L / RATE }

    override fun write(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            if (released) return
            var n = pcm.size
            val space = RING_SAMPLES - rCount
            if (n > space) {
                if (!overflowLogged) { overflowLogged = true; Log.w(TAG, "player_ring_overflow") }
                n = space
            }
            var w = (rHead + rCount) % RING_SAMPLES
            for (i in 0 until n) { ring[w] = pcm[i]; w = if (w + 1 == RING_SAMPLES) 0 else w + 1 }
            rCount += n
            if (thread == null) {
                val t = Thread(::outLoop, "audio-out")
                thread = t
                t.start()
            }
            lock.notifyAll()
        }
    }

    override fun flush() {
        synchronized(lock) {
            rHead = 0; rCount = 0
            epoch++
            overflowLogged = false
            // Bookkeeping resets now; the out thread re-syncs on its next tick (epoch check).
            accepted = 0L
            levels.clear()
            utter = Utter.IDLE
            decayActive = true
            val t = track
            if (t != null) {
                try { t.pause(); t.flush(); t.play() } catch (e: Throwable) { Log.w(TAG, "player_flush_failed") }
            }
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
    }

    // ---- audio-out thread ---------------------------------------------------------------------

    private fun createTrack(): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val target = LATENCY_FRAMES * 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, target))
            .build()
        latencyFrames = t.bufferSizeInFrames
        t.play()
        t
    } catch (e: Throwable) {
        Log.w(TAG, "player_track_failed ${e.javaClass.simpleName}")
        null
    }

    private fun outLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
        val chunk = ShortArray(CHUNK)
        var chunkLen = 0
        var chunkOff = 0
        var chunkEpoch = 0
        try {
            while (true) {
                // 1) take a chunk if none pending
                synchronized(lock) {
                    if (released) return
                    if (chunkLen > 0 && chunkEpoch != epoch) { chunkLen = 0; chunkOff = 0 } // flushed while pending
                    if (chunkLen == 0 && rCount > 0) {
                        val n = minOf(CHUNK, rCount)
                        var r = rHead
                        for (i in 0 until n) { chunk[i] = ring[r]; r = if (r + 1 == RING_SAMPLES) 0 else r + 1 }
                        rHead = r; rCount -= n
                        chunkLen = n; chunkOff = 0; chunkEpoch = epoch
                        analyzer.process(chunk, 0, n)
                        levels.append(accepted, n, analyzer)
                        if (utter == Utter.IDLE) { utter = Utter.PENDING; firstPos = accepted; decayActive = false }
                    }
                }
                // 2) hand it to the track (non-blocking)
                var wrote = 0
                if (chunkLen > 0) {
                    val t = track ?: createTrack().also { track = it }
                    if (t == null) { // no audio device: pretend it played so the state machine completes
                        synchronized(lock) { if (chunkEpoch == epoch) { accepted += chunkLen; lastAcceptNs = System.nanoTime() } }
                        chunkLen = 0
                    } else {
                        wrote = t.write(chunk, chunkOff, chunkLen, AudioTrack.WRITE_NON_BLOCKING)
                        if (wrote < 0) { Log.w(TAG, "player_write_error $wrote"); wrote = chunkLen }
                        synchronized(lock) {
                            if (chunkEpoch == epoch) {
                                accepted += wrote
                                lastAcceptNs = System.nanoTime()
                            } else {
                                // flush() raced this write: the stale samples may sit in the track, purge them.
                                try { t.pause(); t.flush(); t.play() } catch (_: Throwable) {}
                            }
                        }
                        chunkOff += wrote; chunkLen -= wrote
                    }
                }
                // 3) publish what is under the playback head, fire callbacks
                publishTick()
                // 4) wait
                synchronized(lock) {
                    if (released) return
                    val busy = chunkLen > 0 || rCount > 0 || utter != Utter.IDLE || decayActive
                    if (!busy) lock.wait()
                    else if (chunkLen == 0 && rCount > 0) Unit
                    else lock.wait(if (chunkLen > 0 && wrote == 0) 5L else TICK_MS)
                }
            }
        } finally {
            val t = track
            track = null
            try { t?.stop() } catch (_: Throwable) {}
            t?.release()
            synchronized(lock) { thread = null }
        }
    }

    private var lastPubNs = 0L

    private fun publishTick() {
        var fire1 = false
        var fire2 = false
        var head: Long
        synchronized(lock) {
            head = headPosition()
            if (utter == Utter.PENDING && head > firstPos) { utter = Utter.PLAYING; fire1 = true }
            if (utter != Utter.IDLE && rCount == 0 && head >= accepted && accepted > 0) {
                if (utter == Utter.PENDING) fire1 = true
                utter = Utter.IDLE
                fire2 = true
                decayActive = true
                decayEnv = levelEnvAt(head)
            }
        }
        // Throttle publishing to ~100 Hz max.
        val now = System.nanoTime()
        val doPublish = now - lastPubNs >= 8_000_000L
        if (doPublish) {
            lastPubNs = now
            synchronized(lock) {
                if (utter != Utter.IDLE) {
                    val i = levels.lookup(head)
                    if (i >= 0) {
                        levels.bandsInto(i, pubBands)
                        lastOnsets = levels.onsetsAt(i); lastStrength = levels.strengthAt(i)
                        decayEnv = levels.envAt(i)
                        bus.out.publish(decayEnv, lastOnsets, pubBands, lastStrength)
                    }
                } else if (decayActive) {
                    // Nothing under the head: relax the last frame towards 0 (release 180 ms), then stop publishing.
                    val k = exp(-8f / LevelAnalyzer.RELEASE_MS)
                    decayEnv *= k
                    for (b in pubBands.indices) pubBands[b] *= k
                    if (decayEnv < 0.002f) { decayEnv = 0f; java.util.Arrays.fill(pubBands, 0f); decayActive = false }
                    bus.out.publish(decayEnv, lastOnsets, pubBands, 0f)
                }
            }
        }
        val l = listener
        if (l != null) {
            try {
                if (fire1) l.onFirstAudible()
                if (fire2) l.onDrained()
            } catch (e: Throwable) {
                Log.w(TAG, "player_listener_error ${e.javaClass.simpleName}")
            }
        }
    }

    private fun levelEnvAt(head: Long): Float {
        val i = levels.lookup(head)
        return if (i >= 0) levels.envAt(i) else 0f
    }

    /** Frames played since the last flush: getTimestamp extrapolated to now, else written - estimated latency. */
    private fun headPosition(): Long {
        val t = track ?: return accepted
        val ok = try { t.getTimestamp(tsHolder) } catch (_: Throwable) { false }
        if (ok && tsHolder.framePosition <= accepted) {
            val dtNs = System.nanoTime() - tsHolder.nanoTime
            val h = tsHolder.framePosition + (dtNs * RATE / 1_000_000_000L).coerceAtLeast(0L)
            return h.coerceAtMost(accepted)
        }
        // No usable timestamp: all audio handed over and the estimated latency has elapsed -> fully played.
        if (rCount == 0 && System.nanoTime() - lastAcceptNs >= latencyFrames * 1_000_000_000L / RATE) return accepted
        return LevelFrameRing.estimateHead(accepted, latencyFrames)
    }

    companion object {
        const val RATE = 24_000
        private const val RING_SAMPLES = 5 * RATE
        private const val CHUNK = 480
        private const val TICK_MS = 10L
        private const val LATENCY_FRAMES = RATE * 120 / 1000
    }
}
