package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState

/** §2.6 ambient / idle face, laid out on the 1340x800 design grid. */
@Composable
fun AmbientFace(
    mode: Mode?,
    link: LinkState,
    orbState: OrbState,
    onPttClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val px = LocalPx.current
    val now by rememberNow()
    Box(modifier.fillMaxSize()) {
        StatusBar(mode, link, now.timeText(), now.dateText(), Modifier.align(Alignment.TopStart))

        // Clock: top-left of the content area (content starts at y=48).
        Text(
            now.timeText(),
            style = JarvisType.AmbientClock.style(JarvisColors.TextPrimary),
            modifier = Modifier.offset(px.dp(50f), px.dp(60f)),
        )
        Text(
            now.dateText(),
            style = JarvisType.HudLabel.copy(tracking = 0f).style(JarvisColors.TextSecondary),
            modifier = Modifier.offset(px.dp(54f), px.dp(184f)),
        )

        // Orb: center (670, 392), diameter 520.
        Box(Modifier.offset(px.dp(670f - 260f), px.dp(392f - 260f))) { PlaceholderOrb(520f) }
        Text(
            orbState.name,
            style = JarvisType.StatusBar.style(JarvisColors.TextMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().offset(y = px.dp(668f)),
        )

        // Bottom 64 px PTT bar (y 736-800).
        val unpaired = orbState == OrbState.UNPAIRED
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(px.dp(64f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(px.dp(520f))
                    .height(px.dp(48f))
                    .border(px.dp(2f), JarvisColors.CyanDim, RoundedCornerShape(px.dp(24f)))
                    .clickable(onClick = onPttClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unpaired) "Pair to begin" else "HOLD TO TALK",
                    style = JarvisType.HudLabel.style(if (unpaired) JarvisColors.WarnAmber else JarvisColors.CyanBright),
                )
            }
        }
    }
}
