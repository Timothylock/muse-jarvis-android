package dev.timlock.jarvis.core.model

/** Orb quality preference (§3.6). AUTO = governor decides. */
enum class QualityPref { AUTO, A, B, C }

/**
 * User-tunable runtime configuration (§4.5). Lives in the store as `AppState.config` and is persisted by :app.
 * Use [sanitized] on anything read from outside (DataStore, ADB): it clamps to the supported ranges.
 */
data class UserConfig(
    /** Standby window after a turn (15-120 s). */
    val standbyMs: Long = DEFAULT_STANDBY_MS,
    /** Think timeout (30/60/90 s). */
    val thinkTimeoutMs: Long = DEFAULT_THINK_TIMEOUT_MS,
    /** Default panel lifetime; null = sticky by default. */
    val panelTtlMs: Long? = DEFAULT_PANEL_TTL_MS,
    /** 2 or 3. */
    val maxPanels: Int = 3,
    val clock24h: Boolean = true,
    val nightEnabled: Boolean = true,
    /** Minutes after local midnight. */
    val nightStartMin: Int = 23 * 60,
    val nightEndMin: Int = 7 * 60,
    val quality: QualityPref = QualityPref.AUTO,
    /** -6, 0 or +6. */
    val micGainDb: Int = 0,
) {
    fun sanitized(): UserConfig = copy(
        standbyMs = standbyMs.coerceIn(15_000L, 120_000L),
        // Deliberately narrower than the spec's 20-180 s range (Fable audit finding 13, deferred by the overseer
        // 2026-11-13): widen only if the owner asks.
        thinkTimeoutMs = thinkTimeoutMs.coerceIn(30_000L, 90_000L),
        panelTtlMs = panelTtlMs?.coerceIn(10_000L, 3_600_000L),
        maxPanels = maxPanels.coerceIn(2, 3),
        nightStartMin = nightStartMin.mod(24 * 60),
        nightEndMin = nightEndMin.mod(24 * 60),
        micGainDb = micGainDb.coerceIn(-6, 6),
    )

    /** True when [minuteOfDay] lies in the night window (handles windows crossing midnight). Start == end = never. */
    fun inNightWindow(minuteOfDay: Int): Boolean = when {
        nightStartMin == nightEndMin -> false
        nightStartMin < nightEndMin -> minuteOfDay in nightStartMin until nightEndMin
        else -> minuteOfDay >= nightStartMin || minuteOfDay < nightEndMin
    }

    companion object {
        const val DEFAULT_STANDBY_MS = 45_000L
        const val DEFAULT_THINK_TIMEOUT_MS = 60_000L
        const val DEFAULT_PANEL_TTL_MS = 120_000L
    }
}
