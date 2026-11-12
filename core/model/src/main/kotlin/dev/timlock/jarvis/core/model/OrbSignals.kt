package dev.timlock.jarvis.core.model

import java.util.concurrent.atomic.AtomicInteger

/**
 * Time base for everything the orb renderer reads: monotonic milliseconds,
 * `System.nanoTime() / 1_000_000` (CLOCK_MONOTONIC on Android). Never wall-clock.
 */
fun monoMillis(): Long = System.nanoTime() / 1_000_000L

/** Where/how big the orb is drawn (DESIGN §2.3, §2.4, §3.1 PAIRING, §4.1). */
enum class OrbLayout { CENTERED, DOCKED, PAIRING, FIRST_RUN }

/**
 * Immutable snapshot the store publishes for the GL thread (DESIGN §6.3).
 * The renderer interpolates visual parameters itself from [previous] -> [state]
 * starting at [sinceMs]; the store never ticks at frame rate.
 * All timestamps are [monoMillis]; 0 / null means "never" / "not applicable".
 */
data class OrbSnapshot(
    val state: OrbState = OrbState.BOOT,
    val previous: OrbState = OrbState.BOOT,
    val sinceMs: Long = 0L,
    val sim: Boolean = false,
    val layout: OrbLayout = OrbLayout.CENTERED,
    val layoutSinceMs: Long = 0L,
    /** Last panel arrival (DISPLAY_PULSE overlay: 300 ms emit pulse on L3/H). */
    val pulseAtMs: Long = 0L,
    /** Last ambient push (600 ms attention pulse on L4, §2.6). */
    val attentionAtMs: Long = 0L,
    /** STANDBY countdown arc on L1: [countdownStartMs, countdownEndMs]. */
    val countdownStartMs: Long = 0L,
    val countdownEndMs: Long = 0L,
    /** Tap-latched talk: solid mint ring (§4.3). */
    val latched: Boolean = false,
    /** Orb "shake" when PTT pressed while OFFLINE (§4.3 failures). */
    val shakeAtMs: Long = 0L,
    /** Night mode (§2.7): ×0.35 brightness, tier D, 20 fps. */
    val night: Boolean = false,
    /** Up to 3 leader lines, top panel first. Empty unless layout == DOCKED. */
    val leaders: List<LeaderAnchor> = emptyList(),
)

/**
 * GL leader line to one DOCK panel (review H4/C9/C15). [yPx] = design-px y (1340x800 canvas) of the panel's
 * left-edge midpoint at x = 625; the renderer eases y changes itself (panels slide 350 ms). Timeline per
 * [revealStartMs]: shoot from L1 toward x 625 at +250 ms over 180 ms (ease-out-expo, 6 px cyan.hot head dot),
 * then settle to 25% alpha over 2 s. [pulseAtMs] (in-place update): a 10 px dot travels orb->panel in 350 ms.
 */
data class LeaderAnchor(val yPx: Float, val revealStartMs: Long, val pulseAtMs: Long = 0L)

/** Written by the store (main thread), read once per frame by the GL thread. */
class OrbStateHolder {
    @Volatile
    var snapshot: OrbSnapshot = OrbSnapshot()
}

/**
 * Lock-free audio level channel (DESIGN §3.3 step 6). One writer (an audio thread),
 * one reader (GL thread). Double-buffered with a sequence number.
 * Layout of a frame: [0] = env (0..1), [1] = onset count (as float), [2..9] = 8 bands (0..1),
 * [10] = strength of the latest onset (0..1; review H13: echoes only on stressed onsets, alpha 0.25 + 0.5·strength).
 */
class AudioLevels {
    private val buffers = arrayOf(FloatArray(FRAME_SIZE), FloatArray(FRAME_SIZE))
    private val seq = AtomicInteger(0)

    /** Writer side. [bands] must have 8 entries. Allocation-free. */
    fun publish(env: Float, onsetCount: Int, bands: FloatArray, onsetStrength: Float = 1f) {
        val next = seq.get() + 1
        val buf = buffers[next and 1]
        buf[0] = env
        buf[1] = onsetCount.toFloat()
        for (i in 0 until BANDS) buf[2 + i] = bands[i]
        buf[2 + BANDS] = onsetStrength
        seq.set(next)
    }

    /** Reader side: copies the latest frame into [into] (size >= FRAME_SIZE); returns the sequence. */
    fun read(into: FloatArray): Int {
        val s = seq.get()
        buffers[s and 1].copyInto(into, 0, 0, FRAME_SIZE)
        return s
    }

    companion object {
        const val BANDS = 8
        const val FRAME_SIZE = 3 + BANDS
    }
}

/** Mic and playback levels. Kotlin audio threads write; the orb renderer reads (§3.3). */
class AudioVisualBus {
    val mic = AudioLevels()
    val out = AudioLevels()
}
