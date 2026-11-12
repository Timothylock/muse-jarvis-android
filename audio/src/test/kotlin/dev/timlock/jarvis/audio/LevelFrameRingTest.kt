package dev.timlock.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelFrameRingTest {
    /** Feeds blocks of constant amplitude so frame k has a distinguishable onset/env history. */
    private fun fill(ring: LevelFrameRing, blocks: Int): LevelAnalyzer {
        val a = LevelAnalyzer()
        val loud = ShortArray(480) { if (it % 2 == 0) 12000 else -12000 }
        val quiet = ShortArray(480)
        for (k in 0 until blocks) {
            a.process(if (k % 2 == 0) loud else quiet, 0, 480)
            ring.append(k * 480L, 480, a)
        }
        return a
    }

    @Test fun lookupFindsFrameUnderHead() {
        val r = LevelFrameRing(16)
        fill(r, 10)
        val i = r.lookup(3 * 480L + 17)
        // the frame appended for block 3 has onsets >= those of block 2 and its own env
        val j = r.lookup(3 * 480L)
        assertEquals(i, j)
        val before = r.lookup(2 * 480L)
        assertTrue(before != i)
    }

    @Test fun headBeyondEndGivesNewest_beforeStartGivesOldest() {
        val r = LevelFrameRing(4)
        val a = fill(r, 10) // only blocks 6..9 retained
        val newest = r.lookup(1_000_000L)
        assertEquals(a.env, r.envAt(newest), 1e-6f)
        val oldest = r.lookup(0L)
        val out = FloatArray(8)
        r.bandsInto(oldest, out)
        assertEquals(r.lookup(6 * 480L), oldest)
    }

    @Test fun emptyAndClear() {
        val r = LevelFrameRing(4)
        assertEquals(-1, r.lookup(0))
        fill(r, 3); r.clear()
        assertEquals(-1, r.lookup(0))
    }

    @Test fun fallbackHeadEstimate() {
        assertEquals(0L, LevelFrameRing.estimateHead(1000, 2880))
        assertEquals(7120L, LevelFrameRing.estimateHead(10_000, 2880))
    }
}
