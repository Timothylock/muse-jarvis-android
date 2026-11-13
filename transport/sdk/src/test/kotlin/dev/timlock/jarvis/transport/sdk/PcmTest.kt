package dev.timlock.jarvis.transport.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test fun leRoundTrip() {
        val pcm = shortArrayOf(0, 1, -1, 255, 256, Short.MAX_VALUE, Short.MIN_VALUE, -12345)
        val le = PcmCodec.toLe(pcm)
        assertEquals(16, le.size)
        assertArrayEquals(pcm, PcmCodec.fromLe(le))
    }

    @Test fun leByteOrder() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), PcmCodec.toLe(shortArrayOf(0x1234, -1)))
        assertArrayEquals(shortArrayOf(0x1234), PcmCodec.fromLe(byteArrayOf(0x34, 0x12, 0x7F)))
    }

    @Test fun trimLeadingSilenceKeepsLeadIn() {
        // 24 kHz: 1 s of silence, then 0.5 s of loud tone.
        val silence = ShortArray(24_000)
        val voice = ShortArray(12_000) { (if (it % 2 == 0) 8000 else -8000).toShort() }
        val out = PcmTrim.trimLeadingSilence(silence + voice)
        // 200 ms lead-in (4800 samples) + the voiced part.
        assertEquals(4_800 + 12_000, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(8000, out[4_800].toInt())
    }

    @Test fun trimLeadingSilenceLeavesUntouchedClipsAlone() {
        val voiceFirst = ShortArray(24_000) { (if (it % 2 == 0) 8000 else -8000).toShort() }
        assertEquals(voiceFirst.size, PcmTrim.trimLeadingSilence(voiceFirst).size)
        val shortLead = ShortArray(2_000) + ShortArray(4_800) { 8000.toShort() }
        assertEquals(shortLead.size, PcmTrim.trimLeadingSilence(shortLead).size) // < 200 ms of lead-in: nothing cut
        val allQuiet = ShortArray(48_000)
        assertEquals(allQuiet.size, PcmTrim.trimLeadingSilence(allQuiet).size)
        assertEquals(0, PcmTrim.trimLeadingSilence(ShortArray(0)).size)
    }

    @Test fun wavHeader() {
        val wav = Wav.encode(shortArrayOf(1, 2, 3), 24_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        fun le32(o: Int) = (0..3).sumOf { (wav[o + it].toInt() and 0xFF) shl (8 * it) }
        fun le16(o: Int) = (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8)
        assertEquals(36 + 6, le32(4))
        assertEquals(16, le32(16)); assertEquals(1, le16(20)); assertEquals(1, le16(22))
        assertEquals(24_000, le32(24)); assertEquals(48_000, le32(28)); assertEquals(2, le16(32)); assertEquals(16, le16(34))
        assertEquals("data", String(wav, 36, 4)); assertEquals(6, le32(40))
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), wav.copyOfRange(44, 50))
    }
}
