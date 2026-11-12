package dev.timlock.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WavReaderTest {
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + body.size + (body.size and 1)).order(ByteOrder.LITTLE_ENDIAN)
        b.put(id.toByteArray()).putInt(body.size).put(body)
        return b.array()
    }

    private fun fmt(format: Int, ch: Int, rate: Int, bits: Int): ByteArray {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(format.toShort()).putShort(ch.toShort()).putInt(rate).putInt(rate * ch * bits / 8)
            .putShort((ch * bits / 8).toShort()).putShort(bits.toShort())
        return b.array()
    }

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        val body = chunks.sumOf { it.size }
        o.write("RIFF".toByteArray())
        o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + body).array())
        o.write("WAVE".toByteArray())
        chunks.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun pcm16(vararg s: Int): ByteArray {
        val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { b.putShort(it.toShort()) }
        return b.array()
    }

    @Test fun stereoPcm16Downmix() {
        val n = 160
        val samples = IntArray(n * 2) { if (it % 2 == 0) 1000 else 3000 }
        val w = WavReader.parse(wav(chunk("fmt ", fmt(1, 2, 16000, 16)), chunk("data", pcm16(*samples))))
        assertEquals(16000, w.sampleRate)
        assertEquals(2, w.channels)
        assertEquals(n, w.pcm.size)
        assertEquals(2000, w.pcm[10].toInt())
    }

    @Test fun float32() {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(0.5f).putFloat(-0.25f).putFloat(2f)
        val w = WavReader.parse(wav(chunk("fmt ", fmt(3, 1, 24000, 32)), chunk("data", b.array())))
        assertEquals(3, w.pcm.size)
        assertEquals(16384, w.pcm[0].toInt())
        assertEquals(-8192, w.pcm[1].toInt())
        assertEquals(32767, w.pcm[2].toInt())
    }

    @Test fun skipsListChunk() {
        val list = chunk("LIST", "INFOabc".toByteArray()) // odd size -> pad byte
        val w = WavReader.parse(wav(chunk("fmt ", fmt(1, 1, 24000, 16)), list, chunk("data", pcm16(1, 2, 3))))
        assertEquals(listOf<Short>(1, 2, 3), w.pcm.toList())
    }

    @Test fun rejects8Bit() {
        try {
            WavReader.parse(wav(chunk("fmt ", fmt(1, 1, 8000, 8)), chunk("data", ByteArray(10))))
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("wav: "))
        }
    }

    @Test fun rejectsGarbageAndSixChannels() {
        try { WavReader.parse(ByteArray(40)); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("wav: ")) }
        try {
            WavReader.parse(wav(chunk("fmt ", fmt(1, 6, 24000, 16)), chunk("data", ByteArray(12))))
            fail()
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("channel")) }
    }

    private fun sine(hz: Double, rate: Int, n: Int) = ShortArray(n) { (sin(2 * PI * hz * it / rate) * 10000).toInt().toShort() }
    private fun peak(a: ShortArray, from: Int, to: Int) = (from until to).maxOf { abs(a[it].toInt()) }

    @Test fun identityAt24k() {
        val x = sine(440.0, 24000, 100)
        assertTrue(Resample.to24k(x, 24000) === x)
    }

    @Test fun downsample48kLengthAndAmplitude() {
        val out = Resample.to24k(sine(500.0, 48000, 48000), 48000)
        assertEquals(24000, out.size)
        assertTrue("peak ${peak(out, 1000, 23000)}", abs(peak(out, 1000, 23000) - 10000) < 800)
    }

    @Test fun upsample16kLengthAndAmplitude() {
        val out = Resample.to24k(sine(500.0, 16000, 16000), 16000)
        assertEquals(24000, out.size)
        assertTrue("peak ${peak(out, 1000, 23000)}", abs(peak(out, 1000, 23000) - 10000) < 500)
    }

    @Test fun downsample44k1Ratio() {
        val out = Resample.to24k(sine(300.0, 44100, 44100), 44100)
        assertEquals(24000, out.size)
    }
}
