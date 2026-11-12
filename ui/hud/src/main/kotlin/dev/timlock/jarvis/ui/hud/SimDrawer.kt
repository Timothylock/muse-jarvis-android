package dev.timlock.jarvis.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import dev.timlock.jarvis.core.model.DisplayPreset
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.ScenarioInfo
import dev.timlock.jarvis.core.model.ScenarioRun
import dev.timlock.jarvis.core.model.SimFault
import dev.timlock.jarvis.core.model.SimLogEntry
import dev.timlock.jarvis.core.model.SimPairOutcome
import dev.timlock.jarvis.core.model.SimSettings
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

private val DrawerFill = Color(0xFF0A0F1A).copy(alpha = 0.92f)

private val OrbButtons = listOf(
    "IDLE" to OrbState.IDLE, "CONN" to OrbState.CONNECTING, "LISTEN" to OrbState.LISTENING,
    "THINK" to OrbState.THINKING, "SPEAK" to OrbState.SPEAKING, "STBY" to OrbState.STANDBY,
    "OFFL" to OrbState.OFFLINE, "ERR" to OrbState.ERROR, "UNPAIRED" to OrbState.UNPAIRED,
    "PAIRING" to OrbState.PAIRING,
)

private val InvalidPresets = listOf(
    "missing title" to DisplayPreset.INVALID_MISSING_TITLE,
    "7 metrics" to DisplayPreset.INVALID_7_METRICS,
    "long label" to DisplayPreset.INVALID_LONG_LABEL,
    "bad id" to DisplayPreset.INVALID_BAD_ID,
)

