package dev.timlock.jarvis.transport.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SimVoiceTest {
    @Test fun babbleHasSignalAndPauses() {
        val v = SimVoice(1)
        val frame = ShortArray(960)
        var peak = 0
        var sumSq = 0.0
        var silentFrames = 0
        val n = 25 * 4 // 4 s of 40 ms frames
        for (f in 0 until n) {
            v.fill(frame)
            var allZero = true
            for (s in frame) {
                if (s.toInt() != 0) allZero = false
                peak = maxOf(peak, abs(s.toInt().coerceAtLeast(-32767))); sumSq += s.toDouble() * s
            }
            if (allZero) silentFrames++
        }
        val rms = sqrt(sumSq / (n * 960))
        assertTrue("peak $peak", peak in 1500..32767)
        assertTrue("rms $rms", rms in 200.0..12000.0)
        assertTrue("expected some pause frames, got $silentFrames", silentFrames >= 1)
    }

    @Test fun deterministicPerSeed() {
        val a = ShortArray(960); val b = ShortArray(960)
        SimVoice(7).fill(a); SimVoice(7).fill(b)
        assertTrue(a.contentEquals(b))
    }

    @Test fun anyBufferLength() {
        val v = SimVoice(3)
        v.fill(ShortArray(1)); v.fill(ShortArray(0)); v.fill(ShortArray(5000))
        assertEquals(24_000, SimVoice.RATE)
    }
}
