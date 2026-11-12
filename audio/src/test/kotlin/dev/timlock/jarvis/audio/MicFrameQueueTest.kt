package dev.timlock.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicFrameQueueTest {
    private fun block(v: Int, n: Int = 480) = ShortArray(n) { v.toShort() }

    @Test fun backlogHandedOutOnlyAfterLive_inOrder_thenLive() {
        val q = MicFrameQueue()
        val g = q.openStream()
        q.start()
        // 3 backlog frames of distinct values
        for (v in 1..6) q.onCaptured(block(v), 0, 480)
        assertNull("nothing while in backlog mode", q.poll(g, 0))
        q.live()
        val f1 = q.poll(g, 0)!!
        assertEquals(1, f1[0].toInt()); assertEquals(2, f1[959].toInt())
        // paced: not due again until 40 ms later
        assertNull(q.poll(g, 10))
        val f2 = q.poll(g, 40)!!
        assertEquals(3, f2[0].toInt())
        assertNotNull(q.poll(g, 80))
        // drained: live frame arrives only when 960 samples are there
        assertNull(q.poll(g, 120))
        q.onCaptured(block(9), 0, 480)
        assertNull(q.poll(g, 130))
        q.onCaptured(block(9), 0, 480)
        assertEquals(9, q.poll(g, 140)!![0].toInt())
    }

    @Test fun silenceFlushesLeftoverThenPacedZerosNeverEof() {
        val q = MicFrameQueue()
        val g = q.openStream()
        q.start(); q.live()
        q.onCaptured(block(7), 0, 480)          // half a frame
        q.onCaptured(block(7), 0, 480)
        q.onCaptured(block(5), 0, 480)          // partial tail
        q.silence()
        q.onCaptured(block(99), 0, 480)         // captured during the tail: discarded
        val a = q.poll(g, 0)!!
        assertEquals(7, a[0].toInt()); assertEquals(7, a[959].toInt())
        val b = q.poll(g, 40)!!                  // padded leftover
        assertEquals(5, b[0].toInt()); assertEquals(5, b[479].toInt()); assertEquals(0, b[480].toInt())
        var t = 80L
        repeat(20) {
            assertNull("paced", q.poll(g, t - 1))
            val z = q.poll(g, t)!!
            assertEquals(960, z.size)
            assertTrue(z.all { it.toInt() == 0 })
            assertFalse(q.streamEnded(g))
            t += 40
        }
    }

    @Test fun stoppedStillSendsZerosWhileStreamOpen() {
        val q = MicFrameQueue()
        val g = q.openStream()
        q.stopped()
        assertEquals(0, q.poll(g, 0)!![0].toInt())
        assertFalse(q.streamEnded(g))
    }

    @Test fun eofOnlyWhenEnded_andOldStreamsDie() {
        val q = MicFrameQueue()
        val g1 = q.openStream()
        q.endStream()
        assertTrue(q.streamEnded(g1))
        assertNull(q.poll(g1, 0))
        val g2 = q.openStream()
        assertFalse(q.streamEnded(g2))
        assertTrue("old stream stays dead", q.streamEnded(g1))
    }

    @Test fun ringCappedAtTenSecondsDroppingOldest() {
        val q = MicFrameQueue()
        q.start()
        for (i in 0 until 600) q.onCaptured(block(i % 100, 480), 0, 480) // 12 s
        assertEquals(240_000, q.ringSamples)
        // fallback utterance keeps all 12 s (cap 60 s)
        assertEquals(288_000, q.takeUtterance().size)
    }

    @Test fun utteranceCappedAt60sAndResetByStart() {
        val q = MicFrameQueue()
        q.start()
        for (i in 0 until 3200) q.onCaptured(block(1), 0, 480) // 64 s
        assertEquals(60 * 24_000, q.takeUtterance().size)
        q.start()
        assertEquals(0, q.takeUtterance().size)
        q.onCaptured(block(2), 0, 480)
        q.silence()
        q.onCaptured(block(3), 0, 480)
        assertEquals(480, q.takeUtterance().size)
    }

    @Test fun startKeepsQueuedAudioOnlyWithOpenStream() {
        val q = MicFrameQueue()
        q.start(); q.onCaptured(block(1), 0, 480)
        q.start()
        assertEquals("no consumer: stale audio dropped", 0, q.ringSamples)
        q.openStream()
        q.onCaptured(block(1), 0, 480)
        q.start()
        assertEquals(480, q.ringSamples)
    }
}

class MicFrameQueueUtteranceSurvivesStopTest {
    @Test fun utteranceSurvivesStopAndEndStream() {
        val q = MicFrameQueue()
        q.openStream(); q.start()
        q.onCaptured(ShortArray(480) { 4 }, 0, 480)
        q.stopped(); q.endStream()
        assertEquals(480, q.takeUtterance().size)
        assertEquals(480, q.takeUtterance().size)
    }
}
