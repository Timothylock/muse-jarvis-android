package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/** Static stand-in for the GL orb (arrives in M2): 3 concentric rings and a soft core. */
@Composable
fun PlaceholderOrb(diameterPx: Float, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    Canvas(modifier.size(px.dp(diameterPx))) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val stroke = px.device(2f)
        drawCircle(JarvisColors.CyanDim, radius = r - stroke, center = c, style = Stroke(stroke))
        drawCircle(JarvisColors.CyanMid, radius = r * 0.78f, center = c, style = Stroke(stroke * 1.5f))
        drawCircle(JarvisColors.CyanDim, radius = r * 0.56f, center = c, style = Stroke(stroke))
        drawCircle(
            Brush.radialGradient(
                0f to JarvisColors.CyanHot,
                0.3f to JarvisColors.CyanBright.copy(alpha = 0.55f),
                1f to Color.Transparent,
                center = c,
                radius = r * 0.42f,
            ),
            radius = r * 0.42f,
            center = c,
        )
    }
}
