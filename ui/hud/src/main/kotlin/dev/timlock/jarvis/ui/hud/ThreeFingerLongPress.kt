package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/** Fires [onTrigger] when three or more fingers stay down together for [holdMs]. Never consumes events. */
fun Modifier.threeFingerLongPress(enabled: Boolean, holdMs: Long = 1000, onTrigger: () -> Unit): Modifier = composed2(enabled, onTrigger, holdMs)

private fun Modifier.composed2(enabled: Boolean, onTrigger: () -> Unit, holdMs: Long): Modifier =
    pointerInput(enabled, holdMs) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var threeSince = -1L
            var fired = false
            while (true) {
                val remaining = if (threeSince < 0 || fired) Long.MAX_VALUE
                else holdMs - (System.nanoTime() / 1_000_000 - threeSince)
                val ev = if (remaining == Long.MAX_VALUE) awaitPointerEvent(PointerEventPass.Initial)
                else withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent(PointerEventPass.Initial) }
                if (ev == null) { // timed out with 3 fingers held
                    fired = true
                    onTrigger()
                    continue
                }
                val down = ev.changes.count { it.pressed }
                if (down == 0) return@awaitEachGesture
                if (down >= 3) { if (threeSince < 0) threeSince = System.nanoTime() / 1_000_000 } else threeSince = -1L
            }
        }
    }
