package dev.timlock.jarvis.transport.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Babble synth (§7.1): white noise through three resonant bandpass (formant-like) filters, shaped by a 4-6 Hz
 * syllable envelope, in runs of syllables separated by 200-500 ms pauses. Clearly not a voice (no pitch). Level is moderate (peak about -12 dBFS) because it now plays audibly.
 * Allocation-free after construction. Not thread-safe: one instance per speech.
 */
class SimVoice(seed: Long = 0x5EEDL, private val rate: Int = RATE) {
    private var rng = seed xor 0x2545F4914F6CDD1DL
    // biquad bandpass (constant 0 dB peak) state: b0, b2, a1, a2 per formant; x1,x2,y1,y2
    private val b0 = DoubleArray(3)
    private val a1 = DoubleArray(3)
    private val a2 = DoubleArray(3)
    private val z1 = DoubleArray(3)
    private val z2 = DoubleArray(3)
    private var syllLeft = 0
    private var syllLen = 1
    private var runLeft = 0
    private var pauseLeft = 0
    private var gain = 0.0

    init { newSyllable(); runLeft = 3 + nextInt(5) }

    private fun nextLong(): Long {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
        rng = x
        return x
    }
    private fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()
    private fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    private fun setBandpass(i: Int, freq: Double, q: Double) {
        val w = 2 * PI * freq / rate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        b0[i] = alpha / a0
        a1[i] = -2 * cos(w) / a0
        a2[i] = (1 - alpha) / a0
    }

    private fun newSyllable() {
        val rateHz = 4.0 + nextDouble() * 2.0
        syllLen = max(1, (rate / rateHz).toInt())
        syllLeft = syllLen
        setBandpass(0, 300 + nextDouble() * 500, 6.0)
        setBandpass(1, 900 + nextDouble() * 1400, 8.0)
        setBandpass(2, 2400 + nextDouble() * 700, 10.0)
        gain = 0.6 + nextDouble() * 0.4
    }

    /** Fills [out] (any length, normally [dev.timlock.jarvis.core.transport.PcmFormat.FRAME_SAMPLES]) with babble. */
    fun fill(out: ShortArray) {
        for (n in out.indices) {
            if (pauseLeft > 0) {
                pauseLeft--
                out[n] = 0
                if (pauseLeft == 0) { newSyllable(); runLeft = 3 + nextInt(5) }
                continue
            }
            val noise = nextDouble() * 2 - 1
            out[n] = sampleFor(noise).toInt().toShort()
            if (--syllLeft <= 0) {
                if (--runLeft <= 0) {
                    pauseLeft = (rate * (0.2 + nextDouble() * 0.3)).toInt()
                } else newSyllable()
            }
        }
    }

    private fun sampleFor(noise: Double): Double {
        // phase 0..1 through the syllable; sin^2-ish bump
        val ph = 1.0 - syllLeft.toDouble() / syllLen
        val env = sin(PI * ph).let { it * it }
        var y = 0.0
        for (i in 0 until 3) {
            // direct form II transposed, bandpass b = [b0, 0, -b0]
            val o = b0[i] * noise + z1[i]
            z1[i] = z2[i] - a1[i] * o
            z2[i] = -b0[i] * noise - a2[i] * o
            y += o * (if (i == 0) 1.0 else if (i == 1) 0.8 else 0.5)
        }
        return (y * env * gain * 0.65 * 32767).coerceIn(-32768.0, 32767.0)
    }

    companion object { const val RATE = 24_000 }
}
