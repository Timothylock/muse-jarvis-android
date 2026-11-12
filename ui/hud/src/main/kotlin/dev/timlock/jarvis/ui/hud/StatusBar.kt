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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.timlock.jarvis.core.model.monoMillis
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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

/** Right-edge clearance during a call, measured on the SM-T220 (proofs/m5-touch-hold.png). */
private const val PRIVACY_PILL_CLEARANCE_PX = 96f

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
    /** When the call became Ready (monotonic ms), 0 = no call: shows CALL mm:ss, the state word and END. */
    callSinceMs: Long = 0L,
    stateWord: String = "",
    onEnd: () -> Unit = {},
    /** §7.7 test input: developer flag on / a test WAV is being injected. Amber chip (never violet). */
    testInputEnabled: Boolean = false,
    testInputActive: Boolean = false,
    /** Tap on the gear glyph, or a long press (800 ms) anywhere on the bar, opens Settings (§4.5). */
    onSettings: () -> Unit = {},
) {
    val px = LocalPx.current
    val inCall = callSinceMs != 0L
    val base = JarvisType.StatusBar.style(JarvisColors.TextSecondary)
    Row(
        modifier.fillMaxWidth().height(px.dp(48f)).longPressObserver(800L, onSettings).padding(horizontal = px.dp(50f)),
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
        if (inCall) Text("CALL " + callClock(callSinceMs), style = base) else Text(time, style = base)
        if (testInputActive || (testInputEnabled && mode == Mode.REAL)) {
            Spacer(Modifier.width(px.dp(24f)))
            TestInputChip(active = testInputActive)
        }
        Spacer(Modifier.weight(1f))
        if (inCall) {
            Text(stateWord, style = JarvisType.StatusBar.style(JarvisColors.CyanBright))
            Spacer(Modifier.width(px.dp(24f)))
            Box(
                Modifier.height(px.dp(48f)).width(px.dp(88f)).clickable(onClick = onEnd),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.border(px.dp(1.5f), JarvisColors.ErrRed, RoundedCornerShape(px.dp(6f))).padding(horizontal = px.dp(16f), vertical = px.dp(4f)),
                    contentAlignment = Alignment.Center,
                ) { Text("END", style = JarvisType.StatusBar.style(JarvisColors.ErrRed)) }
            }
            // Android's mic privacy pill (system UI, ~x 1240-1312, y 0-32 while capturing) would cover END; keep it clear.
            Spacer(Modifier.width(px.dp(PRIVACY_PILL_CLEARANCE_PX)))
        } else {
            Text(date.uppercase(), style = base)
            Spacer(Modifier.width(px.dp(24f)))
            Box(
                Modifier.height(px.dp(48f)).width(px.dp(56f)).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Text("⚙", style = base) }
        }
    }
}

/** "mm:ss" since [sinceMs]; ticks once a second, and only while composed (i.e. while a call is open). */
@Composable
private fun callClock(sinceMs: Long): String {
    val nowMs by produceState(monoMillis(), sinceMs) {
        while (true) { value = monoMillis(); delay(1000) }
    }
    val secs = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(secs / 60, secs % 60)
}

/** Amber "TEST INPUT" chip: filled-ish and bright while injecting, outline and dim when only enabled (real mode). */
@Composable
fun TestInputChip(active: Boolean, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    val amber = JarvisColors.WarnAmber
    val shape = RoundedCornerShape(px.dp(6f))
    Box(
        modifier
            .border(px.dp(1.5f), if (active) amber else amber.copy(alpha = 0.6f), shape)
            .let { if (active) it.background(amber.copy(alpha = 0.20f), shape) else it }
            .padding(horizontal = px.dp(12f), vertical = px.dp(3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("TEST INPUT", style = JarvisType.StatusBar.style(if (active) amber else amber.copy(alpha = 0.7f)))
    }
}

/** Fires [onLongPress] after [holdMs] of a stationary press; observes in the Initial pass and never consumes. */
private fun Modifier.longPressObserver(holdMs: Long, onLongPress: () -> Unit): Modifier = pointerInput(holdMs) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val ended = withTimeoutOrNull(holdMs) {
            var released = false
            while (!released) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id }
                released = c == null || !c.pressed || (c.position - down.position).getDistance() > slop
            }
            true
        }
        if (ended == null) onLongPress()
    }
}