/**
 * §7.3 simulation control drawer. Stateless: every value comes in as a plain parameter and every action goes out
 * as a callback. Draw it above the panels and below the [SimFrame]. Tapping outside the drawer calls [onClose].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimDrawer(
    open: Boolean,
    scenarios: List<ScenarioInfo>,
    run: ScenarioRun,
    settings: SimSettings,
    forcedOrb: OrbState?,
    currentOrb: OrbState,
    perfHud: Boolean,
    log: List<SimLogEntry>,
    /** Short result line of the last display preset (ok / error text); null = none. */
    toast: String?,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
    onPause: (Boolean) -> Unit,
    onForceOrb: (OrbState?) -> Unit,
    onPreset: (DisplayPreset) -> Unit,
    onFault: (SimFault) -> Unit,
    onRepair: () -> Unit,
    onSettings: (SimSettings) -> Unit,
    onPerfHud: (Boolean) -> Unit,
    /** §7.1 SimLink: opens the pairing flow (P1) in sim. */
    onPairFlow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val px = LocalPx.current
    if (open) {
        // Transparent scrim: tap outside closes. The orb stays visible.
        Box(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } })
    }
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(tween(250)) { -it },
        exit = slideOutHorizontally(tween(250)) { -it },
        modifier = modifier,
    ) {
        var logOpen by remember { mutableStateOf(false) }
        var invalidOpen by remember { mutableStateOf(false) }
        Column(
            Modifier
                .width(px.dp(460f))
                .fillMaxHeight()
                .background(DrawerFill)
                .border(px.dp(2f), SimStyling.accent.copy(alpha = 0.6f))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(px.dp(18f)),
            verticalArrangement = Arrangement.spacedBy(px.dp(10f)),
        ) {
            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("◇ SIMULATION", style = JarvisType.HudLabel.style(SimStyling.accent))
                Spacer(Modifier.weight(1f))
                DrawerButton("Exit", onClick = onExit)
                Spacer(Modifier.width(px.dp(8f)))
                DrawerButton("×", onClick = onClose, minWidthPx = 48f)
            }

            // Scenarios
            Section("Scenario")
            Column(
                Modifier.fillMaxWidth().height(px.dp(220f))
                    .border(px.dp(1f), JarvisColors.CyanDim, RoundedCornerShape(px.dp(6f)))
                    .verticalScroll(rememberScrollState()),
            ) {
                if (scenarios.isEmpty()) Text("no scenarios", style = small(JarvisColors.TextMuted), modifier = Modifier.padding(px.dp(8f)))
                for (s in scenarios) {
                    val active = run.id == s.id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = px.dp(48f)).clickable { onPlay(s.id) }.padding(horizontal = px.dp(8f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.id + if (s.external) " *" else "", style = small(if (active) JarvisColors.CyanBright else JarvisColors.TextPrimary))
                            if (s.title.isNotBlank()) Text(s.title, style = mono(JarvisColors.TextMuted))
                        }
                        Text("▶", style = small(JarvisColors.CyanBright))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(px.dp(8f))) {
                val nowText = if (run.id == null) "Now: free play" else "Now: ${run.id} ${run.step}/${run.total}"
                Text(nowText, style = small(JarvisColors.TextSecondary), modifier = Modifier.weight(1f))
                DrawerButton("■", onClick = onStop, minWidthPx = 48f)
                DrawerButton("⏸", selected = run.paused, onClick = { onPause(!run.paused) }, minWidthPx = 48f)
            }
            if (run.id != null || run.asserts > 0 || run.failed > 0) {
                Text(
                    "asserts ${run.asserts - run.failed} ok / ${run.failed} failed",
                    style = mono(if (run.failed > 0) JarvisColors.ErrRed else JarvisColors.StateListen),
                )
            }

            // Orb state
            Section("Orb state")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(px.dp(6f)), verticalArrangement = Arrangement.spacedBy(px.dp(6f))) {
                for ((label, st) in OrbButtons) DrawerButton(label, selected = forcedOrb == st, onClick = { onForceOrb(st) })
                DrawerButton(if (forcedOrb == null) "auto ☑" else "auto ☐", selected = forcedOrb == null, onClick = {
                    onForceOrb(if (forcedOrb == null) currentOrb else null)
                })
            }

            // Display
            Section("Display")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(px.dp(6f)), verticalArrangement = Arrangement.spacedBy(px.dp(6f))) {
                DrawerButton("card") { onPreset(DisplayPreset.CARD) }
                DrawerButton("stats") { onPreset(DisplayPreset.STATS) }
                DrawerButton("list") { onPreset(DisplayPreset.LIST) }
                DrawerButton("timer") { onPreset(DisplayPreset.TIMER) }
                DrawerButton("clear all") { onPreset(DisplayPreset.CLEAR_ALL) }
                DrawerButton("flood x6") { onPreset(DisplayPreset.FLOOD_X6) }
                DrawerButton(if (invalidOpen) "invalid ▴" else "invalid ▾", selected = invalidOpen) { invalidOpen = !invalidOpen }
            }
            if (invalidOpen) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(px.dp(6f)), verticalArrangement = Arrangement.spacedBy(px.dp(6f))) {
                    for ((label, p) in InvalidPresets) DrawerButton(label) { onPreset(p) }
                }
            }
            if (toast != null) {
                Text(
                    toast,
                    style = mono(if (toast.startsWith("ok")) JarvisColors.StateListen else JarvisColors.WarnAmber),
                    maxLines = 2,
                )
            }

            // Faults
            Section("Faults")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(px.dp(6f)), verticalArrangement = Arrangement.spacedBy(px.dp(6f))) {
                DrawerButton("offline") { onFault(SimFault.OFFLINE) }
                DrawerButton("revoke") { onFault(SimFault.REVOKE) }
                DrawerButton("send✗") { onFault(SimFault.SEND_FAIL) }
                DrawerButton("admit timeout") { onFault(SimFault.ADMIT_TIMEOUT) }
                DrawerButton("dead call") { onFault(SimFault.SESSION_DEAD) }
                DrawerButton("re-pair", onClick = onRepair)
            }

            // Pairing (SimLink)
            Section("Pairing")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(px.dp(6f)), verticalArrangement = Arrangement.spacedBy(px.dp(6f))) {
                DrawerButton("pair flow ›", onClick = onPairFlow)
                DrawerButton(if (settings.pairInstant) "instant ☑" else "instant ☐", selected = settings.pairInstant) {
                    onSettings(settings.copy(pairInstant = !settings.pairInstant))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(px.dp(6f)), verticalArrangement = Arrangement.spacedBy(px.dp(6f))) {
                for (o in SimPairOutcome.entries) {
                    DrawerButton(o.wire, selected = settings.pairOutcome == o) { onSettings(settings.copy(pairOutcome = o)) }
                }
            }

            // Latency
            Section("Latency")
            LabeledSlider("admit ${fmtSec(settings.admitMs)}", settings.admitMs / 3000f) {
                onSettings(settings.copy(admitMs = (it * 3000f / 50f).roundToLong() * 50L))
            }
            LabeledSlider("think ${fmtSec(settings.thinkMs)}", settings.thinkMs / 6000f) {
                onSettings(settings.copy(thinkMs = (it * 6000f / 100f).roundToLong() * 100L))
            }
            // Time scale 0.25..4x on a log axis (log2 -2..2).
            LabeledSlider("time ×%.2f".format(settings.timescale), ((ln(settings.timescale.coerceIn(0.25f, 4f)) / ln(2f)) + 2f) / 4f) {
                val v = 2f.pow(it * 4f - 2f)
                onSettings(settings.copy(timescale = (v * 20f).roundToLong() / 20f))
            }

            // Perf HUD + log
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(px.dp(8f))) {
                DrawerButton(if (perfHud) "Perf HUD ☑" else "Perf HUD ☐", selected = perfHud) { onPerfHud(!perfHud) }
                DrawerButton(if (logOpen) "Event log ⌄" else "Event log ›", selected = logOpen) { logOpen = !logOpen }
            }
            if (logOpen) {
                // Timestamps are relative to the oldest retained entry (independent of the clock source).
                val t0 = log.minOfOrNull { it.atMs } ?: 0L
                Column(
                    Modifier.fillMaxWidth().height(px.dp(360f))
                        .border(px.dp(1f), JarvisColors.CyanDim, RoundedCornerShape(px.dp(6f)))
                        .verticalScroll(rememberScrollState()).padding(px.dp(6f)),
                ) {
                    for (e in log.sortedByDescending { it.atMs }.take(200)) {
                        Text("+%.1fs %s".format((e.atMs - t0) / 1000f, e.text), style = mono(JarvisColors.TextSecondary))
                    }
                    if (log.isEmpty()) Text("(empty)", style = mono(JarvisColors.TextMuted))
                }
            }
        }
    }
}

