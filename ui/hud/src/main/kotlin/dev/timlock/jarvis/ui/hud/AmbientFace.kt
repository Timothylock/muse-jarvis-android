package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntSize
import dev.timlock.jarvis.core.model.Caption
import dev.timlock.jarvis.core.model.OrbLayout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    /** Layout from the store; decides the orb PTT circle (§2.3). */
    layout: OrbLayout = OrbLayout.CENTERED,
    caption: Caption? = null,
    callSinceMs: Long = 0L,
    /** Tap-latched talk: solid mint outline on the PTT pill. */
    latched: Boolean = false,
    onPttDown: () -> Unit = {},
    onPttUp: () -> Unit = {},
    onHangUp: () -> Unit = {},
) {
    val px = LocalPx.current
    val now by rememberNow()
    Box(modifier.fillMaxSize()) {
        orb()
        // Orb PTT target (§4.3): the whole orb circle, immediate down/up, double-tap = hang up. Below the status bar,
        // the panels (overlay) and the sim drawer, which are composed later and so win the hit test.
        val unpaired = orbState == OrbState.UNPAIRED
        Box(
            Modifier.fillMaxSize().pttTouch(
                onDown = { if (unpaired) onPttClick() else onPttDown() },
                onUp = { if (!unpaired) onPttUp() },
                onDoubleTap = if (unpaired) null else onHangUp,
                hit = { pos: Offset, size: IntSize ->
                    val k = size.width / DESIGN_WIDTH_PX
                    PttGeometry.inOrb(layout, pos.x / k, pos.y / k)
                },
            ),
        )
        StatusBar(
            mode, link, now.timeText(), now.dateText(), Modifier.align(Alignment.TopStart), onSimChipClick,
            callSinceMs = callSinceMs, stateWord = orbState.name, onEnd = onHangUp,
        )

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

        // Bottom 64 px caption / PTT bar (y 736-800): the pill is also a PTT target.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(px.dp(64f)), contentAlignment = Alignment.Center) {
            val hasCaption = caption != null
            val outline = if (latched) JarvisColors.StateListen else JarvisColors.CyanDim
            Box(
                Modifier
                    .width(px.dp(if (hasCaption) 1240f else 520f))
                    .height(px.dp(if (hasCaption) 64f else 48f))
                    .border(px.dp(if (latched) 3f else 2f), outline, RoundedCornerShape(px.dp(if (hasCaption) 32f else 24f)))
                    .pttTouch(
                        onDown = { if (unpaired) onPttClick() else onPttDown() },
                        onUp = { if (!unpaired) onPttUp() },
                    ),
                contentAlignment = if (hasCaption) Alignment.CenterStart else Alignment.Center,
            ) {
                if (caption != null) {
                    CaptionText(caption, sim = mode == Mode.SIM)
                } else {
                    val label = when (orbState) {
                        OrbState.IDLE -> "HOLD TO TALK"
                        OrbState.UNPAIRED -> "Pair to begin"
                        else -> orbState.name
                    }
                    Text(
                        label,
                        style = JarvisType.HudLabel.style(
                            when {
                                orbState == OrbState.UNPAIRED -> JarvisColors.WarnAmber
                                orbState == OrbState.IDLE -> JarvisColors.CyanBright
                                latched -> JarvisColors.StateListen
                                else -> JarvisColors.CyanMid
                            },
                        ),
                    )
                }
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
