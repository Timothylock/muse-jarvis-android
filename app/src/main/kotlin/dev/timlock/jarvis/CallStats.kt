package dev.timlock.jarvis

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only per-call diagnostics (§7.7). Numbers and flags only: never captions, transcripts or server text.
 * Times are monotonic millis; [admissionMs] = open -> Ready, [firstAudibleMs] = latest turn end -> first audible reply.
 */
data class CallStats(
    val seq: Int,
    val threadIdSet: Boolean,
    val test: Boolean,
    val openedAtMs: Long,
    val admissionMs: Long?,
    val firstAudibleMs: Long?,
    val framesSent: Long,
    val liveFramesSent: Long,
    val samplesReceived: Long,
    val endReason: String?,
    val durationMs: Long?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("seq", seq).put("threadIdSet", threadIdSet).put("test", test).put("openedAtMs", openedAtMs)
        .put("admissionMs", admissionMs ?: JSONObject.NULL).put("firstAudibleMs", firstAudibleMs ?: JSONObject.NULL)
        .put("framesSent", framesSent).put("liveFramesSent", liveFramesSent).put("samplesReceived", samplesReceived)
        .put("endReason", endReason ?: JSONObject.NULL).put("durationMs", durationMs ?: JSONObject.NULL)
}

/** Mutable tracker behind one [CallStats]; counters are bumped from SDK/audio threads. */
internal class CallTrack(val seq: Int, val threadIdSet: Boolean, val test: Boolean, val openedAtMs: Long) {
    val frames = AtomicLong()
    val liveFrames = AtomicLong()
    val samples = AtomicLong()
    @Volatile var admissionMs: Long? = null
    @Volatile var firstAudibleMs: Long? = null
    @Volatile var turnEndMs: Long = 0L
    @Volatile var endReason: String? = null
    @Volatile var endedAtMs: Long = 0L

    fun snapshot(nowMs: Long) = CallStats(
        seq, threadIdSet, test, openedAtMs, admissionMs, firstAudibleMs, frames.get(), liveFrames.get(), samples.get(),
        endReason, if (endedAtMs != 0L) endedAtMs - openedAtMs else null,
    )
}
