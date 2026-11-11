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

/**
 * bg.void with a radial vignette: bg.deep at the center -> bg.void at 0.7 * diagonal.
 * Pass [drawBackground] = false when the GL orb surface (which draws the same vignette) sits behind the HUD.
 */
@Composable
fun HudBackground(
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .then(if (drawBackground) Modifier.background(JarvisColors.BgVoid) else Modifier)
            .drawBehind {
                if (!drawBackground) return@drawBehind
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
