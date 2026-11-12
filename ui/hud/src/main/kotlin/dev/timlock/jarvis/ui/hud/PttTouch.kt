package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import dev.timlock.jarvis.core.model.OrbLayout

/** §2.3 / §4.3 orb PTT target geometry, in design px (1340x800). */
object PttGeometry {
    const val CENTERED_CX = 670f
    const val DOCKED_CX = 305f
    const val CY = 392f
    const val CENTERED_D = 520f
    const val DOCKED_D = 440f
    /** A press shorter than this is a tap (matches the store's TAP_MS). */
    const val TAP_MS = 250L
    /** Second tap within this long after a tap-latch tap = double-tap (hang up). */
    const val DOUBLE_TAP_MS = 350L

    /** Whether the design-px point is inside the orb circle for [layout]; PAIRING / FIRST_RUN have no orb target. */
    fun inOrb(layout: OrbLayout, xPx: Float, yPx: Float): Boolean {
        val (cx, d) = when (layout) {
            OrbLayout.CENTERED -> CENTERED_CX to CENTERED_D
            OrbLayout.DOCKED -> DOCKED_CX to DOCKED_D
            else -> return false
        }
        val dx = xPx - cx
        val dy = yPx - CY
        return dx * dx + dy * dy <= (d / 2) * (d / 2)
    }
}

/**
 * Immediate PTT gestures (no gesture-detector delay): [onDown] on the first pointer down inside [hit], [onUp] when
 * that pointer lifts or the gesture is cancelled. With [onDoubleTap], a press that starts within
 * [PttGeometry.DOUBLE_TAP_MS] of the release of a tap (< [PttGeometry.TAP_MS]) calls it instead of [onDown]/[onUp].
 * Consumes only gestures it handles; touches outside [hit] fall through to whatever is below.
 */
@Composable
fun Modifier.pttTouch(
    onDown: () -> Unit,
    onUp: () -> Unit,
    onDoubleTap: (() -> Unit)? = null,
    hit: (Offset, IntSize) -> Boolean = { _, _ -> true },
): Modifier {
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val dbl by rememberUpdatedState(onDoubleTap)
    val hitTest by rememberUpdatedState(hit)
    return pointerInput(Unit) {
        var lastTapUpMs = -1L
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = true)
            if (!hitTest(first.position, size)) return@awaitEachGesture
            first.consume()
            val t0 = first.uptimeMillis
            val doubleCb = dbl
            val isDouble = doubleCb != null && lastTapUpMs >= 0 && t0 - lastTapUpMs <= PttGeometry.DOUBLE_TAP_MS
            if (isDouble) {
                lastTapUpMs = -1L
                doubleCb!!.invoke()
            } else {
                down()
            }
            var released = false
            var tUp = t0
            try {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == first.id }
                    ch?.consume()
                    if (ch == null || !ch.pressed) { tUp = ch?.uptimeMillis ?: tUp; released = true; break }
                }
            } finally {
                // Also runs when the gesture is cancelled (composition left / pointer input restarted).
                if (!isDouble) up()
            }
            if (!isDouble) lastTapUpMs = if (released && tUp - t0 < PttGeometry.TAP_MS) tUp else -1L
        }
    }
}
