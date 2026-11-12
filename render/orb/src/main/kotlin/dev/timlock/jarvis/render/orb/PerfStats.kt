package dev.timlock.jarvis.render.orb

/** Cheap, lock-free snapshot of renderer health for a later perf HUD. Written by the GL thread. */
object PerfStats {
    @Volatile var fps: Float = 0f
    @Volatile var p50Ms: Float = 0f
    @Volatile var p95Ms: Float = 0f
    /** p50 of the pure CPU draw-call time (no lateness term), ms. */
    @Volatile var cpuP50Ms: Float = 0f
    /** p50 GPU frame time from GL_EXT_disjoint_timer_query, ms; -1 when unavailable. */
    @Volatile var gpuP50Ms: Float = -1f
    @Volatile var tier: Tier = Tier.A
    @Volatile var particles: Int = 0
    /** PowerManager thermal status (0 none .. 6 shutdown). */
    @Volatile var thermal: Int = 0
    @Volatile var targetFps: Int = 60
}
