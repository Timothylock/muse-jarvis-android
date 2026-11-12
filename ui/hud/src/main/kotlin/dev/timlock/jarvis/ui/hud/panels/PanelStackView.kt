package dev.timlock.jarvis.ui.hud.panels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.PanelContent
import dev.timlock.jarvis.core.model.PanelStack
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.ui.hud.JarvisColors
import dev.timlock.jarvis.ui.hud.JarvisType
import dev.timlock.jarvis.ui.hud.LocalPx
import dev.timlock.jarvis.ui.hud.style
import kotlinx.coroutines.delay
import kotlin.math.sqrt

internal const val ZONE_X = 625f
internal const val ZONE_Y = 64f
internal const val ZONE_W = 665f
internal const val ZONE_H = 656f
internal const val GAP = 16f
internal const val COLLAPSED_H = 56f

private const val ORB_CX = 305f
private const val ORB_CY = 392f
private const val ORB_R = 220f

internal val EaseOutPanels = CubicBezierEasing(0f, 0f, 0.2f, 1f)

private class Slot(val panel: Panel, val y: Float, val h: Float)
private class Departing(val panel: Panel, val y: Float, val h: Float)

/**
 * §2.5 HUD panel stack: absolute-positioned panels in the zone x 625-1290, y 64-720, newest at the top,
 * leader lines from the docked orb, exit animations, "Panel replaced" toast, "+N more" overlay.
 * Fills its parent (the full 1340x800 design canvas) and passes through touches outside the panels.
 */
@Composable
fun PanelStackView(
    stack: PanelStack,
    layoutDocked: Boolean,
    layoutSinceMs: Long,
    speaking: Boolean,
    heightPx: (Panel) -> Int,
    onDismiss: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onExpand: (String) -> Unit,
    onAcknowledgeTimer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val px = LocalPx.current

    // Layout: newest (index 0) at the top, 16 px gaps. Collapsed panels are 56 px strips.
    val slots = ArrayList<Slot>(stack.panels.size)
    var cursor = ZONE_Y
    for (p in stack.panels) {
        val h = if (p.collapsed) COLLAPSED_H else heightPx(p).toFloat()
        slots.add(Slot(p, cursor, h))
        cursor += h + GAP
    }

    // Departing panels: kept rendered locally until their 400 ms exit finishes. The list is a plain holder
    // (mutated during composition, idempotently) plus a tick state to force recomposition on removal.
    val holder = remember { Holder() }
    var tick by remember { mutableIntStateOf(0) }
    val liveIds = HashSet<String>()
    for (s in slots) liveIds.add(s.panel.id)
    holder.departing.removeAll { it.panel.id in liveIds }
    for (old in holder.prev) {
        if (old.panel.id !in liveIds && holder.departing.none { it.panel.id == old.panel.id }) {
            holder.departing.add(Departing(old.panel, old.y, old.h))
        }
    }
    SideEffect { holder.prev = slots }
    @Suppress("UNUSED_EXPRESSION") tick // read so removal recomposes

    var overlayId by remember { mutableStateOf<String?>(null) }
    if (overlayId != null && overlayId !in liveIds) overlayId = null

    Box(modifier.fillMaxSize()) {
        LeaderLines(slots, layoutDocked, layoutSinceMs)

        for (d in holder.departing.toList()) {
            key("dep-${d.panel.id}-${d.panel.version}") {
                DepartingPanel(d) {
                    holder.departing.remove(d)
                    tick++
                }
            }
        }

        for (s in slots) {
            key(s.panel.id) {
                PanelItem(
                    panel = s.panel,
                    targetY = s.y,
                    h = s.h,
                    speaking = speaking,
                    onDismiss = { onDismiss(s.panel.id) },
                    onPin = { onPin(s.panel.id, !s.panel.pinned) },
                    onExpand = { onExpand(s.panel.id) },
                    onAcknowledgeTimer = { onAcknowledgeTimer(s.panel.id) },
                    onMore = { overlayId = s.panel.id },
                )
            }
        }

        val ov = stack.panels.firstOrNull { it.id == overlayId }
        if (ov != null) MoreOverlay(ov) { overlayId = null }

        ReplacedToast(stack.replacedToastAtMs)
    }
}

private class Holder {
    var prev: List<Slot> = emptyList()
    val departing = ArrayList<Departing>()
}

@Composable
private fun DepartingPanel(d: Departing, onDone: () -> Unit) {
    val px = LocalPx.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(400, easing = LinearEasing))
        onDone()
    }
    Box(
        Modifier
            .offset(px.dp(ZONE_X), 0.dp)
            .width(px.dp(ZONE_W))
            .height(px.dp(d.h))
            .graphicsLayer {
                translationY = px.device(d.y)
                translationX = px.device(80f) * p.value
                alpha = 1f - p.value
            },
    ) {
        PanelFrame(d.panel, d.h, speaking = false, interactive = false)
    }
}

