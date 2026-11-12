package dev.timlock.jarvis.core.model

/** Plain data for the §7.3 simulation control panel. The drawer is stateless: :app collects and passes these. */
data class ScenarioInfo(val id: String, val title: String, val external: Boolean = false)

/** Scenario progress. [id] null = no scenario running (free play: the sim auto-replies to PTT turns). */
data class ScenarioRun(
    val id: String? = null,
    val step: Int = 0,
    val total: Int = 0,
    val paused: Boolean = false,
    val timescale: Float = 1f,
    /** Assertion counts from `expect` steps in the current/last run. */
    val asserts: Int = 0,
    val failed: Int = 0,
)

/** One line of the event log (last 200). Sim-only content, never real server text. */
data class SimLogEntry(val atMs: Long, val text: String)

/** Drawer sliders (§7.3). [admitMs]/[thinkMs] apply to free play and to scenarios that don't set them. */
data class SimSettings(val admitMs: Long = 800, val thinkMs: Long = 2000, val timescale: Float = 1f)

/** §7.7 `cmd fault --es kind ...` and the drawer's Faults row. */
enum class SimFault(val wire: String) {
    OFFLINE("offline"), REVOKE("revoke"), SEND_FAIL("send_fail"), ADMIT_TIMEOUT("admit_timeout"), SESSION_DEAD("session_dead");
    companion object { fun parse(s: String?): SimFault? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() } }
}

/** One-tap display presets in the drawer (§7.3 "Display" row). INVALID_* exercise the validator. */
enum class DisplayPreset { CARD, STATS, LIST, TIMER, CLEAR_ALL, FLOOD_X6, INVALID_MISSING_TITLE, INVALID_7_METRICS, INVALID_LONG_LABEL, INVALID_BAD_ID }

/** Perf HUD numbers (§3.6), refreshed about once a second by :app. */
data class PerfHudData(
    val fps: Float = 0f, val p50Ms: Float = 0f, val p95Ms: Float = 0f, val tier: String = "-",
    val particles: Int = 0, val thermal: Int = 0, val pssMb: Int = 0,
)
