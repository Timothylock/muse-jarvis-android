package dev.timlock.jarvis.transport.sdk

/** Pure PCM16 helpers for the Python boundary (the SDK speaks little-endian bytes). */
object PcmCodec {
    fun toLe(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** A trailing odd byte is dropped. */
    fun fromLe(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }
}

/** RIFF/WAVE writer: PCM16 mono, for the dictated-turn fallback (24 kHz, §4.3.1). */
object Wav {
    const val HEADER_BYTES = 44

    fun encode(pcm: ShortArray, sampleRate: Int = 24_000): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArray(HEADER_BYTES + dataLen)
        fun str(off: Int, s: String) { for (i in s.indices) out[off + i] = s[i].code.toByte() }
        fun le32(off: Int, v: Int) { for (i in 0..3) out[off + i] = (v shr (8 * i)).toByte() }
        fun le16(off: Int, v: Int) { out[off] = v.toByte(); out[off + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); le32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1 /* PCM */); le16(22, 1 /* mono */)
        le32(24, sampleRate); le32(28, sampleRate * 2); le16(32, 2); le16(34, 16)
        str(36, "data"); le32(40, dataLen)
        System.arraycopy(PcmCodec.toLe(pcm), 0, out, HEADER_BYTES, dataLen)
        return out
    }
}

/** Pure trimming helpers for the dictated-turn fallback (the SDK caps a voice note at 2 MiB of WAV). */
object PcmTrim {
    /**
     * Drops leading silence from 24 kHz mono PCM16: scans [windowMs] windows for the first one whose RMS reaches
     * [rmsThreshold] and keeps [leadInMs] before it. Returns [pcm] itself when nothing would be removed or no
     * voiced window exists (so an all-quiet clip is never emptied).
     */
    fun trimLeadingSilence(
        pcm: ShortArray,
        sampleRate: Int = 24_000,
        windowMs: Int = 20,
        leadInMs: Int = 200,
        rmsThreshold: Int = 300,
    ): ShortArray {
        val win = (sampleRate * windowMs / 1000).coerceAtLeast(1)
        var first = -1
        var start = 0
        while (start + win <= pcm.size) {
            var sum = 0.0
            for (i in start until start + win) {
                val v = pcm[i].toDouble()
                sum += v * v
            }
            if (Math.sqrt(sum / win) >= rmsThreshold) { first = start; break }
            start += win
        }
        if (first < 0) return pcm
        val from = (first - sampleRate * leadInMs / 1000).coerceAtLeast(0)
        return if (from == 0) pcm else pcm.copyOfRange(from, pcm.size)
    }
}
