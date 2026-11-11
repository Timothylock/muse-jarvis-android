package dev.timlock.jarvis.ui.hud

import androidx.compose.ui.graphics.Color

/** §2.1 palette. `sim.violet` is deliberately absent: it lives only in [SimStyling] (§7.6 rule 8). */
object JarvisColors {
    val BgVoid = Color(0xFF02070A)
    val BgDeep = Color(0xFF04141A)
    val GridFaint = Color(0xFF0B2A33)
    val CyanDim = Color(0xFF0E5A66)
    val CyanMid = Color(0xFF1FB6C9)
    val CyanBright = Color(0xFF5CE8F5)
    val CyanHot = Color(0xFFC8FBFF)
    val TealDeep = Color(0xFF0A7F86)
    val TextPrimary = Color(0xFFD6F7FA)
    val TextSecondary = Color(0xFF7FBAC2)
    val TextMuted = Color(0xFF4A7C84)
    val StateListen = Color(0xFF7DF9C9)
    val StateThink = Color(0xFF4FA8FF)
    val WarnAmber = Color(0xFFFFB547)
    val ErrRed = Color(0xFFFF5A6A)

    /** Panel fill #06202A at 72% opacity. */
    val PanelFill = Color(0xFF06202A).copy(alpha = 0.72f)
}
