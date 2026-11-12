package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.AudioLevels
import dev.timlock.jarvis.core.model.AudioVisualBus
import dev.timlock.jarvis.core.model.OrbState
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthetic audio levels for sim mode (replaces real mic/playback analysis, §3.3).
 * Call [step] at ~50 Hz. SPEAKING drives `bus.out` with a babble envelope; LISTENING/CONNECTING
 * drive `bus.mic` with a speech-like envelope; everything else publishes low noise.
 * Allocation-free per step.
 */
class SyntheticLevels(private val bus: AudioVisualBus, seed: Long = 0x5EED_1234L) {
    private val out = SyntheticVoice(seed, syllableHzMin = 4f, syllableHzMax = 6f, pauseMinMs = 300f, pauseMaxMs = 700f)
    private val mic = SyntheticVoice(seed xor 0x9E3779B9L, syllableHzMin = 3f, syllableHzMax = 5f, pauseMinMs = 250f, pauseMaxMs = 900f)

    /**
     * [driveOut]/[driveMic] choose which bus channels this synthesizer writes. From M5 the real Player analysis
     * drives `bus.out` (driveOut = false) and the real mic drives `bus.mic`; the synthetic mic channel is only a
     * fallback while RECORD_AUDIO is not granted.
     */
    fun step(orb: OrbState, nowMs: Long, driveOut: Boolean = true, driveMic: Boolean = true) {
        out.step(nowMs, active = orb == OrbState.SPEAKING)
        mic.step(nowMs, active = orb == OrbState.LISTENING || orb == OrbState.CONNECTING)
        if (driveOut) out.publishTo(bus.out)
        if (driveMic) mic.publishTo(bus.mic)
    }
}

/**
 * One channel of babble: syllables at 4-6 Hz (sine-shaped bursts) in runs of 3-8, separated by pauses
 * (> 280 ms), shaped by the §3.3 envelope (attack 30 ms / release 180 ms, onset = env - slow(400 ms) > 0.18
 * and >= 120 ms since the last), plus 8 random-walk bands. Pure and deterministic for a given seed.
 */
class SyntheticVoice(
    seed: Long,
    private val syllableHzMin: Float,
    private val syllableHzMax: Float,
    private val pauseMinMs: Float,
    private val pauseMaxMs: Float,
) {
    private var rng = if (seed == 0L) 0x2545F4914F6CDD1DL else seed
    val bands = FloatArray(AudioLevels.BANDS) { 0.5f }
    var env = 0f
        private set
    var onsets = 0
        private set

    private var slow = 0f
    private var lastMs = -1L
    private var lastOnsetMs = Long.MIN_VALUE / 2
    private var syllableStart = 0L
    private var syllableEnd = 0L
    private var pauseUntil = 0L
    private var peak = 0f
    private var syllablesLeft = 0

    private fun nextFloat(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
        rng = x
        return ((x ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }

    fun step(nowMs: Long, active: Boolean) {
        val dt = if (lastMs < 0) 20f else (nowMs - lastMs).coerceIn(1L, 100L).toFloat()
        lastMs = nowMs

        val a: Float
        if (!active) {
            a = 0.02f + nextFloat() * 0.01f
            syllableEnd = 0L; pauseUntil = 0L; syllablesLeft = 0
        } else {
            if (nowMs >= syllableEnd && nowMs >= pauseUntil) {
                if (syllablesLeft <= 0) {
                    pauseUntil = nowMs + (pauseMinMs + nextFloat() * (pauseMaxMs - pauseMinMs)).toLong()
                    syllablesLeft = 3 + (nextFloat() * 6f).toInt()
                } else {
                    syllablesLeft--
                    val hz = syllableHzMin + nextFloat() * (syllableHzMax - syllableHzMin)
                    syllableStart = nowMs
                    syllableEnd = nowMs + (1000f / hz).toLong()
                    peak = 0.5f + nextFloat() * 0.5f
                }
            }
            a = if (nowMs < pauseUntil || nowMs >= syllableEnd || syllableEnd <= syllableStart) 0f
            else peak * sin(PI.toFloat() * (nowMs - syllableStart) / (syllableEnd - syllableStart).toFloat())
        }

        // §3.3 step 3: one-pole envelope, attack 30 ms / release 180 ms.
        val tau = if (a > env) 30f else 180f
        env += (a - env) * (1f - exp(-dt / tau))
        slow += (env - slow) * (1f - exp(-dt / 400f))
        // §3.3 step 4: onset detector.
        if (active && env - slow > 0.18f && nowMs - lastOnsetMs >= 120L) {
            onsets++
            lastOnsetMs = nowMs
        }
        // 8 random-walk bands, mean-reverting around 0.5.
        for (i in bands.indices) {
            val b = bands[i] + (nextFloat() - 0.5f) * 0.25f + (0.5f - bands[i]) * 0.05f
            bands[i] = b.coerceIn(0f, 1f)
        }
    }

    fun publishTo(levels: AudioLevels) = levels.publish(env, onsets, bands)
}
