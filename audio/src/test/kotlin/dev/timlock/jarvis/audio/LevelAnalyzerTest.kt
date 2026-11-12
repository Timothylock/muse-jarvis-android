package dev.timlock.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelAnalyzerTest {
    private val rate = 24_000
    private val block = 480

    /** Sine at [hz], amplitude as linear fraction of full scale; fills one 20 ms block continuing phase via [t0]. */
    private fun tone(hz: Double, amp: Double, t0: Int, out: ShortArray = ShortArray(block)): ShortArray {
        for (i in out.indices) out[i] = (sin(2 * PI * hz * (t0 + i) / rate) * amp * 32767).toInt().toShort()
        return out
    }

    private fun runTone(a: LevelAnalyzer, hz: Double, amp: Double, blocks: Int, startBlock: Int = 0) {
        val b = ShortArray(block)
        for (k in 0 until blocks) { tone(hz, amp, (startBlock + k) * block, b); a.process(b, 0, block) }
    }

    @Test fun silenceGivesZero() {
        val a = LevelAnalyzer()
        val z = ShortArray(block)
        repeat(50) { a.process(z, 0, block) }
        assertEquals(0f, a.env, 1e-4f)
        assertEquals(0, a.onsetCount)
        for (b in a.bands) assertEquals(0f, b, 1e-4f)
    }

    @Test fun attackAndRelease() {
        val a = LevelAnalyzer()
        // 1 kHz at -6 dBFS peak (rms ~ -9 dBFS) is above ceil (-12): target 1.0
        runTone(a, 1000.0, 0.5, 2) // 40 ms
        val afterAttack = a.env
        // one-pole attack tau 30 ms: after 40 ms ~ 1 - exp(-40/30) = 0.74 (block-quantized)
        assertTrue("attack env=$afterAttack", afterAttack in 0.6f..0.85f)
        runTone(a, 1000.0, 0.5, 30, 2)
        assertTrue("saturated ${a.env}", a.env > 0.97f)
        val z = ShortArray(block)
        repeat(9) { a.process(z, 0, block) } // 180 ms = one release tau -> ~ 0.37
        assertTrue("release env=${a.env}", a.env in 0.30f..0.45f)
        repeat(40) { a.process(z, 0, block) }
        assertTrue(a.env < 0.01f)
    }

    @Test fun onsetFiresOnStressedBurstAndRespectsSpacing() {
        val a = LevelAnalyzer()
        val z = ShortArray(block)
        repeat(10) { a.process(z, 0, block) }
        runTone(a, 1000.0, 0.5, 5)
        assertEquals(1, a.onsetCount)
        assertTrue(a.onsetStrength in 0f..1f)
        // Rapid re-bursts every 100 ms (silence 4 blocks, burst 1 block): spacing rule keeps count small.
        var t = 5
        repeat(6) {
            repeat(4) { a.process(z, 0, block) }
            runTone(a, 1000.0, 0.5, 1, t); t += 1
        }
        // 6 re-bursts over ~600 ms can add at most ceil(600/220) = 3 onsets
        assertTrue("onsets=${a.onsetCount}", a.onsetCount <= 4)
        // A steady tone re-fires only while env - env_slow stays > 0.3 (about 220 ms and 440 ms in), then stops.
        val a2 = LevelAnalyzer()
        runTone(a2, 1000.0, 0.5, 100)
        assertTrue("steady onsets=${a2.onsetCount}", a2.onsetCount in 1..3)
    }

    @Test fun weakBurstDoesNotFireOnset() {
        val a = LevelAnalyzer()
        // ~ -45 dBFS rms -> normalized ~0.23 < 0.3
        runTone(a, 1000.0, 0.01, 40)
        assertEquals(0, a.onsetCount)
    }

    @Test fun onsetsSpacedAtLeast220ms() {
        val a = LevelAnalyzer()
        val z = ShortArray(block)
        val times = ArrayList<Int>()
        var last = 0
        var t = 0
        // bursts of 100 ms every 300 ms for 3 s
        for (cycle in 0 until 10) {
            for (k in 0 until 5) { val b = tone(1000.0, 0.5, t * block); a.process(b, 0, block); t++; if (a.onsetCount != last) { times += t; last = a.onsetCount } }
            for (k in 0 until 10) { a.process(z, 0, block); t++; if (a.onsetCount != last) { times += t; last = a.onsetCount } }
        }
        assertTrue("onsets=${times.size}", times.size >= 5)
        for (i in 1 until times.size) assertTrue("gap ${(times[i] - times[i - 1]) * 20} ms", (times[i] - times[i - 1]) * 20 >= 220)
    }

    @Test fun bandSeparation() {
        val low = LevelAnalyzer(); runTone(low, 300.0, 0.3, 60)
        val high = LevelAnalyzer(); runTone(high, 4000.0, 0.3, 60)
        val lowIdx = argMax(low.bands)
        val highIdx = argMax(high.bands)
        assertTrue("low=$lowIdx high=$highIdx", lowIdx < highIdx)
        assertTrue(low.bands[1] + low.bands[2] > low.bands[6] + low.bands[7] + 0.5f)
        assertTrue(high.bands[6] + high.bands[7] > high.bands[1] + high.bands[2] + 0.5f)
    }

    @Test fun micFloorCalibratesFromIdleNoise() {
        val a = LevelAnalyzer(calibrateFloor = true)
        // idle room noise at ~ -42 dBFS rms
        val b = ShortArray(block)
        var seed = 12345L
        repeat(300) {
            for (i in b.indices) { seed = seed * 6364136223846793005L + 1442695040888963407L; b[i] = (((seed ushr 33).toInt() % 2001 - 1000) * 0.4).toInt().toShort() }
            a.process(b, 0, block, idle = true)
        }
        assertTrue("floor=${a.floorDb}", a.floorDb > -50.0 && a.floorDb <= -30.0)
        // And that same noise now reads near zero.
        assertTrue("env=${a.env}", a.env < 0.15f)
    }

    private fun argMax(f: FloatArray): Int { var m = 0; for (i in f.indices) if (f[i] > f[m]) m = i; return m }
}
