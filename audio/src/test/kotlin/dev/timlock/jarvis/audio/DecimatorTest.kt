package dev.timlock.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DecimatorTest {
    private fun rms(a: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / (to - from))
    }

    private fun run(hz: Double): Pair<Double, Double> {
        val n = 9600
        val inp = ShortArray(n) { (sin(2 * PI * hz * it / 48_000) * 10000).toInt().toShort() }
        val out = ShortArray(n / 2 + 1)
        val d = Decimator2to1()
        val c = d.process(inp, n, out)
        assertEquals(n / 2, c)
        return rms(out, 200, c) to rms(inp, 400, n)
    }

    @Test fun halvesSampleCount() {
        val d = Decimator2to1()
        val out = ShortArray(10)
        assertEquals(480, d.process(ShortArray(960), 960, ShortArray(481)))
        assertEquals(1, d.process(ShortArray(1), 1, out) + d.process(ShortArray(1), 1, out))
    }

    @Test fun passbandKeepsLevel() {
        val (o, i) = run(1000.0)
        assertTrue("ratio ${o / i}", abs(o / i - 1.0) < 0.05)
        val (o2, i2) = run(6000.0)
        assertTrue("ratio ${o2 / i2}", abs(o2 / i2 - 1.0) < 0.15)
    }

    @Test fun stopbandAttenuated() {
        val (o, i) = run(18_000.0)
        assertTrue("ratio ${o / i}", o / i < 0.1)
    }

    @Test fun dcUnityGain() {
        val inp = ShortArray(600) { 5000 }
        val out = ShortArray(301)
        val c = Decimator2to1().process(inp, 600, out)
        assertEquals(5000.0, out[c - 1].toDouble(), 5.0)
    }

    @Test fun streamingMatchesOneShot() {
        val inp = ShortArray(1000) { (sin(2 * PI * 3000.0 * it / 48_000) * 8000).toInt().toShort() }
        val one = ShortArray(501); val n1 = Decimator2to1().process(inp, 1000, one)
        val d = Decimator2to1(); val a = ShortArray(400); val b = ShortArray(400)
        val na = d.process(inp, 333, a)
        val tail = inp.copyOfRange(333, 1000)
        val nb = d.process(tail, tail.size, b)
        assertEquals(n1, na + nb)
        val joined = a.copyOf(na) + b.copyOf(nb)
        for (i in 0 until n1) assertEquals(one[i].toInt(), joined[i].toInt())
    }
}