private fun fmtSec(ms: Long) = "%.2fs".format(ms / 1000f)

@Composable private fun small(c: Color) = JarvisType.StatusBar.copy(sizePx = 20f, tracking = 0.04f).style(c)
@Composable private fun mono(c: Color) = JarvisType.MetricUnit.copy(sizePx = 18f).style(c)

@Composable
private fun Section(title: String) {
    val px = LocalPx.current
    Row(Modifier.fillMaxWidth().padding(top = px.dp(6f)), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = JarvisType.HudLabel.copy(sizePx = 20f).style(JarvisColors.CyanMid))
        Spacer(Modifier.width(px.dp(8f)))
        Box(Modifier.weight(1f).height(px.dp(1f)).background(JarvisColors.CyanDim))
    }
}

@Composable
private fun DrawerButton(
    label: String,
    selected: Boolean = false,
    minWidthPx: Float = 0f,
    onClick: () -> Unit,
) {
    val px = LocalPx.current
    Box(
        Modifier
            .heightIn(min = px.dp(48f))
            .widthIn(min = px.dp(minWidthPx))
            .border(px.dp(1.5f), if (selected) JarvisColors.CyanBright else JarvisColors.CyanDim, RoundedCornerShape(px.dp(8f)))
            .background(if (selected) JarvisColors.CyanDim.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(px.dp(8f)))
            .clickable(onClick = onClick)
            .padding(horizontal = px.dp(12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = small(if (selected) JarvisColors.CyanHot else JarvisColors.CyanBright))
    }
}

@Composable
private fun LabeledSlider(label: String, fraction: Float, onFraction: (Float) -> Unit) {
    val px = LocalPx.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = mono(JarvisColors.TextSecondary), modifier = Modifier.width(px.dp(150f)))
        SimSlider(fraction.coerceIn(0f, 1f), onFraction, Modifier.weight(1f))
    }
}

/** Minimal cyan slider (0..1). 48 px tall touch area. */
@Composable
private fun SimSlider(fraction: Float, onFraction: (Float) -> Unit, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    var widthPx by remember { mutableStateOf(1) }
    val thumb = px.dp(26f)
    val density = LocalDensity.current
    Box(
        modifier
            .height(px.dp(48f))
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { onFraction((it.x / widthPx).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onFraction((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(px.dp(6f)).background(JarvisColors.CyanDim, RoundedCornerShape(px.dp(3f))))
        Box(Modifier.fillMaxWidth(fraction).height(px.dp(6f)).background(JarvisColors.CyanMid, RoundedCornerShape(px.dp(3f))))
        val thumbPx = with(density) { thumb.roundToPx() }
        Box(
            Modifier
                .offset { IntOffset((fraction * (widthPx - thumbPx)).toInt(), 0) }
                .size(thumb)
                .background(JarvisColors.CyanBright, RoundedCornerShape(50)),
        )
    }
}
