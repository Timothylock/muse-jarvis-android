package dev.timlock.jarvis.ui.hud.panels

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.timlock.jarvis.core.model.Panel
import dev.timlock.jarvis.core.model.monoMillis
import dev.timlock.jarvis.ui.hud.JarvisColors
import dev.timlock.jarvis.ui.hud.JarvisType
import dev.timlock.jarvis.ui.hud.LocalPx
import dev.timlock.jarvis.ui.hud.SimBadge
import dev.timlock.jarvis.ui.hud.simLeftEdge
import dev.timlock.jarvis.ui.hud.style
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TITLE_H = 56f
internal const val CHIP_TITLE_H = 30f

/** One panel: chrome + reveal + content crossfade + gestures. Fills its parent (sized to the panel). */
@Composable
internal fun PanelFrame(
    panel: Panel,
    h: Float,
    speaking: Boolean,
    interactive: Boolean,
    chip: Boolean = false,
    onDismiss: () -> Unit = {},
    onPin: () -> Unit = {},
    onExpand: () -> Unit = {},
    onAcknowledgeTimer: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    val px = LocalPx.current
    val scope = rememberCoroutineScope()

    // C9 arrival timeline: animates only while t < 700 ms at first composition, otherwise settled.
    val arrival = rememberArrival(panel.revealStartMs, interactive)

    // Touch affordances.
    val dragX = remember { Animatable(0f) }
    var xToken by remember { mutableIntStateOf(0) }
    var showX by remember { mutableStateOf(false) }
    LaunchedEffect(xToken) {
        if (xToken > 0) {
            showX = true
            delay(3000)
            showX = false
        }
    }

    var gestures: Modifier = Modifier
    if (interactive) {
        val dismissDist = px.device(120f)
        val flingVel = px.device(1200f)
        gestures = Modifier
            .graphicsLayer { translationX = dragX.value }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.changedToDown() }) xToken++
                    }
                }
            }
            .pointerInput(panel.collapsed, panel.pinned) {
                detectTapGestures(
                    onLongPress = { onPin() },
                    onTap = { if (panel.collapsed) onExpand() },
                )
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> scope.launch { dragX.snapTo((dragX.value + d).coerceAtLeast(0f)) } },
                onDragStopped = { v ->
                    if (dragX.value >= dismissDist || v >= flingVel) onDismiss()
                    else dragX.animateTo(0f, tween(150))
                },
            )
    }

    Box(Modifier.fillMaxSize().then(gestures)) {
        PanelChrome(
            panel, h, arrival, compact = chip,
            overlay = {
                if (showX && interactive) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(px.dp(48f)).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", style = JarvisType.HudLabel.copy(sizePx = 36f).style(JarvisColors.CyanBright))
                    }
                }
            },
        ) {
            if (!panel.collapsed) {
                Crossfade(targetState = panel.content, animationSpec = tween(250), label = "panel-content") { content ->
                    if (chip) ChipBody(content, onAcknowledgeTimer)
                    else PanelBody(panel, content, h - TITLE_H, speaking, onAcknowledgeTimer, onMore)
                }
            }
        }
    }
}

/**
 * Fill, 1 px border, corner brackets, title row (+pin, SIM badge) and a body slot. No shadows or blur.
 * [compact] = chip chrome (smaller brackets and title row). Appearance follows [arrival] (C9).
 */
@Composable
internal fun PanelChrome(
    panel: Panel,
    h: Float,
    arrival: Arrival,
    compact: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    body: @Composable BoxScope.() -> Unit,
) {
    val px = LocalPx.current
    val armPx = if (compact) 8f else 12f
    var m: Modifier = Modifier.fillMaxSize().drawBehind {
        val f = arrival.fill()
        val w = size.width
        val hh = size.height
        drawRect(JarvisColors.PanelFill.copy(alpha = JarvisColors.PanelFill.alpha * f))
        drawRect(JarvisColors.CyanDim.copy(alpha = f), Offset.Zero, size, style = Stroke(px.device(1f).coerceAtLeast(1f)))
        val ba = arrival.bracketAlpha()
        val arm = px.device(armPx) * arrival.bracketScale()
        if (ba > 0f && arm > 0f) {
            val sw = px.device(2f).coerceAtLeast(1.5f)
            val c = JarvisColors.CyanMid.copy(alpha = ba)
            for ((cx, dx) in listOf(0f to 1f, w to -1f)) for ((cy, dy) in listOf(0f to 1f, hh to -1f)) {
                drawLine(c, Offset(cx, cy), Offset(cx + dx * arm, cy), sw)
                drawLine(c, Offset(cx, cy), Offset(cx, cy + dy * arm), sw)
            }
        }
    }
    if (panel.sim) m = m.simLeftEdge(px)
    Box(m) {
        val reveal = if (arrival.running) Modifier.revealMask(arrival::mask, px.device(2f), px.device(24f)) else Modifier
        Box(Modifier.fillMaxSize().then(reveal)) {
            Column(Modifier.fillMaxSize()) {
                TitleRow(panel, compact)
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), content = body)
            }
        }
        overlay()
    }
}

@Composable
private fun TitleRow(panel: Panel, compact: Boolean) {
    val px = LocalPx.current
    Row(
        Modifier.fillMaxWidth().height(px.dp(if (compact) CHIP_TITLE_H else TITLE_H)).padding(horizontal = px.dp(if (compact) 12f else 24f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            panel.title.uppercase(),
            style = (if (compact) JarvisType.StatusBar else JarvisType.HudLabel).style(JarvisColors.CyanMid),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (panel.pinned) {
            Spacer(Modifier.width(px.dp(12f)))
            PinGlyph()
        }
        if (panel.sim) {
            Spacer(Modifier.width(px.dp(12f)))
            SimBadge()
        }
    }
}

@Composable
private fun PinGlyph() {
    val px = LocalPx.current
    Canvas(Modifier.size(px.dp(16f), px.dp(26f))) {
        val w = size.width
        drawCircle(JarvisColors.CyanBright, radius = w * 0.4f, center = Offset(w / 2f, w * 0.45f))
        drawLine(JarvisColors.CyanBright, Offset(w / 2f, w * 0.8f), Offset(w / 2f, size.height), px.device(2f))
    }
}

/** Top->bottom alpha mask with a scan line at the sweep edge; [p] 0..1. */
private fun Modifier.revealMask(p: () -> Float, lineWidth: Float, soft: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        val t = p()
        drawContent()
        if (t < 1f) {
            val edge = size.height * t
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = edge - soft, endY = edge),
                Offset.Zero, Size(size.width, size.height), blendMode = BlendMode.DstIn,
            )
            if (t > 0f) {
                // 24 px gradient tail trailing the 2 px scan line.
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, JarvisColors.CyanBright.copy(alpha = 0.35f)),
                        startY = edge - soft, endY = edge,
                    ),
                    Offset(0f, (edge - soft).coerceAtLeast(0f)), Size(size.width, edge - (edge - soft).coerceAtLeast(0f)),
                )
                drawLine(JarvisColors.CyanBright, Offset(0f, edge), Offset(size.width, edge), lineWidth)
            }
        }
    }
