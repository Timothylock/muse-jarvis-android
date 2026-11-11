package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val DESIGN_WIDTH_PX = 1340f
const val DESIGN_HEIGHT_PX = 800f

/**
 * Converts design pixels (1340x800 canvas) to dp/sp at runtime.
 * dp = px * (windowWidthPx / 1340) / density, so the layout matches the design on the tablet and scales elsewhere.
 */
@Immutable
class Px(val scale: Float, val density: Float, val fontScale: Float) {
    fun dp(px: Float): Dp = (px * scale / density).dp
    fun dp(px: Int): Dp = dp(px.toFloat())

    /** Raw device pixels for a design px value (for Canvas drawing). */
    fun device(px: Float): Float = px * scale

    fun sp(px: Float): TextUnit = (px * scale / density / fontScale).sp
}

val LocalPx = compositionLocalOf { Px(1f, 1f, 1f) }

@Composable
fun ProvidePx(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = constraints.maxWidth / DESIGN_WIDTH_PX
        CompositionLocalProvider(LocalPx provides Px(scale, d.density, d.fontScale)) { content() }
    }
}
