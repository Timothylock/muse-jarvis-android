package dev.timlock.jarvis.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 48 kHz -> 24 kHz: 31-tap Hamming-windowed-sinc low-pass (cutoff ~10.5 kHz, unity DC gain), keep every 2nd
 * output. Streaming (keeps history and phase across calls), allocation-free after construction.
 */
class Decimator2to1 {
    private val hist = DoubleArray(TAPS)
    private var pos = 0
    private var phase = 0

    /** Consumes [n] samples of [input]; writes ceil/floor(n/2) samples to [out] (size >= n/2 + 1). Returns the count written. */
    fun process(input: ShortArray, n: Int, out: ShortArray): Int {
        var o = 0
        for (k in 0 until n) {
            hist[pos] = input[k].toDouble()
            pos = (pos + 1) % TAPS
            if (phase == 0) {
                var acc = 0.0
                // newest sample is at pos-1
                var idx = pos
                for (t in 0 until TAPS) {
                    idx = if (idx == 0) TAPS - 1 else idx - 1
                    acc += COEF[t] * hist[idx]
                }
                out[o++] = acc.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            phase = phase xor 1
        }
        return o
    }

    companion object {
        const val TAPS = 31
        val COEF: DoubleArray = DoubleArray(TAPS).also { c ->
            val fc = 10_500.0 / 48_000.0 // cycles/sample
            val m = (TAPS - 1) / 2
            var sum = 0.0
            for (i in 0 until TAPS) {
                val x = (i - m).toDouble()
                val sinc = if (i == m) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val w = 0.54 - 0.46 * cos(2 * PI * i / (TAPS - 1))
                c[i] = sinc * w
                sum += c[i]
            }
            for (i in 0 until TAPS) c[i] /= sum
        }
    }
}
