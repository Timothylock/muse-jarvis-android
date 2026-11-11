package dev.timlock.jarvis.ui.hud.panels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.timlock.jarvis.core.model.monoMillis

/**
 * Panel arrival timeline (review C9), in ms since the panel's `revealStartMs`:
 * invisible until 380; brackets pop 380-500 (scale 1.4 -> 1.0, ease-out-back) while fill/border fade in;
 * the scan line wipes top->bottom over 450-670 revealing content. Done at 700. All reads happen in draw
 * lambdas (no recomposition, no relayout per frame).
 */
internal class Arrival(private val anim: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?) {
    fun animForRun() = anim!!

    var running by mutableStateOf(anim != null)
        internal set

    private val ms: Float get() = anim?.value ?: SETTLED_MS

    /** Fill + 1 px border alpha. */
    fun fill(): Float = ((ms - 380f) / 120f).coerceIn(0f, 1f)

    /** Bracket alpha (0 before 380). */
    fun bracketAlpha(): Float = ((ms - 380f) / 60f).coerceIn(0f, 1f)

    /** Bracket arm scale 1.4 -> 1.0 with ease-out-back overshoot. */
    fun bracketScale(): Float {
        val u = ((ms - 380f) / 120f).coerceIn(0f, 1f)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val v = u - 1f
        val eased = 1f + c3 * v * v * v + c1 * v * v
        return 1.4f - 0.4f * eased
    }

    /** Scan progress 0..1 over 450-670 ms. */
    fun mask(): Float = ((ms - 450f) / 220f).coerceIn(0f, 1f)

    companion object {
        const val SETTLED_MS = 700f
        val Settled = Arrival(null)
    }
}

/** Animates only if the panel is still inside its 700 ms timeline at first composition; otherwise settled. */
@Composable
internal fun rememberArrival(revealStartMs: Long, enabled: Boolean): Arrival {
    val arrival = remember {
        val t = (monoMillis() - revealStartMs).toFloat()
        if (enabled && t < Arrival.SETTLED_MS) Arrival(Animatable(t)) else Arrival.Settled
    }
    if (arrival !== Arrival.Settled) {
        LaunchedEffect(Unit) {
            val a = arrival.animForRun()
            a.animateTo(Arrival.SETTLED_MS, tween((Arrival.SETTLED_MS - a.value).toInt().coerceAtLeast(1), easing = LinearEasing))
            arrival.running = false
        }
    }
    return arrival
}
