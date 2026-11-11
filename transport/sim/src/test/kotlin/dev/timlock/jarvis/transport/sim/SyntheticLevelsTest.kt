package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.AudioLevels
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.OrbState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticLevelsTest {
    private fun run(orb: OrbState, seconds: Int, bus: AudioVisualBus, from: Long = 0L): Long {
        val sl = SyntheticLevels(bus)
        var t = from
        repeat(seconds * 50) { sl.step(orb, t); t += 20 }
        return t
    }

    @Test fun speakingProducesEnvelopeOnsetsAndBands() {
        val bus = AudioVisualBus()
        val sl = SyntheticLevels(bus)
        val f = FloatArray(AudioLevels.FRAME_SIZE)
        var max = 0f
        var t = 0L
        repeat(250) {
            sl.step(OrbState.SPEAKING, t); t += 20
            bus.out.read(f)
            assertTrue(f[0] in 0f..1f)
            for (i in 2 until AudioLevels.FRAME_SIZE) assertTrue(f[i] in 0f..1f)
            if (f[0] > max) max = f[0]
        }
        assertTrue("env peaked at $max", max > 0.4f)
        assertTrue("onsets=${f[1]}", f[1] >= 5f)
        // mic stays quiet while speaking
        bus.mic.read(f)
        assertTrue(f[0] < 0.05f)
    }

    @Test fun speakingHasPausesLongerThan280ms() {
        val v = SyntheticVoice(42L, 4f, 6f, 300f, 700f)
        var quietRun = 0
        var longest = 0
        var t = 0L
        repeat(500) {
            v.step(t, true); t += 20
            if (v.env < 0.03f) { quietRun++; if (quietRun > longest) longest = quietRun } else quietRun = 0
        }
        assertTrue("longest quiet run ${longest * 20} ms", longest * 20 > 280)
    }

    @Test fun idleIsLowNoise() {
        val bus = AudioVisualBus()
        run(OrbState.IDLE, 3, bus)
        val f = FloatArray(AudioLevels.FRAME_SIZE)
        bus.out.read(f)
        assertTrue(f[0] in 0.01f..0.05f)
        assertEquals(0f, f[1], 0f)
    }

    @Test fun listeningDrivesMic() {
        val bus = AudioVisualBus()
        val sl = SyntheticLevels(bus)
        val f = FloatArray(AudioLevels.FRAME_SIZE)
        var max = 0f
        var t = 0L
        repeat(250) { sl.step(OrbState.LISTENING, t); t += 20; bus.mic.read(f); if (f[0] > max) max = f[0] }
        assertTrue(max > 0.3f)
    }

    @Test fun deterministicForSeed() {
        val a = SyntheticVoice(7L, 4f, 6f, 300f, 700f)
        val b = SyntheticVoice(7L, 4f, 6f, 300f, 700f)
        var t = 0L
        repeat(200) { a.step(t, true); b.step(t, true); t += 20; assertEquals(a.env, b.env, 0f) }
    }
}
