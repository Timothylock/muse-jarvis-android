package dev.timlock.jarvis.audio

/**
 * Ring of analyzed level frames keyed by playback sample position (Q3: visuals are latency-compensated by looking
 * up the frame under the playback head). Single writer (audio-out thread) and single reader (same thread);
 * not thread-safe on its own. Allocation-free.
 */
class LevelFrameRing(private val capacity: Int = 512) {
    private val start = LongArray(capacity)
    private val end = LongArray(capacity)
    private val env = FloatArray(capacity)
    private val onsets = IntArray(capacity)
    private val strength = FloatArray(capacity)
    private val bands = FloatArray(capacity * LevelAnalyzer.BANDS)
    private var next = 0
    private var size = 0

    fun clear() { next = 0; size = 0 }

    /** Records the analyzer's current output for samples [startPos, startPos+len). */
    fun append(startPos: Long, len: Int, a: LevelAnalyzer) {
        val i = next
        start[i] = startPos; end[i] = startPos + len
        env[i] = a.env; onsets[i] = a.onsetCount; strength[i] = a.onsetStrength
        System.arraycopy(a.bands, 0, bands, i * LevelAnalyzer.BANDS, LevelAnalyzer.BANDS)
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Finds the frame containing [headPos] (newest frame if the head is past all of them, oldest if before all).
     * Returns its slot index or -1 when empty; read fields with the accessors.
     */
    fun lookup(headPos: Long): Int {
        if (size == 0) return -1
        var idx = (next - 1 + capacity) % capacity
        var best = idx
        for (k in 0 until size) {
            best = idx
            if (start[idx] <= headPos) return idx
            idx = (idx - 1 + capacity) % capacity
        }
        return best
    }

    fun envAt(i: Int) = env[i]
    fun onsetsAt(i: Int) = onsets[i]
    fun strengthAt(i: Int) = strength[i]
    fun bandsInto(i: Int, out: FloatArray) = System.arraycopy(bands, i * LevelAnalyzer.BANDS, out, 0, LevelAnalyzer.BANDS)

    companion object {
        /** Fallback playback head: write position minus the estimated output latency, never negative. */
        fun estimateHead(written: Long, latencyFrames: Int): Long = (written - latencyFrames).coerceAtLeast(0L)
    }
}
