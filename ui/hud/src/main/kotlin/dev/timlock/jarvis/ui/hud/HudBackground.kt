package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import kotlin.math.hypot

/** bg.void with a radial vignette: bg.deep at the center -> bg.void at 0.7 * diagonal. */
@Composable
fun HudBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(JarvisColors.BgVoid)
            .drawBehind {
                val diag = hypot(size.width, size.height)
                drawRect(
                    Brush.radialGradient(
                        0f to JarvisColors.BgDeep,
                        1f to JarvisColors.BgVoid,
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = 0.7f * diag,
                    ),
                )
            },
    ) { content() }
}
