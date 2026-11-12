package dev.timlock.jarvis.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * PTT earcons (DESIGN §4.3 step 1): a 40 ms soft chirp, a short two-note error tone, and a haptic tick.
 * Tones are generated PCM in small static AudioTracks (created lazily, replayed). Failures are logged and ignored.
 */
class Earcons(context: Context) {
    private val vibrator: Vibrator? = try {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }
    } catch (_: Throwable) { null }
    private var chirp: AudioTrack? = null
    private var error: AudioTrack? = null

    @Synchronized fun playChirp() { chirp = replay(chirp ?: build(chirpPcm())) }
    @Synchronized fun playError() { error = replay(error ?: build(errorPcm())) }

    fun tick() {
        try { vibrator?.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Throwable) {}
    }

    private fun build(pcm: ShortArray): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build().also { it.write(pcm, 0, pcm.size) }
    } catch (e: Throwable) {
        Log.w("JARVIS_STORE", "earcon_build_failed ${e.javaClass.simpleName}")
        null
    }

    private fun replay(t: AudioTrack?): AudioTrack? {
        if (t == null) return null
        try {
            t.stop()
            t.setPlaybackHeadPosition(0)
            t.play()
        } catch (e: Throwable) {
            Log.w("JARVIS_STORE", "earcon_play_failed ${e.javaClass.simpleName}")
        }
        return t
    }

    companion object {
        const val RATE = 24_000

        /** 40 ms glide 880 -> 1320 Hz, raised-cosine envelope, peak ~ -14 dBFS. */
        fun chirpPcm(): ShortArray {
            val n = RATE * 40 / 1000
            val out = ShortArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val u = i.toDouble() / n
                phase += 2 * PI * (880.0 + 440.0 * u) / RATE
                val env = 0.5 - 0.5 * kotlin.math.cos(2 * PI * u)
                out[i] = (sin(phase) * env * 0.2 * 32767).toInt().toShort()
            }
            return out
        }

        /** Two descending notes (392 Hz then 262 Hz), 110 ms each. */
        fun errorPcm(): ShortArray {
            val seg = RATE * 110 / 1000
            val out = ShortArray(seg * 2)
            for (k in 0 until 2) {
                val f = if (k == 0) 392.0 else 262.0
                for (i in 0 until seg) {
                    val u = i.toDouble() / seg
                    val env = (if (u < 0.1) u / 0.1 else if (u > 0.7) (1 - u) / 0.3 else 1.0)
                    out[k * seg + i] = (sin(2 * PI * f * i / RATE) * env * 0.2 * 32767).toInt().toShort()
                }
            }
            return out
        }
    }
}
