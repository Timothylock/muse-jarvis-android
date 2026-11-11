package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Stand-in for the Pair / Import flows (feature:pairing, M7). */
@Composable
fun PairingPlaceholderScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(px.dp(28f), Alignment.CenterVertically),
    ) {
        Text("Pairing arrives in a later build", style = JarvisType.Body.style(JarvisColors.TextPrimary))
        Text(
            "BACK",
            style = JarvisType.HudLabel.style(JarvisColors.CyanBright),
            modifier = Modifier
                .border(px.dp(2f), JarvisColors.CyanDim, RoundedCornerShape(px.dp(24f)))
                .clickable(onClick = onBack)
                .padding(horizontal = px.dp(48f), vertical = px.dp(10f)),
        )
    }
}
