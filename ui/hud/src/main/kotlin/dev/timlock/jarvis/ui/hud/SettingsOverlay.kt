package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.QualityPref
import dev.timlock.jarvis.core.model.UserConfig

/** Read-only facts the overlay displays; the app resolves them (SDK diagnostics etc.). Never a token, only its hint. */
data class SettingsInfo(
    val mode: Mode?,
    val link: LinkState,
    val nodeId: String? = null,
    /** Masked token hint (e.g. last 4), or null. */
    val tokenHint: String? = null,
    val versionName: String = "",
    val sdkVersion: String? = null,
    val sdkCommit: String? = null,
    val perfHud: Boolean = false,
    val testInputEnabled: Boolean = false,
    /** One-line result of the last log export (null = none yet). */
    val exportStatus: String? = null,
)

private enum class Section(val label: String) {
    Connection("CONNECTION"), Voice("VOICE"), Display("DISPLAY"), Performance("PERFORMANCE"), Developer("DEVELOPER"),
}

/**
 * §4.5 Settings: full-screen overlay (stateless; the app owns config and wires the callbacks). Left section list,
 * right content pane that scrolls if needed. [onConfig] receives the full updated [UserConfig].
 */
@Composable
fun SettingsOverlay(
    info: SettingsInfo,
    config: UserConfig,
    onConfig: (UserConfig) -> Unit,
    onClose: () -> Unit,
    onMode: (Mode) -> Unit,
    onRepair: () -> Unit,
    onImport: () -> Unit,
    onUnpair: () -> Unit,
    onPerfHud: (Boolean) -> Unit,
    onTestInput: (Boolean) -> Unit,
    onOpenSimPanel: () -> Unit,
    onExportLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val px = LocalPx.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    val sim = info.mode == Mode.SIM
    Column(
        modifier
            .fillMaxSize()
            .background(JarvisColors.BgVoid)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = px.dp(50f)),
    ) {
        // Header
        Row(Modifier.fillMaxWidth().height(px.dp(96f)), verticalAlignment = Alignment.CenterVertically) {
            Text("SETTINGS", style = JarvisType.HudLabel.copy(sizePx = 34f).style(JarvisColors.CyanBright))
            if (sim) {
                Spacer(Modifier.width(px.dp(24f)))
                SimChip()
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .border(px.dp(2f), JarvisColors.CyanMid, RoundedCornerShape(px.dp(24f)))
                    .clickable(onClick = onClose)
                    .padding(horizontal = px.dp(32f), vertical = px.dp(8f)),
                contentAlignment = Alignment.Center,
            ) { Text("✕  CLOSE", style = JarvisType.HudLabel.copy(sizePx = 22f).style(JarvisColors.CyanMid)) }
        }
        Box(Modifier.fillMaxWidth().height(px.dp(1f)).background(JarvisColors.CyanDim))
        Row(Modifier.fillMaxSize()) {
            // Section list
            Column(Modifier.width(px.dp(290f)).fillMaxHeight().padding(top = px.dp(24f)), verticalArrangement = Arrangement.spacedBy(px.dp(8f))) {
                for (s in Section.entries) {
                    val sel = s.ordinal == section
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(px.dp(64f))
                            .background(if (sel) JarvisColors.CyanBright.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { section = s.ordinal },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(px.dp(4f)).fillMaxHeight().background(if (sel) JarvisColors.CyanBright else Color.Transparent))
                        Spacer(Modifier.width(px.dp(20f)))
                        Text(s.label, style = JarvisType.HudLabel.copy(sizePx = 24f).style(if (sel) JarvisColors.CyanBright else JarvisColors.TextSecondary))
                    }
                }
            }
            Box(Modifier.width(px.dp(1f)).fillMaxHeight().background(JarvisColors.GridFaint))
            // Content pane
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(start = px.dp(40f), top = px.dp(8f)).verticalScroll(rememberScrollState()),
            ) {
                when (Section.entries[section]) {
                    Section.Connection -> ConnectionPane(info, onMode, onRepair, onImport, onUnpair)
                    Section.Voice -> VoicePane(config, onConfig)
                    Section.Display -> DisplayPane(config, onConfig)
                    Section.Performance -> PerformancePane(info, config, onConfig, onPerfHud)
                    Section.Developer -> DeveloperPane(info, onTestInput, onOpenSimPanel, onExportLogs)
                }
                Spacer(Modifier.height(px.dp(24f)))
            }
        }
    }
}

// ---- panes ---------------------------------------------------------------------------------

