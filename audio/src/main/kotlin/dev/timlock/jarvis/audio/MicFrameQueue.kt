package dev.timlock.jarvis.audio

/**
 * Pure state machine behind [MicSource] (DESIGN §4.3 / §6.4). The capture thread feeds [onCaptured]; the SDK-side
 * consumer calls [poll]. Thread-safe (one lock; critical sections are short copies).
 *
 * Modes: STOPPED / SILENCE hand out any ring leftovers (zero-padded to a frame) and then paced 960-zero frames
 * (never EOF while the stream is open); BACKLOG captures into the ring and hands out nothing (call not ready);
 * LIVE hands out whole 960-sample frames from the ring (backlog first, naturally at the 40 ms pace, i.e.
 * "drained at real-time pace", then live). Captured audio is only kept in BACKLOG/LIVE. The ring holds <= 10 s
 * (oldest dropped); the utterance buffer (fallback path) holds <= 60 s from the last [start].
 */
class MicFrameQueue(
    private val ringCap: Int = 10 * RATE,
    private val utteranceCap: Int = 60 * RATE,
) {
    enum class Mode { STOPPED, BACKLOG, LIVE, SILENCE }

    private val lock = Any()
    private val ring = ShortArray(ringCap)
    private var head = 0
    private var count = 0
    private var utt: ShortArray? = null
    private var uttLen = 0
    private var mode = Mode.STOPPED
    private var nextDueMs = 0L
    private var gen = 0
    private var ended = false
    private var streamOpen = false

    val currentMode: Mode get() = synchronized(lock) { mode }
    val ringSamples: Int get() = synchronized(lock) { count }

    /** MicStart: new utterance, capture to backlog. Keeps queued ring audio only while a consumer is attached. */
    fun start() = synchronized(lock) {
        uttLen = 0
        if (!streamOpen) { head = 0; count = 0 }
        mode = Mode.BACKLOG
    }

    /** MicLive: backlog then live frames. */
    fun live() = synchronized(lock) { mode = Mode.LIVE }

    /** MicSilence: ring leftovers first, then paced zero frames. Capture no longer kept. */
    fun silence() = synchronized(lock) { mode = Mode.SILENCE }

    /** Recorder closed (tail elapsed): same hand-out behaviour as SILENCE. */
    fun stopped() = synchronized(lock) { mode = Mode.STOPPED }

    /** New consumer for a (new) call; returns its generation. Does not touch queued audio. */
    fun openStream(): Int = synchronized(lock) { gen++; ended = false; streamOpen = true; gen }

    /** Call ended: EOF for the current stream. */
    fun endStream() = synchronized(lock) { ended = true; streamOpen = false }

    fun streamEnded(g: Int): Boolean = synchronized(lock) { ended || g != gen }

    /** Capture thread: [len] samples of 24 kHz mono. */
    fun onCaptured(block: ShortArray, off: Int, len: Int) = synchronized(lock) {
        if (mode != Mode.BACKLOG && mode != Mode.LIVE) return@synchronized
        var u = utt
        if (u == null) { u = ShortArray(utteranceCap); utt = u }
        val take = minOf(len, utteranceCap - uttLen)
        if (take > 0) { System.arraycopy(block, off, u, uttLen, take); uttLen += take }
        var src = off
        var n = len
        if (n > ringCap) { src += n - ringCap; n = ringCap }
        val over = count + n - ringCap
        if (over > 0) { head = (head + over) % ringCap; count -= over }
        var w = (head + count) % ringCap
        for (i in 0 until n) { ring[w] = block[src + i]; w = if (w + 1 == ringCap) 0 else w + 1 }
        count += n
    }

    /** Audio captured since the last [start] (<= 60 s). */
    fun takeUtterance(): ShortArray = synchronized(lock) { utt?.copyOf(uttLen) ?: ShortArray(0) }

    /** Next frame if one is due at [nowMs], else null. Caller checks [streamEnded] first. */
    fun poll(g: Int, nowMs: Long): ShortArray? = synchronized(lock) {
        if (ended || g != gen || nowMs < nextDueMs) return@synchronized null
        val frame: ShortArray
        when (mode) {
            Mode.BACKLOG -> return@synchronized null
            Mode.LIVE -> {
                if (count < FRAME) return@synchronized null
                frame = pop(FRAME)
            }
            Mode.SILENCE, Mode.STOPPED -> frame = if (count > 0) pop(minOf(FRAME, count)) else ShortArray(FRAME)
        }
        nextDueMs = if (nowMs >= nextDueMs + FRAME_MS) nowMs + FRAME_MS else nextDueMs + FRAME_MS
        frame
    }

    /** Suggested wait before the next [poll] attempt. */
    fun waitMs(nowMs: Long): Long = synchronized(lock) { (nextDueMs - nowMs).coerceIn(2L, 20L) }

    private fun pop(n: Int): ShortArray {
        val out = ShortArray(FRAME)
        var r = head
        for (i in 0 until n) { out[i] = ring[r]; r = if (r + 1 == ringCap) 0 else r + 1 }
        head = r
        count -= n
        return out
    }

    companion object {
        const val RATE = 24_000
        const val FRAME = 960
        const val FRAME_MS = 40L
    }
}
