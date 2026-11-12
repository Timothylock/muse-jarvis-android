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
    /** Fills the whole screen behind the HUD (the GL orb); the default is the static preview orb. */
    orb: @Composable () -> Unit = { DefaultAmbientOrb() },
    /** True when DOCK panels exist: the docked orb would overlap the big clock, so it is hidden. Chips coexist with the clock. */
    hasDockedPanels: Boolean = false,
    /** Drawn over the face (above the status bar and clock), e.g. the panel stack. */
    overlay: @Composable () -> Unit = {},
    onSimChipClick: (() -> Unit)? = null,
) {
    val px = LocalPx.current
    val now by rememberNow()
    Box(modifier.fillMaxSize()) {
        orb()
        StatusBar(mode, link, now.timeText(), now.dateText(), Modifier.align(Alignment.TopStart), onSimChipClick)

        if (!hasDockedPanels) {
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
        }

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
                val label = when (orbState) {
                    OrbState.IDLE -> "HOLD TO TALK"
                    OrbState.UNPAIRED -> "Pair to begin"
                    else -> orbState.name
                }
                Text(
                    label,
                    style = JarvisType.HudLabel.style(
                        when (orbState) {
                            OrbState.UNPAIRED -> JarvisColors.WarnAmber
                            OrbState.IDLE -> JarvisColors.CyanBright
                            else -> JarvisColors.CyanMid
                        },
                    ),
                )
            }
        }
        overlay()
    }
}

/** Preview stand-in: orb center (670, 392), diameter 520. */
@Composable
private fun DefaultAmbientOrb() {
    val px = LocalPx.current
    Box(Modifier.offset(px.dp(670f - 260f), px.dp(392f - 260f))) { PlaceholderOrb(520f) }
}
