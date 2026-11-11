package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.timlock.jarvis.ui.hud.SimStyling.simBorder

/** §4.1 first-run screen. */
@Composable
fun FirstRunScreen(
    onPair: () -> Unit,
    onSimulate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val px = LocalPx.current
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PlaceholderOrb(300f)
        Spacer(Modifier.height(px.dp(20f)))
        Text(
            "Connect this display to Muse",
            style = JarvisType.HudLabel.copy(sizePx = 44f, tracking = 0.02f).style(JarvisColors.TextPrimary),
        )
        Spacer(Modifier.height(px.dp(28f)))
        Row(horizontalArrangement = Arrangement.spacedBy(px.dp(40f))) {
            ChoiceCard(
                title = "◈ PAIR WITH MUSE",
                body = "Needs your phone & the Muse app nearby (~3 min)",
                titleColor = JarvisColors.CyanBright,
                borderModifier = Modifier.border(px.dp(2f), JarvisColors.CyanDim, RoundedCornerShape(px.dp(14f))),
                onClick = onPair,
            )
            ChoiceCard(
                title = "◇ SIMULATION MODE",
                body = "No account needed. Fake replies for testing.",
                titleColor = SimStyling.accent,
                borderModifier = Modifier.simBorder(),
                onClick = onSimulate,
            )
        }
        Spacer(Modifier.height(px.dp(24f)))
        Text(
            "Import pairing from another device ›",
            style = JarvisType.Body.style(JarvisColors.TextSecondary),
            modifier = Modifier.clickable(onClick = onImport).padding(px.dp(8f)),
        )
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    titleColor: Color,
    borderModifier: Modifier,
    onClick: () -> Unit,
) {
    val px = LocalPx.current
    Column(
        Modifier
            .width(px.dp(500f))
            .background(JarvisColors.PanelFill, RoundedCornerShape(px.dp(14f)))
            .then(borderModifier)
            .clickable(onClick = onClick)
            .padding(px.dp(28f)),
    ) {
        Text(title, style = JarvisType.HudLabel.style(titleColor))
        Spacer(Modifier.height(px.dp(8f)))
        Text(body, style = JarvisType.Body.style(JarvisColors.TextSecondary))
    }
}
