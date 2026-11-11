package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign

/** The ONLY place sim.violet (#B48CFF) appears in the UI (§7.6 rule 8). */
object SimStyling {
    private val Violet = Color(0xFFB48CFF)

    /** Accent for text that must read as "simulation" (e.g. the sim card title). */
    val accent: Color get() = Violet

    /** Violet card/panel outline. */
    @Composable
    fun Modifier.simBorder(widthPx: Float = 2f, radiusPx: Float = 14f): Modifier {
        val px = LocalPx.current
        return border(px.dp(widthPx), Violet, RoundedCornerShape(px.dp(radiusPx)))
    }
}

/** 3 px violet border around the whole screen. Draw last in the root so it sits above everything. */
@Composable
fun SimFrame(modifier: Modifier = Modifier) {
    val px = LocalPx.current
    Box(modifier.fillMaxSize().border(px.dp(3f), SimStyling.accent))
}

/** "◇ SIMULATION" chip for the status bar. */
@Composable
fun SimChip(modifier: Modifier = Modifier) {
    val px = LocalPx.current
    Box(
        modifier
            .border(px.dp(1.5f), SimStyling.accent, RoundedCornerShape(px.dp(6f)))
            .background(SimStyling.accent.copy(alpha = 0.14f), RoundedCornerShape(px.dp(6f)))
            .padding(horizontal = px.dp(12f), vertical = px.dp(3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("◇ SIMULATION", style = JarvisType.StatusBar.style(SimStyling.accent))
    }
}

/** Full-screen "SIMULATION MODE — replies are fake" notice; the caller shows it for 1.5 s. */
@Composable
fun SimInterstitial(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(JarvisColors.BgVoid)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "SIMULATION MODE — replies are fake",
            style = JarvisType.HudLabel.copy(sizePx = 44f).style(SimStyling.accent),
            textAlign = TextAlign.Center,
        )
    }
}

/** §7.6 rule 4: violet 4 px left edge for panels rendered in sim mode. */
fun Modifier.simLeftEdge(px: Px, widthPx: Float = 4f): Modifier = drawBehind {
    drawRect(SimStyling.accent, Offset.Zero, Size(px.device(widthPx), size.height))
}

/** §7.6 rule 4: "SIM" micro-badge for panel title rows. */
@Composable
fun SimBadge(modifier: Modifier = Modifier) {
    val px = LocalPx.current
    Box(
        modifier
            .border(px.dp(1f), SimStyling.accent, RoundedCornerShape(px.dp(4f)))
            .padding(horizontal = px.dp(6f), vertical = px.dp(1f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("SIM", style = JarvisType.StatusBar.style(SimStyling.accent))
    }
}
