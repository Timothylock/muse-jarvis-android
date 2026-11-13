package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/** What the import screen shows; the app maps its import status onto this (keeps :ui:hud free of app types). */
data class ImportScreenState(
    /** One short result line (empty before the first run). */
    val line: String = "",
    val lineIsProblem: Boolean = false,
    /** Shows the "REPLACE IDENTITY" confirm button (different identity already stored). */
    val showConfirm: Boolean = false,
    val busy: Boolean = false,
    /** Where the owner should put the files (absolute path shown as text). */
    val dropDir: String = "",
    /** After success: reminder to delete the other copy (§4.2.6 step 5). */
    val success: Boolean = false,
)

/** Minimal import-from-another-device screen (§4.2.6), reached from the pairing flow's "Import pairing instead". */
@Composable
fun ImportPairingScreen(
    state: ImportScreenState,
    onImport: () -> Unit,
    onConfirmReplace: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val px = LocalPx.current
    Column(
        modifier.fillMaxSize().padding(horizontal = px.dp(120f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(px.dp(22f), Alignment.CenterVertically),
    ) {
        Text("Import pairing", style = JarvisType.HudLabel.copy(sizePx = 40f).style(JarvisColors.TextPrimary))
        Text(
            "Push identity.json + pairing.json (+ sdk_token), or one bundle.json, to:",
            style = JarvisType.Body.style(JarvisColors.TextSecondary), textAlign = TextAlign.Center,
        )
        if (state.dropDir.isNotEmpty()) {
            Text(state.dropDir, style = JarvisType.MetricUnit.copy(sizePx = 22f).style(JarvisColors.CyanMid), textAlign = TextAlign.Center)
        }
        val shown = when {
            state.busy -> "Importing…"
            else -> state.line
        }
        if (shown.isNotEmpty()) {
            Text(
                shown,
                style = JarvisType.Body.style(if (state.lineIsProblem) JarvisColors.WarnAmber else JarvisColors.StateListen),
                textAlign = TextAlign.Center,
            )
        }
        if (state.success) {
            Text(
                "Delete the pairing copy on the other machine: only one host may hold this identity.",
                style = JarvisType.Body.style(JarvisColors.TextSecondary), textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(px.dp(24f))) {
            if (state.showConfirm) PillButton("REPLACE IDENTITY", JarvisColors.WarnAmber, onConfirmReplace)
            if (!state.success) PillButton("IMPORT", JarvisColors.CyanBright, onImport)
            PillButton(if (state.success) "DONE" else "BACK", JarvisColors.CyanBright, onBack)
        }
    }
}

/** §4.4 banner at the top of the content area. */
@Composable
fun PairingLostBanner(onRepair: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = px.dp(50f))
            .background(JarvisColors.PanelFill, RoundedCornerShape(px.dp(12f)))
            .border(px.dp(2f), JarvisColors.WarnAmber, RoundedCornerShape(px.dp(12f)))
            .padding(px.dp(18f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(px.dp(20f)),
    ) {
        Text(
            "Muse disconnected this display (token revoked or expired). Re-pair to continue.",
            style = JarvisType.Body.style(JarvisColors.TextPrimary),
            modifier = Modifier.weight(1f),
        )
        PillButton("RE-PAIR", JarvisColors.WarnAmber, onRepair)
        PillButton("LATER", JarvisColors.TextSecondary, onLater)
    }
}

@Composable
private fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    val px = LocalPx.current
    Box(
        Modifier
            .border(px.dp(2f), color, RoundedCornerShape(px.dp(24f)))
            .clickable(onClick = onClick)
            .padding(horizontal = px.dp(40f), vertical = px.dp(10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = JarvisType.HudLabel.style(color))
    }
}
