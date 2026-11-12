package dev.timlock.jarvis.ui.hud.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.timlock.jarvis.core.model.ListItem
import dev.timlock.jarvis.core.model.Metric
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.ui.hud.JarvisColors
import dev.timlock.jarvis.ui.hud.JarvisType
import dev.timlock.jarvis.ui.hud.LocalPx
import dev.timlock.jarvis.ui.hud.style
import kotlinx.coroutines.delay
import kotlin.math.ceil

private const val ROW_H = 34f
private const val THIN_SPACE = " "

@Composable
internal fun PanelBody(
    panel: Panel,
    content: PanelContent,
    bodyH: Float,
    speaking: Boolean,
    onAcknowledgeTimer: () -> Unit,
    onMore: () -> Unit,
) {
    when (content) {
        is PanelContent.Card -> CardBody(content, onMore)
        is PanelContent.Stats -> StatsBody(content)
        is PanelContent.ListBody -> ListBody(panel, content, speaking)
        is PanelContent.Timer -> TimerBody(content, onAcknowledgeTimer)
    }
}

@Composable
private fun CardBody(card: PanelContent.Card, onMore: () -> Unit) {
    val px = LocalPx.current
    val body = JarvisType.Body.style(JarvisColors.TextPrimary)
    Column(Modifier.fillMaxSize().padding(horizontal = px.dp(24f))) {
        card.body?.let { Text(it, style = body, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        for (b in card.bullets.take(6)) Text("▸ $b", style = body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (card.moreBullets > 0) {
            Text(
                "+${card.moreBullets} more",
                style = JarvisType.Body.style(JarvisColors.TextSecondary),
                modifier = Modifier.clickable(onClick = onMore),
            )
        }
    }
}

@Composable
private fun StatsBody(stats: PanelContent.Stats) {
    val px = LocalPx.current
    val metrics = stats.metrics.take(6)
    val compact = metrics.size > 3
    val role = if (compact) JarvisType.MetricCompact else JarvisType.MetricStat
    val rows = metrics.chunked(3)
    Column(
        Modifier.fillMaxSize().padding(start = px.dp(24f), end = px.dp(24f), bottom = px.dp(8f)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            for (row in rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (m in row) Box(Modifier.weight(1f)) { MetricCell(m, role) }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        stats.caption?.let {
            Text(
                it,
                style = JarvisType.Caption.copy(sizePx = if (rows.size > 1) 20f else 24f).style(JarvisColors.TextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetricCell(m: Metric, role: dev.timlock.jarvis.ui.hud.TypeRole) {
    val px = LocalPx.current
    val valueStyle = role.style(JarvisColors.CyanBright).copy(lineHeight = px.sp(role.sizePx))
    val unitStyle = JarvisType.MetricUnit.style(JarvisColors.TextSecondary)
    Column {
        Text(
            buildAnnotatedString {
                append(m.value)
                m.unit?.let { u ->
                    if (!m.unitAttached) append(THIN_SPACE)
                    withStyle(SpanStyle(fontFamily = unitStyle.fontFamily, fontSize = unitStyle.fontSize, color = unitStyle.color)) { append(u) }
                }
            },
            style = valueStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
        Text(
            m.label.uppercase(),
            style = JarvisType.StatusBar.style(JarvisColors.TextSecondary).copy(lineHeight = px.sp(20f)),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListBody(panel: Panel, list: PanelContent.ListBody, speaking: Boolean) {
    val px = LocalPx.current
    val scroll = rememberScrollState()
    val rowDevice = px.device(ROW_H)

    // 600 ms left-edge highlight for rows new in this version (decided at first composition of this content).
    var highlight by remember {
        mutableStateOf(panel.newRows.isNotEmpty() && monoMillis() - panel.updatedAtMs < 600)
    }
    LaunchedEffect(Unit) {
        if (highlight) {
            delay((600 - (monoMillis() - panel.updatedAtMs)).coerceIn(1, 600))
            highlight = false
        }
    }

    // While speaking, auto-scroll one row every 2.5 s (only does anything when the list overflows).
    LaunchedEffect(speaking, list.items.size) {
        while (speaking) {
            delay(2500)
            if (scroll.maxValue > 0 && scroll.value < scroll.maxValue) {
                val next = (ceil(scroll.value / rowDevice).toInt() + (if (scroll.value % rowDevice == 0f) 1 else 0)) * rowDevice
                scroll.animateScrollTo(next.toInt().coerceAtMost(scroll.maxValue))
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = px.dp(24f))) {
        list.items.forEachIndexed { i, item -> ListRow(i, item, list.ordered, highlight && i in panel.newRows) }
    }
}

@Composable
private fun ListRow(index: Int, item: ListItem, ordered: Boolean, highlighted: Boolean) {
    val px = LocalPx.current
    val color = if (item.done) JarvisColors.TextMuted else JarvisColors.TextPrimary
    val style = JarvisType.Body.style(color)
    val marker = if (ordered) "${index + 1}." else if (item.level == 0) "▸" else "▹"
    Row(
        Modifier
            .fillMaxWidth()
            .height(px.dp(ROW_H))
            .drawBehind {
                if (highlighted) drawRect(JarvisColors.CyanBright, Offset(-px.device(24f), 0f), Size(px.device(3f), size.height))
            }
            .padding(start = px.dp(28f * item.level.coerceIn(0, 2))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, style = style, modifier = Modifier.width(px.dp(if (ordered) 48f else 34f)))
        Text(
            item.text,
            style = if (item.done) style.copy(textDecoration = TextDecoration.LineThrough) else style,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (item.done) Text("  ✓", style = style)
    }
}

@Composable
private fun TimerBody(timer: PanelContent.Timer, onAck: () -> Unit) {
    val px = LocalPx.current
    val now = remember { mutableLongStateOf(monoMillis()) }
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(timer.endsAtMs) {
        while (true) {
            val t = monoMillis()
            now.longValue = t
            val rem = timer.endsAtMs - t
            if (rem > 0) {
                blinkOn = true
                delay(((rem - 1) % 1000) + 1) // next whole-second boundary
            } else {
                blinkOn = !blinkOn // 1 Hz blink: 500 ms on / 500 ms off
                delay(500)
            }
        }
    }
    val rem = timer.endsAtMs - now.longValue
    val ringing = rem <= 0
    val secs = if (ringing) 0L else ceil(rem / 1000.0).toLong()
    Row(
        Modifier.fillMaxSize().padding(horizontal = px.dp(24f)).then(if (ringing) Modifier.clickable(onClick = onAck) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatCountdown(secs),
            style = JarvisType.TimerCountdown.style(if (ringing && !blinkOn) androidx.compose.ui.graphics.Color.Transparent else JarvisColors.CyanBright)
                .copy(lineHeight = px.sp(88f)),
            maxLines = 1, softWrap = false,
        )
        if (ringing) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text("TAP TO DISMISS", style = JarvisType.HudLabel.style(JarvisColors.WarnAmber))
            }
        }
    }
}

internal fun formatCountdown(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
