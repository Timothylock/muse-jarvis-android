package dev.timlock.jarvis.ui.hud

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/** A §2.2 typography role. Sizes are design px; UPPER-casing is done by callers. */
class TypeRole(
    val family: FontFamily,
    val weight: FontWeight,
    val sizePx: Float,
    val tracking: Float = 0f,
    val lineHeightPx: Float? = null,
) {
    fun copy(sizePx: Float = this.sizePx, tracking: Float = this.tracking) =
        TypeRole(family, weight, sizePx, tracking, lineHeightPx)
}

@Composable
fun TypeRole.style(color: Color = Color.Unspecified): TextStyle {
    val px = LocalPx.current
    return TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = px.sp(sizePx),
        letterSpacing = tracking.em,
        lineHeight = px.sp(lineHeightPx ?: (sizePx * 1.15f)),
        color = color,
    )
}

object JarvisType {
    val Rajdhani = FontFamily(
        Font(R.font.rajdhani_light, FontWeight.Light),
        Font(R.font.rajdhani_medium, FontWeight.Medium),
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    )
    val PlexSans = FontFamily(
        Font(R.font.plex_sans_light, FontWeight.Light),
        Font(R.font.plex_sans_regular, FontWeight.Normal),
    )
    val PlexMono = FontFamily(
        Font(R.font.plex_mono_light, FontWeight.Light),
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )

    val HudLabel = TypeRole(Rajdhani, FontWeight.SemiBold, 26f, 0.08f)
    val StatusBar = TypeRole(Rajdhani, FontWeight.Medium, 18f, 0.12f)
    val Body = TypeRole(PlexSans, FontWeight.Normal, 26f, 0f, 34f)
    val Caption = TypeRole(PlexSans, FontWeight.Light, 24f, 0f, 32f)
    val MetricStat = TypeRole(PlexMono, FontWeight.Medium, 64f, -0.02f)
    val MetricCompact = TypeRole(PlexMono, FontWeight.Medium, 44f, -0.02f)
    val MetricUnit = TypeRole(PlexMono, FontWeight.Normal, 26f)
    val AmbientClock = TypeRole(Rajdhani, FontWeight.Light, 120f, 0.04f, 120f)
    val TimerCountdown = TypeRole(PlexMono, FontWeight.Light, 88f)
}
