package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.transport.PcmSinkListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real-time test (the sink is wall-clock based): margins are generous. */
class VirtualSinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<String>()
    private val sink = VirtualSink(scope).also {
        it.setListener(object : PcmSinkListener {
            override fun onFirstAudible() { events += "first" }
            override fun onDrained() { events += "drained" }
        })
    }

    @After fun tearDown() { scope.cancel() }

    private fun frames(n: Int) = repeat(n) { sink.write(ShortArray(960)) } // 40 ms each

    @Test fun firstAudibleImmediatelyThenDrained() {
        frames(5) // 200 ms
        Thread.sleep(80)
        assertEquals(listOf("first"), events.toList())
        Thread.sleep(400)
        assertEquals(listOf("first", "drained"), events.toList())
    }

    @Test fun flushSuppressesDrained() {
        frames(10) // 400 ms
        Thread.sleep(60)
        sink.flush()
        Thread.sleep(600)
        assertEquals(listOf("first"), events.toList())
    }

    @Test fun newUtteranceAfterFlushFiresFirstAgain() {
        frames(10)
        Thread.sleep(50)
        sink.flush()
        frames(2)
        Thread.sleep(300)
        assertEquals(listOf("first", "first", "drained"), events.toList())
    }

    @Test fun continuousWritesStayOneUtterance() {
        repeat(6) { frames(1); Thread.sleep(30) } // written faster than played
        assertTrue(events.count { it == "first" } == 1)
        Thread.sleep(300)
        assertEquals(listOf("first", "drained"), events.toList())
    }
}
