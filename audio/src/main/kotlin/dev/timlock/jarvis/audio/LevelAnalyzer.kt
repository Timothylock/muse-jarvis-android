package dev.timlock.jarvis.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DESIGN §3.3 pipeline for one channel, pure Kotlin (no android.*), allocation-free per block.
 *
 * Per block (normally 20 ms = 480 samples @24 kHz; any length works, time constants scale with the block length):
 * RMS -> dBFS -> normalize (floor -55 / ceil -12; mic floor auto-calibrates to the 10th percentile of the last 5 s
 * of idle dB + 6 dB) -> one-pole envelope (attack 30 ms, release 180 ms) -> env_slow (400 ms) -> onset rule (review
 * H13: env - env_slow > 0.3 and >= 220 ms since the last onset; strength = clamp((d - 0.3)/0.4, 0, 1)) -> 8-band
 * biquad bandpass bank (centres log-spaced 100 Hz..8 kHz), each band with its own envelope.
 * Band normalization uses floor -60 / ceil -20 dBFS because a band carries only a fraction of the total power.
 * Time for onset spacing is sample time (deterministic), not wall time.
 */
class LevelAnalyzer(private val sampleRate: Int = 24_000, private val calibrateFloor: Boolean = false) {
    var env = 0f; private set
    var envSlow = 0f; private set
    var onsetCount = 0; private set
    var onsetStrength = 0f; private set
    val bands = FloatArray(BANDS)
    /** Current normalization floor in dBFS (moves only when [calibrateFloor] and the block is idle). */
    var floorDb = FLOOR_DB; private set

    private var timeMs = 0.0
    private var lastOnsetMs = -1.0e9

    // biquad bank: coefficients and DF2T state per band
    private val b0 = DoubleArray(BANDS)
    private val a1 = DoubleArray(BANDS)
    private val a2 = DoubleArray(BANDS)
    private val z1 = DoubleArray(BANDS)
    private val z2 = DoubleArray(BANDS)
    private val bandSumSq = DoubleArray(BANDS)
    private val bandEnv = FloatArray(BANDS)

    // idle dB history for floor calibration
    private val hist = FloatArray(CAL_BLOCKS)
    private val scratch = FloatArray(CAL_BLOCKS)
    private var histCount = 0
    private var histPos = 0
    private var sinceCal = 0

    init {
        for (i in 0 until BANDS) {
            val f = bandCenterHz(i)
            val w = 2.0 * PI * f / sampleRate
            val alpha = sin(w) / (2.0 * BAND_Q)
            val a0 = 1.0 + alpha
            b0[i] = alpha / a0
            a1[i] = -2.0 * cos(w) / a0
            a2[i] = (1.0 - alpha) / a0
        }
    }

    /** Analyze [len] samples of [pcm] from [off]. [idle] = the channel is known to carry no wanted signal (mic calibration). */
    fun process(pcm: ShortArray, off: Int, len: Int, idle: Boolean = false) {
        if (len <= 0) return
        var sumSq = 0.0
        java.util.Arrays.fill(bandSumSq, 0.0)
        for (n in off until off + len) {
            val x = pcm[n] / 32768.0
            sumSq += x * x
            for (i in 0 until BANDS) {
                val y = b0[i] * x + z1[i]
                z1[i] = z2[i] - a1[i] * y
                z2[i] = -b0[i] * x - a2[i] * y
                bandSumSq[i] += y * y
            }
        }
        val dtMs = len * 1000.0 / sampleRate
        val db = toDb(sqrt(sumSq / len))
        if (calibrateFloor && idle) calibrate(db)
        val ceil = CEIL_DB
        val a = clamp01(((db - floorDb) / (ceil - floorDb)).toFloat())

        val dt = dtMs.toFloat()
        val prevSlow = envSlow
        env += (a - env) * (1f - exp(-dt / (if (a > env) ATTACK_MS else RELEASE_MS)))
        envSlow += (env - envSlow) * (1f - exp(-dt / SLOW_MS))
        timeMs += dtMs
        val d = env - prevSlow
        if (d > ONSET_THRESH && timeMs - lastOnsetMs >= ONSET_SPACING_MS) {
            onsetCount++
            lastOnsetMs = timeMs
            onsetStrength = clamp01((d - ONSET_THRESH) / 0.4f)
        }

        for (i in 0 until BANDS) {
            val bdb = toDb(sqrt(bandSumSq[i] / len))
            val ba = clamp01(((bdb - BAND_FLOOR_DB) / (BAND_CEIL_DB - BAND_FLOOR_DB)).toFloat())
            val e = bandEnv[i]
            bandEnv[i] = e + (ba - e) * (1f - exp(-dt / (if (ba > e) ATTACK_MS else RELEASE_MS)))
            bands[i] = bandEnv[i]
        }
    }

    private fun calibrate(db: Double) {
        hist[histPos] = db.toFloat()
        histPos = (histPos + 1) % CAL_BLOCKS
        if (histCount < CAL_BLOCKS) histCount++
        if (++sinceCal >= 10 && histCount >= 50) {
            sinceCal = 0
            System.arraycopy(hist, 0, scratch, 0, histCount)
            java.util.Arrays.sort(scratch, 0, histCount)
            val p10 = scratch[(histCount - 1) / 10]
            floorDb = (p10 + 6f).coerceIn(FLOOR_DB.toFloat(), MAX_FLOOR_DB.toFloat()).toDouble()
        }
    }

    fun reset() {
        env = 0f; envSlow = 0f; onsetCount = 0; onsetStrength = 0f; timeMs = 0.0; lastOnsetMs = -1.0e9
        java.util.Arrays.fill(z1, 0.0); java.util.Arrays.fill(z2, 0.0)
        java.util.Arrays.fill(bandEnv, 0f); java.util.Arrays.fill(bands, 0f)
    }

    companion object {
        const val BANDS = 8
        const val FLOOR_DB = -55.0
        const val CEIL_DB = -12.0
        const val MAX_FLOOR_DB = -30.0
        const val BAND_FLOOR_DB = -60.0
        const val BAND_CEIL_DB = -20.0
        const val ATTACK_MS = 30f
        const val RELEASE_MS = 180f
        const val SLOW_MS = 400f
        const val ONSET_THRESH = 0.3f
        const val ONSET_SPACING_MS = 220.0
        const val BAND_Q = 1.4
        /** 5 s of 20 ms blocks. */
        const val CAL_BLOCKS = 250

        fun bandCenterHz(i: Int): Double = 100.0 * (80.0).pow(i / 7.0)

        private fun toDb(rms: Double): Double = if (rms < 1e-6) -120.0 else 20.0 * log10(rms)
        private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
    }
}