@Composable
private fun ConnectionPane(info: SettingsInfo, onMode: (Mode) -> Unit, onRepair: () -> Unit, onImport: () -> Unit, onUnpair: () -> Unit) {
    val px = LocalPx.current
    val sim = info.mode == Mode.SIM
    var confirm by rememberSaveable { mutableStateOf(false) }
    SRow("Mode", if (sim) "Simulation never talks to Muse" else null) {
        Choice(listOf("Real" to Mode.REAL, "Simulation" to Mode.SIM), info.mode) { if (it != info.mode) onMode(it) }
    }
    SRow("Link") {
        if (sim) {
            SimBadge()
            Spacer(Modifier.width(px.dp(12f)))
            Text("Simulated", style = valueStyle(JarvisColors.TextPrimary))
        } else {
            Text("●  " + linkText(info.link), style = valueStyle(linkColor(info.link)))
        }
    }
    SRow("Node id") {
        Text(if (sim) "simulated" else info.nodeId ?: "—", style = monoStyle(JarvisColors.CyanMid))
    }
    SRow("Token") {
        Text(if (sim) "none (simulation)" else info.tokenHint?.let { "••••  $it" } ?: "—", style = monoStyle(JarvisColors.CyanMid))
    }
    Spacer(Modifier.height(px.dp(20f)))
    if (confirm && !sim) {
        Text(
            "Unpair this display? It forgets the token; the node identity is kept.",
            style = JarvisType.Body.copy(sizePx = 24f).style(JarvisColors.WarnAmber),
        )
        Spacer(Modifier.height(px.dp(14f)))
        Row(horizontalArrangement = Arrangement.spacedBy(px.dp(20f))) {
            ActionButton("CONFIRM UNPAIR", JarvisColors.ErrRed) { confirm = false; onUnpair() }
            ActionButton("CANCEL", JarvisColors.TextSecondary) { confirm = false }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(px.dp(20f))) {
            if (sim) {
                ActionButton("PAIRING WALKTHROUGH (SIM)", SimStyling.accent, onRepair)
            } else {
                ActionButton("RE-PAIR", JarvisColors.CyanBright, onRepair)
                ActionButton("IMPORT PAIRING", JarvisColors.CyanBright, onImport)
                ActionButton("UNPAIR", JarvisColors.ErrRed) { confirm = true }
            }
        }
    }
}

@Composable
private fun VoicePane(c: UserConfig, set: (UserConfig) -> Unit) {
    SRow("Primary path", "Used first; chat + speech takes over after repeated failures") {
        Text("Realtime (fallback: chat)", style = valueStyle(JarvisColors.TextPrimary))
    }
    SRow("Standby window", "Stay in the call after a reply (15-120 s)") {
        Stepper("${c.standbyMs / 1000} s", c.standbyMs > 15_000L, c.standbyMs < 120_000L,
            { set(c.copy(standbyMs = c.standbyMs - 15_000L)) }, { set(c.copy(standbyMs = c.standbyMs + 15_000L)) })
    }
    SRow("Think timeout", "Give up waiting for a reply after") {
        Choice(listOf("30 s" to 30_000L, "60 s" to 60_000L, "90 s" to 90_000L), c.thinkTimeoutMs) { set(c.copy(thinkTimeoutMs = it)) }
    }
    SRow("Mic gain") {
        Choice(listOf("−6 dB" to -6, "0 dB" to 0, "+6 dB" to 6), c.micGainDb) { set(c.copy(micGainDb = it)) }
    }
}

@Composable
private fun DisplayPane(c: UserConfig, set: (UserConfig) -> Unit) {
    SRow("Panel lifetime", "Default time a panel stays after a call") {
        Choice(
            listOf<Pair<String, Long?>>("60 s" to 60_000L, "2 min" to 120_000L, "5 min" to 300_000L, "Sticky" to null),
            c.panelTtlMs,
        ) { set(c.copy(panelTtlMs = it)) }
    }
    SRow("Max panels") {
        Choice(listOf("2" to 2, "3" to 3), c.maxPanels) { set(c.copy(maxPanels = it)) }
    }
    SRow("Clock") {
        Choice(listOf("12 h" to false, "24 h" to true), c.clock24h) { set(c.copy(clock24h = it)) }
    }
    SRow("Night dim", "Dims the screen after 10 min idle inside the window") {
        Choice(listOf("On" to true, "Off" to false), c.nightEnabled) { set(c.copy(nightEnabled = it)) }
    }
    SRow("Night starts") {
        Stepper(hhmm(c.nightStartMin), true, true,
            { set(c.copy(nightStartMin = (c.nightStartMin - 30).mod(1440))) }, { set(c.copy(nightStartMin = (c.nightStartMin + 30).mod(1440))) })
    }
    SRow("Night ends") {
        Stepper(hhmm(c.nightEndMin), true, true,
            { set(c.copy(nightEndMin = (c.nightEndMin - 30).mod(1440))) }, { set(c.copy(nightEndMin = (c.nightEndMin + 30).mod(1440))) })
    }
}

@Composable
private fun PerformancePane(info: SettingsInfo, c: UserConfig, set: (UserConfig) -> Unit, onPerfHud: (Boolean) -> Unit) {
    SRow("Quality", "Auto lets the governor pick a tier") {
        Choice(
            listOf("Auto" to QualityPref.AUTO, "A" to QualityPref.A, "B" to QualityPref.B, "C" to QualityPref.C),
            c.quality,
        ) { set(c.copy(quality = it)) }
    }
    SRow("Perf HUD") {
        Choice(listOf("On" to true, "Off" to false), info.perfHud, onPerfHud)
    }
}

