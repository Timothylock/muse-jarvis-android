package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode

fun linkColor(link: LinkState): Color = when (link) {
    is LinkState.Online -> JarvisColors.CyanBright
    LinkState.Connecting, is LinkState.Offline -> JarvisColors.WarnAmber
    LinkState.Unpaired, is LinkState.PairingLost -> JarvisColors.ErrRed
}

/** 48 px status bar (§2.3). */
@Composable
fun StatusBar(
    mode: Mode?,
    link: LinkState,
    time: String,
    date: String,
    modifier: Modifier = Modifier,
    /** Tap on the violet sim chip (opens the §7.3 drawer). */
    onSimChipClick: (() -> Unit)? = null,
) {
    val px = LocalPx.current
    val base = JarvisType.StatusBar.style(JarvisColors.TextSecondary)
    Row(
        modifier.fillMaxWidth().height(px.dp(48f)).padding(horizontal = px.dp(50f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode == Mode.SIM) {
            Box(
                Modifier.height(px.dp(48f)).let { if (onSimChipClick != null) it.clickable(onClick = onSimChipClick) else it },
                contentAlignment = Alignment.Center,
            ) { SimChip() }
        } else Text("◈ MUSE DESK", style = base)
        Spacer(Modifier.width(px.dp(24f)))
        Text(
            buildAnnotatedString {
                append("LINK ")
                withStyle(SpanStyle(color = linkColor(link))) { append("●") }
            },
            style = base,
        )
        Spacer(Modifier.width(px.dp(24f)))
        Text(time, style = base)
        Spacer(Modifier.weight(1f))
        Text(date.uppercase(), style = base)
        Spacer(Modifier.width(px.dp(24f)))
        Text("⚙", style = base)
    }
}
