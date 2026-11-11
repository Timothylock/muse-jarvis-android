package dev.timlock.jarvis.core.transport

object PcmFormat {
    const val SAMPLE_RATE = 24_000
    const val FRAME_SAMPLES = 960
}

/** Mic side. Frames are [PcmFormat.FRAME_SAMPLES] samples of 24 kHz mono PCM16. */
interface PcmSource {
    /** Next frame, or null at end of stream. */
    suspend fun nextFrame(): ShortArray?
}

/** Speaker side. Writes must not block. */
interface PcmSink {
    fun write(pcm: ShortArray)
    fun flush()
    fun setListener(listener: PcmSinkListener?)
}

interface PcmSinkListener {
    /** First sample of the current utterance is audible. */
    fun onFirstAudible()

    /** All queued audio has been played. */
    fun onDrained()
}
