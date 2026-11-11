package dev.timlock.jarvis.render.orb

import java.util.Arrays

/**
 * §3.6 governor. Samples are "frame cost" in ms (CPU draw time plus how late the frame arrived
 * relative to its target interval). 3 s windows; GL-thread only except [thermalStatus].
 */
internal class QualityGovernor(private val headroom: () -> Float) {
    @Volatile var thermalStatus: Int = 0
    var tier: Tier = Tier.A
        private set

    private val buf = FloatArray(1024)
    private var n = 0
    private var windowStart = 0L
    private var overWindows = 0
    private var healthyMs = 0L

    fun record(sampleMs: Float, nowMs: Long, targetFps: Int) {
        if (windowStart == 0L) windowStart = nowMs
        if (n < buf.size) buf[n++] = sampleMs
        if (nowMs - windowStart < WINDOW_MS) return
        val windowLen = nowMs - windowStart
        windowStart = nowMs
        val p95 = if (n >= 8) percentile(0.95f) else 0f
        n = 0
        val limit = if (targetFps >= 45) 15.5f else 31f
        val over = p95 > limit
        val hot = thermalStatus >= THERMAL_MODERATE
        overWindows = if (over) overWindows + 1 else 0
        if ((overWindows >= 2 || hot) && tier != Tier.D) {
            tier = Tier.values()[tier.ordinal + 1]
            overWindows = 0
            healthyMs = 0
            return
        }
        if (over || hot) { healthyMs = 0; return }
        healthyMs += windowLen
        if (healthyMs >= HEALTHY_MS && tier != Tier.A) {
            val h = headroom()
            if (h.isNaN() || h < 0.7f) {
                tier = Tier.values()[tier.ordinal - 1]
                healthyMs = 0
            }
        }
    }

    private fun percentile(q: Float): Float {
        Arrays.sort(buf, 0, n)
        return buf[((n - 1) * q).toInt()]
    }

    companion object {
        const val WINDOW_MS = 3000L
        const val HEALTHY_MS = 30_000L
        const val THERMAL_MODERATE = 2
    }
}
