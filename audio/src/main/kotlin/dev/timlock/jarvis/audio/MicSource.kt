package dev.timlock.jarvis.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.transport.PcmSource
import kotlinx.coroutines.delay

private const val TAG = "JARVIS_STORE"

/**
 * Mic capture (DESIGN §4.3 steps 1/4, §6.4). One long-lived instance; the frame hand-out state machine is the pure
 * [MicFrameQueue]. Each call gets its own stream via [openStream] (EOF when [endStream]/[stop] is called).
 *
 * AudioRecord: VOICE_COMMUNICATION, 24 kHz mono PCM16 (48 kHz + 31-tap FIR 2:1 decimation if 24 kHz won't init),
 * 20 ms reads on the `audio-in` thread (URGENT_AUDIO). Mic levels go to `bus.mic` every block. [stopAfter] keeps
 * the recorder open for the tail so a fast re-press does not re-init. Without RECORD_AUDIO nothing is captured:
 * frames are silence and `mic_permission_missing` is logged (and [onPermissionMissing] fires); never throws.
 */
class MicSource(
    private val context: Context,
    private val bus: AudioVisualBus,
    private val onPermissionMissing: () -> Unit = {},
) : PcmSource {
    private val queue = MicFrameQueue()
    private val analyzer = LevelAnalyzer(PCM_RATE, calibrateFloor = true)
    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var stopAtNs = 0L          // 0 = no stop scheduled
    @Volatile private var hardStop = false
    @Volatile var recording = false
        private set

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** MicStart: capture to backlog (opens the recorder unless the tail kept it open). */
    fun start() {
        queue.start()
        if (!hasPermission()) {
            Log.w(TAG, "mic_permission_missing")
            onPermissionMissing()
            return
        }
        synchronized(lock) {
            stopAtNs = 0L
            hardStop = false
            if (thread == null) {
                val t = Thread(::captureLoop, "audio-in")
                thread = t
                t.start()
            }
        }
    }

    /** MicLive: backlog at real-time pace, then live frames. */
    fun live() = queue.live()

    /** MicSilence: leftovers, then paced zero frames. */
    fun silence() = queue.silence()

    /** MicStopAfter: close the recorder after [delayMs] unless [start] is called again. */
    fun stopAfter(delayMs: Long) {
        stopAtNs = System.nanoTime() + delayMs * 1_000_000L
    }

    /** MicStop: close the recorder now and EOF the current call stream. */
    fun stop() {
        hardStop = true
        queue.stopped()
        queue.endStream()
    }

    /** EOF for the current call's stream without touching capture state. */
    fun endStream() = queue.endStream()

    /** Audio captured since the last [start], <= 60 s (fallback path). Survives [stop]/[silence]; reset only by the next [start]. */
    fun takeUtterance(): ShortArray = queue.takeUtterance()

    /** A [PcmSource] for one call's handle ([attachMic]); EOF once the call ends. */
    fun openStream(): PcmSource {
        val g = queue.openStream()
        return object : PcmSource {
            override suspend fun nextFrame(): ShortArray? = pull(g)
        }
    }

    private var defaultStream: PcmSource? = null

    /** Convenience for direct use: pulls from a lazily opened stream. Call handles should use [openStream]. */
    override suspend fun nextFrame(): ShortArray? =
        (defaultStream ?: openStream().also { defaultStream = it }).nextFrame()

    private suspend fun pull(g: Int): ShortArray? {
        while (true) {
            if (queue.streamEnded(g)) return null
            val now = System.nanoTime() / 1_000_000L
            queue.poll(g, now)?.let { return it }
            delay(queue.waitMs(now))
        }
    }

    // ---- audio-in thread ---------------------------------------------------------------------

    private fun open(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val readBytes = rate / 50 * 2
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, readBytes * 4),
            )
        } catch (t: Throwable) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        return rec
    }

    private fun captureLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
        var rate = PCM_RATE
        var rec = open(rate)
        if (rec == null) { rate = 2 * PCM_RATE; rec = open(rate) }
        if (rec == null) {
            Log.w(TAG, "mic_init_failed")
            synchronized(lock) { if (thread === Thread.currentThread()) thread = null }
            return
        }
        try {
            rec.startRecording()
            recording = true
            val raw = ShortArray(rate / 50)
            val block = ShortArray(BLOCK)
            val decim = if (rate != PCM_RATE) Decimator2to1() else null
            while (!shouldExit()) {
                var got = 0
                while (got < raw.size && !hardStop) {
                    val r = rec.read(raw, got, raw.size - got)
                    if (r <= 0) { got = -1; break }
                    got += r
                }
                if (got < 0) { Log.w(TAG, "mic_read_failed"); break }
                if (got < raw.size) break
                val src: ShortArray
                if (decim != null) { decim.process(raw, raw.size, block); src = block } else src = raw
                val m = queue.currentMode
                analyzer.process(src, 0, BLOCK, idle = m == MicFrameQueue.Mode.SILENCE || m == MicFrameQueue.Mode.STOPPED)
                bus.mic.publish(analyzer.env, analyzer.onsetCount, analyzer.bands, analyzer.onsetStrength)
                queue.onCaptured(src, 0, BLOCK)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mic_capture_error ${t.javaClass.simpleName}")
        } finally {
            recording = false
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            synchronized(lock) { if (thread === Thread.currentThread()) thread = null }
            val m = queue.currentMode
            if (m != MicFrameQueue.Mode.BACKLOG && m != MicFrameQueue.Mode.LIVE) queue.stopped()
            analyzer.reset()
            bus.mic.publish(0f, analyzer.onsetCount, analyzer.bands, 0f)
        }
    }

    /** Atomic with [start]'s spawn decision: once true, [thread] is already cleared so a new start() spawns afresh. */
    private fun shouldExit(): Boolean = synchronized(lock) {
        val stopAt = stopAtNs
        val ex = hardStop || (stopAt != 0L && System.nanoTime() >= stopAt)
        if (ex && thread === Thread.currentThread()) thread = null
        ex
    }

    private companion object {
        const val PCM_RATE = 24_000
        const val BLOCK = 480
    }
}
