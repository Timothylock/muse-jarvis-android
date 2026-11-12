package dev.timlock.jarvis.audio

/** Mono PCM16 decoded from a WAV file. */
class WavData(val sampleRate: Int, val channels: Int, val pcm: ShortArray) {
    val durationMs: Long get() = pcm.size * 1000L / sampleRate
}

/** Minimal RIFF/WAVE parser (PCM16 and IEEE float32, 1-2 channels). Pure Kotlin. */
object WavReader {
    private const val FMT_PCM = 1
    private const val FMT_FLOAT = 3
    private const val FMT_EXTENSIBLE = 0xFFFE

    fun parse(bytes: ByteArray): WavData {
        require(bytes.size >= 12) { "wav: too short" }
        require(tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "wav: not a RIFF/WAVE file" }
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                require(size >= 16 && body + 16 <= bytes.size) { "wav: bad fmt chunk" }
                format = le16(bytes, body)
                channels = le16(bytes, body + 2)
                rate = le32(bytes, body + 4)
                bits = le16(bytes, body + 14)
                if (format == FMT_EXTENSIBLE && size >= 26 && body + 26 <= bytes.size) format = le16(bytes, body + 24)
            } else if (id == "data") {
                dataOff = body
                // A streaming writer may leave the size 0 or oversized: clamp to what is there.
                dataLen = if (size < 0 || size > bytes.size - body) bytes.size - body else size
                break
            }
            if (size < 0) throw IllegalArgumentException("wav: bad chunk size")
            val next = body.toLong() + size + (size and 1)
            if (next > bytes.size) break
            pos = next.toInt()
        }
        require(format != -1) { "wav: no fmt chunk" }
        require(dataOff >= 0) { "wav: no data chunk" }
        require(channels == 1 || channels == 2) { "wav: unsupported channel count $channels (need 1 or 2)" }
        require(rate in 4_000..192_000) { "wav: unsupported sample rate $rate" }
        val bytesPer = when {
            format == FMT_PCM && bits == 16 -> 2
            format == FMT_FLOAT && bits == 32 -> 4
            else -> throw IllegalArgumentException("wav: unsupported format (need PCM16 or float32, got format=$format bits=$bits)")
        }
        val frames = dataLen / (bytesPer * channels)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val o = dataOff + (i * channels + c) * bytesPer
                acc += if (bytesPer == 2) {
                    ((bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)).toShort().toFloat()
                } else {
                    java.lang.Float.intBitsToFloat(le32(bytes, o)).coerceIn(-1f, 1f) * 32767f
                }
            }
            out[i] = Math.round(acc / channels).coerceIn(-32768, 32767).toShort()
        }
        return WavData(rate, channels, out)
    }

    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

/** Sample-rate conversion to the 24 kHz wire rate. */
object Resample {
    const val TARGET = 24_000

    fun to24k(pcm: ShortArray, fromRate: Int): ShortArray {
        require(fromRate > 0) { "wav: bad sample rate" }
        if (fromRate == TARGET) return pcm
        var src = pcm
        if (fromRate > TARGET) src = lowPass(pcm, fromRate.toDouble() / TARGET)
        val outLen = (pcm.size.toLong() * TARGET / fromRate).toInt()
        val out = ShortArray(outLen)
        val step = fromRate.toDouble() / TARGET
        for (i in 0 until outLen) {
            val p = i * step
            val i0 = p.toInt()
            val f = p - i0
            val a = src[minOf(i0, src.size - 1)].toInt()
            val b = src[minOf(i0 + 1, src.size - 1)].toInt()
            out[i] = (a + (b - a) * f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Centered moving average ("box") of ~[ratio] samples, applied twice (triangular): cheap anti-alias for speech. */
    private fun lowPass(pcm: ShortArray, ratio: Double): ShortArray {
        val w = maxOf(2, Math.round(ratio).toInt())
        return boxPass(boxPass(pcm, w), w)
    }

    private fun boxPass(x: ShortArray, w: Int): ShortArray {
        val n = x.size
        val out = ShortArray(n)
        val half = w / 2
        var sum = 0L
        var lo = 0   // window [lo, hi)
        var hi = 0
        for (i in 0 until n) {
            val wantLo = maxOf(0, i - half)
            val wantHi = minOf(n, i - half + w)
            while (hi < wantHi) { sum += x[hi]; hi++ }
            while (lo < wantLo) { sum -= x[lo]; lo++ }
            out[i] = (sum / (hi - lo)).toInt().toShort()
        }
        return out
    }
}