@Composable
private fun PanelItem(
    panel: Panel,
    targetY: Float,
    h: Float,
    speaking: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onExpand: () -> Unit,
    onAcknowledgeTimer: () -> Unit,
    onMore: () -> Unit,
) {
    val px = LocalPx.current
    val y = remember { Animatable(targetY) }
    LaunchedEffect(targetY) {
        if (y.value != targetY) y.animateTo(targetY, tween(350, easing = EaseOutPanels))
    }
    Box(
        Modifier
            .offset(px.dp(ZONE_X), 0.dp)
            .width(px.dp(ZONE_W))
            .height(px.dp(h))
            .graphicsLayer { translationY = px.device(y.value) },
    ) {
        PanelFrame(
            panel, h, speaking, interactive = true,
            onDismiss = onDismiss, onPin = onPin, onExpand = onExpand,
            onAcknowledgeTimer = onAcknowledgeTimer, onMore = onMore,
        )
    }
}

/** Leader lines (§2.5): orb L1 edge -> panel left-edge midpoint. Animated only while a phase is running. */
@Composable
private fun LeaderLines(slots: List<Slot>, docked: Boolean, layoutSinceMs: Long) {
    val px = LocalPx.current
    val clock = remember { mutableLongStateOf(monoMillis()) }
    // Latest animation end across panels and the layout fade-in.
    var endAt = 0L
    for (s in slots) endAt = maxOf(endAt, s.panel.createdAtMs + LEAD_FADE_END)
    endAt = maxOf(endAt, layoutSinceMs + LAYOUT_DELAY + LAYOUT_FADE)
    val key = slots.joinToString { "${it.panel.id}@${it.panel.createdAtMs}" } + docked + layoutSinceMs
    LaunchedEffect(key) {
        if (!docked) return@LaunchedEffect
        while (monoMillis() < endAt) {
            withFrameMillis { clock.longValue = monoMillis() }
        }
        clock.longValue = monoMillis()
    }
    if (!docked || slots.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val now = clock.longValue
        val sinceLayout = now - layoutSinceMs
        if (sinceLayout < LAYOUT_DELAY) return@Canvas
        val layoutAlpha = ((sinceLayout - LAYOUT_DELAY) / LAYOUT_FADE.toFloat()).coerceIn(0f, 1f)
        for (s in slots) {
            val age = now - s.panel.createdAtMs
            val draw = (age / 300f).coerceIn(0f, 1f)
            val fade = if (age < LEAD_FADE_START) 1f
            else 1f - 0.75f * ((age - LEAD_FADE_START) / (LEAD_FADE_END - LEAD_FADE_START).toFloat()).coerceIn(0f, 1f)
            val tx = ZONE_X
            val ty = s.y + s.h / 2f
            val dx = tx - ORB_CX
            val dy = ty - ORB_CY
            val len = sqrt(dx * dx + dy * dy)
            val sx = ORB_CX + ORB_R * dx / len
            val sy = ORB_CY + ORB_R * dy / len
            val ex = sx + (tx - sx) * draw
            val ey = sy + (ty - sy) * draw
            drawLine(
                JarvisColors.CyanMid.copy(alpha = layoutAlpha * fade),
                Offset(px.device(sx), px.device(sy)),
                Offset(px.device(ex), px.device(ey)),
                strokeWidth = px.device(1f).coerceAtLeast(1f),
            )
        }
    }
}

private const val LEAD_FADE_START = 2000L
private const val LEAD_FADE_END = 2500L
private const val LAYOUT_DELAY = 600L
private const val LAYOUT_FADE = 300L

@Composable
private fun ReplacedToast(atMs: Long) {
    val px = LocalPx.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(atMs) {
        val remaining = if (atMs == 0L) 0L else 1500L - (monoMillis() - atMs)
        if (remaining > 0) {
            visible = true
            delay(remaining)
        }
        visible = false
    }
    if (!visible) return
    Box(
        Modifier
            .offset(px.dp(ZONE_X + 150f), px.dp(ZONE_Y + ZONE_H - 44f))
            .width(px.dp(ZONE_W - 300f))
            .height(px.dp(40f))
            .background(JarvisColors.PanelFill)
            .border(px.dp(1f), JarvisColors.CyanDim),
        contentAlignment = Alignment.Center,
    ) {
        Text("PANEL REPLACED", style = JarvisType.StatusBar.style(JarvisColors.CyanBright), textAlign = TextAlign.Center)
    }
}

/** Simple full-height overlay for a card's "+N more" (shows what the model has). */
@Composable
private fun MoreOverlay(panel: Panel, onClose: () -> Unit) {
    val px = LocalPx.current
    val card = panel.content as? PanelContent.Card ?: return
    Box(
        Modifier
            .offset(px.dp(ZONE_X), px.dp(ZONE_Y))
            .width(px.dp(ZONE_W))
            .height(px.dp(ZONE_H))
            .clickable(onClick = onClose),
    ) {
        PanelChrome(panel, ZONE_H, bracketProgress = { 1f }, fillProgress = { 1f }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(px.dp(24f))
                    .verticalScroll(rememberScrollState()),
            ) {
                card.body?.let { Text(it, style = JarvisType.Body.style(JarvisColors.TextPrimary)) }
                for (b in card.bullets) Text("▸ $b", style = JarvisType.Body.style(JarvisColors.TextPrimary))
                if (card.moreBullets > 0) {
                    Text("+${card.moreBullets} more not shown", style = JarvisType.Body.style(JarvisColors.TextSecondary))
                }
                Text("TAP TO CLOSE", style = JarvisType.StatusBar.style(JarvisColors.TextMuted))
            }
        }
    }
}