@Composable
private fun DeveloperPane(info: SettingsInfo, onTestInput: (Boolean) -> Unit, onOpenSimPanel: () -> Unit, onExportLogs: () -> Unit) {
    val px = LocalPx.current
    val sim = info.mode == Mode.SIM
    if (sim) {
        SRow("Simulation panel", "Scenarios, faults and display presets") {
            ActionButton("OPEN", SimStyling.accent, onOpenSimPanel)
        }
    }
    SRow("Test input", "Allows WAV injection as mic input (adb, real mode)") {
        Choice(listOf("On" to true, "Off" to false), info.testInputEnabled, onTestInput)
    }
    SRow("Export logs", info.exportStatus ?: "Redacted log bundle (zip) saved to the app's export folder") {
        ActionButton("EXPORT", JarvisColors.CyanBright, onExportLogs)
    }
    Spacer(Modifier.height(px.dp(16f)))
    Text("ABOUT", style = JarvisType.HudLabel.copy(sizePx = 20f).style(JarvisColors.TextMuted))
    SRow("App version") { Text(info.versionName.ifEmpty { "—" }, style = monoStyle(JarvisColors.TextPrimary)) }
    SRow("SDK") {
        Text(listOfNotNull(info.sdkVersion, info.sdkCommit?.take(10)).joinToString("  ").ifEmpty { "—" }, style = monoStyle(JarvisColors.TextPrimary))
    }
}

// ---- building blocks -------------------------------------------------------------------------

@Composable
private fun valueStyle(color: Color) = JarvisType.Body.copy(sizePx = 24f).style(color)

@Composable
private fun monoStyle(color: Color) = JarvisType.MetricUnit.copy(sizePx = 22f).style(color)

private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private fun linkText(l: LinkState) = when (l) {
    is LinkState.Online -> if (l.vmName.isNotBlank()) "Online · ${l.vmName}" else "Online"
    LinkState.Connecting -> "Connecting"
    is LinkState.Offline -> "Offline"
    LinkState.Unpaired -> "Not paired"
    is LinkState.PairingLost -> "Pairing lost — re-pair"
}

/** Label (+ optional hint) on the left, control on the right, hairline below. */
@Composable
private fun SRow(label: String, hint: String? = null, control: @Composable () -> Unit) {
    val px = LocalPx.current
    Row(Modifier.fillMaxWidth().height(px.dp(84f)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = JarvisType.Body.copy(sizePx = 26f).style(JarvisColors.TextPrimary))
            if (hint != null) Text(hint, style = JarvisType.Caption.copy(sizePx = 18f).style(JarvisColors.TextMuted))
        }
        Row(verticalAlignment = Alignment.CenterVertically) { control() }
    }
    Box(Modifier.fillMaxWidth().height(px.dp(1f)).background(JarvisColors.GridFaint))
}

@Composable
private fun <T> Choice(options: List<Pair<String, T>>, selected: T?, onSelect: (T) -> Unit) {
    val px = LocalPx.current
    Row(horizontalArrangement = Arrangement.spacedBy(px.dp(10f))) {
        for ((label, value) in options) {
            val sel = value == selected
            val color = if (sel) JarvisColors.CyanBright else JarvisColors.TextSecondary
            Box(
                Modifier
                    .border(px.dp(2f), if (sel) JarvisColors.CyanBright else JarvisColors.CyanDim, RoundedCornerShape(px.dp(22f)))
                    .background(if (sel) JarvisColors.CyanBright.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(px.dp(22f)))
                    .clickable { onSelect(value) }
                    .padding(horizontal = px.dp(24f), vertical = px.dp(9f)),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = JarvisType.HudLabel.copy(sizePx = 22f).style(color)) }
        }
    }
}

@Composable
private fun Stepper(value: String, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    val px = LocalPx.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", canMinus, onMinus)
        Text(
            value,
            style = JarvisType.MetricUnit.copy(sizePx = 26f).style(JarvisColors.TextPrimary),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(px.dp(110f)),
        )
        StepButton("+", canPlus, onPlus)
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val px = LocalPx.current
    val color = if (enabled) JarvisColors.CyanBright else JarvisColors.CyanDim
    Box(
        Modifier
            .width(px.dp(64f)).height(px.dp(48f))
            .border(px.dp(2f), color, RoundedCornerShape(px.dp(24f)))
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = JarvisType.HudLabel.copy(sizePx = 28f).style(color)) }
}

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    val px = LocalPx.current
    Box(
        Modifier
            .border(px.dp(2f), color, RoundedCornerShape(px.dp(24f)))
            .clickable(onClick = onClick)
            .padding(horizontal = px.dp(28f), vertical = px.dp(10f)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = JarvisType.HudLabel.copy(sizePx = 22f).style(color)) }
}
