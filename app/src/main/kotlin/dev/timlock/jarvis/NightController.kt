package dev.timlock.jarvis

import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.core.store.AppStore
import dev.timlock.jarvis.core.store.CallStatus
import dev.timlock.jarvis.core.store.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalTime

/**
 * §2.7 night dim. Engages ([Intent.SetNight]) when the local time is inside the configured window, the user has been
 * idle for [IDLE_MS] and no call is open. Any touch or a panel arriving wakes it and suppresses re-entry for [SUPPRESS_MS].
 * [force] (ADB `night on|off|auto`) overrides the schedule: ON/OFF hold until AUTO.
 */
class NightController(
    private val store: AppStore,
    scope: CoroutineScope,
    private val localMinute: () -> Int = { LocalTime.now().let { it.hour * 60 + it.minute } },
) {
    enum class Force { AUTO, ON, OFF }

    @Volatile private var force = Force.AUTO
    @Volatile private var lastInteractionMs = monoMillis()
    @Volatile private var suppressUntilMs = 0L

    init {
        scope.launch { while (true) { delay(EVAL_MS); evaluate() } }
        scope.launch {
            store.state.map { Triple(it.config, it.call, it.mode) }.distinctUntilChanged().collect { evaluate() }
        }
        // Ambient push / any panel arrival wakes the display.
        scope.launch {
            var prev: Set<Pair<String, Int>>? = null
            store.state.map { s -> s.panels.panels.map { it.id to it.version }.toSet() }.distinctUntilChanged().collect { cur ->
                val p = prev
                prev = cur
                if (p != null && cur.any { it !in p }) wake()
            }
        }
    }

    fun setForce(f: Force) { force = f; evaluate() }
    fun forced(): Force = force

    /** Call from MainActivity.dispatchTouchEvent. Cheap enough for every touch event. */
    fun onInteraction() {
        lastInteractionMs = monoMillis()
        if (store.state.value.night) wake()
    }

    private fun wake() {
        if (force == Force.ON) return
        suppressUntilMs = monoMillis() + SUPPRESS_MS
        if (store.state.value.night) store.dispatch(Intent.SetNight(false))
    }

    private fun desired(): Boolean {
        val s = store.state.value
        // No mode chosen yet = first-run screen: never dim it (even when forced ON).
        if (s.mode == null) return false
        return when (force) {
            Force.ON -> true
            Force.OFF -> false
            Force.AUTO -> {
                val now = monoMillis()
                s.config.nightEnabled && s.config.inNightWindow(localMinute()) &&
                    now - lastInteractionMs >= IDLE_MS && s.call == CallStatus.None && now >= suppressUntilMs
            }
        }
    }

    fun evaluate() {
        val want = desired()
        if (want != store.state.value.night) store.dispatch(Intent.SetNight(want))
    }

    fun diag(): JSONObject {
        val s = store.state.value
        val now = monoMillis()
        return JSONObject()
            .put("active", s.night).put("force", force.name.lowercase())
            .put("inWindow", s.config.inNightWindow(localMinute()))
            .put("idleMs", now - lastInteractionMs)
            .put("suppressedMs", (suppressUntilMs - now).coerceAtLeast(0L))
    }

    private companion object {
        const val EVAL_MS = 30_000L
        const val IDLE_MS = 10 * 60_000L
        const val SUPPRESS_MS = 2 * 60_000L
    }
}
