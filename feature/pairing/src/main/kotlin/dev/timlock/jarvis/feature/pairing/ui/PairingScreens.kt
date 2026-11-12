package dev.timlock.jarvis.feature.pairing.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import dev.timlock.jarvis.core.transport.PairingStage
import dev.timlock.jarvis.ui.hud.JarvisColors
import dev.timlock.jarvis.ui.hud.JarvisType
import dev.timlock.jarvis.ui.hud.LocalPx
import dev.timlock.jarvis.ui.hud.SimBadge
import dev.timlock.jarvis.ui.hud.style

/** §4.2 pairing screens P1-P4, driven by [flow]. Drawn over the HUD; in P3 the backdrop is clear so the 260 px orb shows. */
@Composable
fun PairingFlowScreen(flow: PairingFlow, modifier: Modifier = Modifier) {
    val st by flow.state.collectAsState()
    WindowAwake(active = st.step == PairStep.WINDOW)
    val backdrop = if (st.step == PairStep.WINDOW) Color.Transparent else JarvisColors.BgVoid.copy(alpha = 0.9f)
    Column(
        modifier
            .fillMaxSize()
            .background(backdrop)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Header(st.step, flow.sim, onCancel = flow::cancel)
        when (st.step) {
            PairStep.TOKEN -> TokenStep(st, flow)
            PairStep.BLUETOOTH -> BluetoothStep(st, flow)
            PairStep.WINDOW -> WindowStep(st)
            PairStep.RESULT -> ResultStep(st, flow)
        }
    }
}

// ---- shared pieces --------------------------------------------------------------------------------

@Composable
private fun Header(step: PairStep, sim: Boolean, onCancel: () -> Unit) {
    val px = LocalPx.current
    val title = when (step) {
        PairStep.TOKEN -> "◈ PAIRING  ·  STEP 1 OF 3"
        PairStep.BLUETOOTH -> "◈ PAIRING  ·  STEP 2 OF 3"
        PairStep.WINDOW -> "◈ PAIRING  ·  STEP 3 OF 3"
        PairStep.RESULT -> "◈ PAIRING  ·  RESULT"
    }
    Row(
        Modifier.fillMaxWidth().height(px.dp(64f)).padding(horizontal = px.dp(50f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = JarvisType.HudLabel.style(JarvisColors.CyanBright))
        if (sim) {
            Spacer(Modifier.width(px.dp(16f)))
            SimBadge()
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.heightIn(min = px.dp(48f)).clickable(onClick = onCancel).padding(horizontal = px.dp(8f)),
            contentAlignment = Alignment.Center,
        ) { Text("✕ CANCEL", style = JarvisType.HudLabel.copy(sizePx = 22f).style(JarvisColors.TextSecondary)) }
    }
    Box(Modifier.fillMaxWidth().height(px.dp(1.5f)).background(JarvisColors.CyanDim))
}

@Composable
private fun FlowButton(label: String, color: Color, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val px = LocalPx.current
    val c = if (enabled) color else color.copy(alpha = 0.35f)
    val shape = RoundedCornerShape(px.dp(26f))
    Box(
        Modifier
            .heightIn(min = px.dp(52f))
            .background(if (filled && enabled) color.copy(alpha = 0.16f) else Color.Transparent, shape)
            .border(px.dp(2f), c, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = px.dp(36f), vertical = px.dp(8f)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = JarvisType.HudLabel.style(c)) }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    val px = LocalPx.current
    Text(
        text,
        style = JarvisType.Body.style(JarvisColors.TextSecondary),
        modifier = Modifier.heightIn(min = px.dp(48f)).clickable(onClick = onClick).padding(horizontal = px.dp(4f), vertical = px.dp(8f)),
    )
}

@Composable private fun Title(text: String) = Text(text, style = JarvisType.HudLabel.copy(sizePx = 34f).style(JarvisColors.TextPrimary))
@Composable private fun BodyText(text: String, color: Color = JarvisColors.TextSecondary, modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(text, style = JarvisType.Body.style(color), modifier = modifier, textAlign = align)

// ---- P1 -------------------------------------------------------------------------------------------

/** Shows a leading "mgst_" and masks the rest: the full token is never rendered. */
private object MaskedToken : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val shown = if (t.startsWith("mgst_")) 5 else 0
        return TransformedText(AnnotatedString(t.take(shown) + "•".repeat(t.length - shown)), OffsetMapping.Identity)
    }
}

@Composable
private fun TokenStep(st: FlowState, flow: PairingFlow) {
    val px = LocalPx.current
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(st.focusRequest) {
        if (st.focusRequest > 0) try { focus.requestFocus() } catch (_: Exception) { }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = px.dp(50f), vertical = px.dp(26f)),
        verticalArrangement = Arrangement.spacedBy(px.dp(14f)),
    ) {
        Title("Gadget token")
        BodyText("Create one at  gadgets.muse.ai  → Tokens → New. It starts “mgst_”.")
        if (st.showSavedCard) {
            Row(
                Modifier
                    .padding(top = px.dp(10f))
                    .fillMaxWidth()
                    .background(JarvisColors.PanelFill, RoundedCornerShape(px.dp(12f)))
                    .border(px.dp(2f), JarvisColors.CyanDim, RoundedCornerShape(px.dp(12f)))
                    .padding(px.dp(22f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(px.dp(20f)),
            ) {
                Text(
                    "Token saved (mgst_…${st.storedLast4})",
                    style = JarvisType.MetricUnit.style(JarvisColors.CyanHot),
                    modifier = Modifier.weight(1f),
                )
                FlowButton("USE", JarvisColors.CyanBright, filled = true, onClick = flow::useStored)
                FlowButton("REPLACE", JarvisColors.TextSecondary, onClick = flow::replaceStored)
            }
            st.tokenNote?.let { BodyText(it, JarvisColors.WarnAmber) }
        } else {
            val border = if (st.entered.isNotEmpty() && !st.enteredValid) JarvisColors.WarnAmber else JarvisColors.CyanMid
            Row(
                Modifier
                    .padding(top = px.dp(10f))
                    .fillMaxWidth()
                    .heightIn(min = px.dp(72f))
                    .background(JarvisColors.BgDeep, RoundedCornerShape(px.dp(10f)))
                    .border(px.dp(2f), border, RoundedCornerShape(px.dp(10f)))
                    .padding(start = px.dp(22f), end = px.dp(12f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (st.entered.isEmpty()) {
                        Text("mgst_…", style = JarvisType.MetricUnit.copy(sizePx = 28f).style(JarvisColors.TextMuted))
                    }
                    BasicTextField(
                        value = st.entered,
                        onValueChange = flow::onTokenChanged,
                        singleLine = true,
                        visualTransformation = MaskedToken,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        textStyle = JarvisType.MetricUnit.copy(sizePx = 28f).style(JarvisColors.CyanHot),
                        cursorBrush = SolidColor(JarvisColors.CyanBright),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                FlowButton("PASTE", JarvisColors.CyanBright) {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let(flow::onTokenChanged)
                }
            }
            when {
                st.entered.isEmpty() -> BodyText(" ", JarvisColors.TextMuted)
                st.enteredValid -> BodyText("✓ Format looks valid", JarvisColors.StateListen)
                else -> BodyText("Not a valid token — copy it again from gadgets.muse.ai", JarvisColors.WarnAmber)
            }
            st.tokenNote?.let { BodyText(it, JarvisColors.WarnAmber) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (st.storedLast4 != null) LinkText("Token already saved? (skip)", onClick = flow::useStored)
                Spacer(Modifier.weight(1f))
                FlowButton("NEXT ›", JarvisColors.CyanBright, enabled = st.enteredValid, filled = true, onClick = flow::next)
            }
        }
        LinkText("Import pairing from another device ›", onClick = flow::importInstead)
    }
}

// ---- P2 -------------------------------------------------------------------------------------------

@Composable
private fun BluetoothStep(st: FlowState, flow: PairingFlow) {
    val px = LocalPx.current
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val askPerms = {
        if (Build.VERSION.SDK_INT >= 31) {
            perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
        }
    }
    // §4.1 step 4: ask for the Bluetooth permissions contextually, once, when this step finds them missing.
    var asked by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(st.readiness.permissions) {
        if (st.readiness.permissions == CheckStatus.FAIL && !asked) { asked = true; askPerms() }
    }
    val r = st.readiness
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = px.dp(50f), vertical = px.dp(26f)),
        verticalArrangement = Arrangement.spacedBy(px.dp(14f)),
    ) {
        Title("Bluetooth")
        BodyText("Checking that this tablet can pair with your phone.")
        Spacer(Modifier.height(px.dp(6f)))
        CheckRow(r.bluetooth, "Bluetooth is on", if (r.bluetooth == CheckStatus.FAIL) "TURN ON" else null) {
            try { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { }
        }
        CheckRow(r.permissions, "Bluetooth permission granted", if (r.permissions == CheckStatus.FAIL) "ALLOW" else null) { askPerms() }
        CheckRow(r.advertising, "This tablet can advertise over Bluetooth", null) { }
        if (r.advertising == CheckStatus.FAIL) {
            BodyText("This system can't advertise over Bluetooth.", JarvisColors.WarnAmber)
            r.advertisingDetail?.let {
                Text("(${it.take(40)})", style = JarvisType.MetricUnit.copy(sizePx = 18f).style(JarvisColors.TextMuted))
            }
            FlowButton("IMPORT PAIRING", JarvisColors.WarnAmber, filled = true, onClick = flow::importInstead)
        } else {
            LinkText("Import pairing from another device ›", onClick = flow::importInstead)
        }
    }
}

@Composable
private fun CheckRow(status: CheckStatus, label: String, action: String?, onAction: () -> Unit) {
    val px = LocalPx.current
    val (glyph, color) = when (status) {
        CheckStatus.OK -> "✓" to JarvisColors.StateListen
        CheckStatus.FAIL -> "✕" to JarvisColors.WarnAmber
        CheckStatus.PENDING -> "…" to JarvisColors.TextMuted
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = px.dp(56f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(px.dp(18f)),
    ) {
        Text(glyph, style = JarvisType.HudLabel.copy(sizePx = 32f).style(color), modifier = Modifier.width(px.dp(40f)), textAlign = TextAlign.Center)
        BodyText(label, if (status == CheckStatus.PENDING) JarvisColors.TextMuted else JarvisColors.TextPrimary, Modifier.weight(1f))
        if (action != null) FlowButton(action, JarvisColors.CyanBright, onClick = onAction)
    }
}

// ---- P3 -------------------------------------------------------------------------------------------

@Composable
private fun WindowStep(st: FlowState) {
    val px = LocalPx.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The orb (PAIRING layout: center (670,280), ring 260 px, drawn extent 1.6 R = 208 px) occupies
        // y 72..488 of the design canvas, just below the 64 px header; this column starts under the header.
        Spacer(Modifier.height(px.dp(424f)))
        BodyText("On your phone: Muse app → Settings → Devices → tap + → choose", JarvisColors.TextPrimary)
        Spacer(Modifier.height(px.dp(14f)))
        Box(
            Modifier
                .background(JarvisColors.PanelFill, RoundedCornerShape(px.dp(10f)))
                .border(px.dp(2f), JarvisColors.CyanMid, RoundedCornerShape(px.dp(10f)))
                .padding(horizontal = px.dp(48f), vertical = px.dp(14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                st.bleName ?: "Starting Bluetooth…",
                style = JarvisType.HudLabel.copy(sizePx = 40f, tracking = 0.04f).style(if (st.bleName != null) JarvisColors.CyanHot else JarvisColors.TextMuted),
            )
        }
        Spacer(Modifier.height(px.dp(22f)))
        Row(horizontalArrangement = Arrangement.spacedBy(px.dp(36f)), verticalAlignment = Alignment.CenterVertically) {
            val labels = listOf("Advertising", "Phone connected", "Secure session", "Saved")
            check(labels.size == PairingStage.ORDER.size)
            labels.forEachIndexed { i, label ->
                val reached = i < st.stagesReached
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(px.dp(10f))) {
                    Box(
                        Modifier
                            .size(px.dp(18f))
                            .clip(CircleShape)
                            .background(if (reached) JarvisColors.CyanBright else Color.Transparent)
                            .border(px.dp(2f), if (reached) JarvisColors.CyanBright else JarvisColors.CyanDim, CircleShape),
                    )
                    Text(label, style = JarvisType.HudLabel.copy(sizePx = 24f).style(if (reached) JarvisColors.TextPrimary else JarvisColors.TextMuted))
                }
            }
        }
        Spacer(Modifier.height(px.dp(20f)))
        Text(
            st.remainingS?.let { "Window closes in ${formatCountdown(it)}" } ?: " ",
            style = JarvisType.MetricUnit.style(JarvisColors.CyanMid),
        )
    }
}

// ---- P4 -------------------------------------------------------------------------------------------

@Composable
private fun ResultStep(st: FlowState, flow: PairingFlow) {
    val px = LocalPx.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = px.dp(120f), vertical = px.dp(26f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(px.dp(22f), Alignment.CenterVertically),
    ) {
        when (st.result) {
            ResultKind.PAIRED -> {
                Text("✓ PAIRED", style = JarvisType.HudLabel.copy(sizePx = 56f).style(JarvisColors.StateListen))
                BodyText(st.nodeId?.let { "Paired as $it." } ?: "Paired.", JarvisColors.TextPrimary, align = TextAlign.Center)
                BodyText("Continuing…", JarvisColors.TextMuted)
                FlowButton("CONTINUE", JarvisColors.CyanBright, filled = true, onClick = flow::continueAfterResult)
            }
            ResultKind.ALREADY_PAIRED -> {
                Text("ALREADY PAIRED", style = JarvisType.HudLabel.copy(sizePx = 48f).style(JarvisColors.StateListen))
                BodyText("This display is already paired with Muse.", JarvisColors.TextPrimary, align = TextAlign.Center)
                FlowButton("CONTINUE", JarvisColors.CyanBright, filled = true, onClick = flow::continueAfterResult)
            }
            ResultKind.WINDOW_CLOSED -> {
                Text("Pairing timed out.", style = JarvisType.HudLabel.copy(sizePx = 48f).style(JarvisColors.WarnAmber))
                BodyText("The setup window closed before your phone finished.", JarvisColors.TextSecondary, align = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(px.dp(24f))) {
                    FlowButton("TRY AGAIN", JarvisColors.CyanBright, filled = !st.suggestImport, onClick = flow::retry)
                    FlowButton("BACK", JarvisColors.TextSecondary, onClick = flow::backToToken)
                }
            }
            ResultKind.BLE_ERROR -> {
                Text("Connection dropped", style = JarvisType.HudLabel.copy(sizePx = 48f).style(JarvisColors.WarnAmber))
                BodyText("Connection dropped — keep the phone within 1 m.", JarvisColors.TextPrimary, align = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(px.dp(24f))) {
                    FlowButton("RETRY", JarvisColors.CyanBright, filled = !st.suggestImport, onClick = flow::retry)
                    FlowButton("IMPORT PAIRING INSTEAD", JarvisColors.WarnAmber, onClick = flow::importInstead)
                }
            }
            null -> Unit
        }
        if (st.suggestImport && (st.result == ResultKind.WINDOW_CLOSED || st.result == ResultKind.BLE_ERROR)) {
            BodyText(
                "Pairing keeps failing. Importing pairing from another device is more reliable.",
                JarvisColors.WarnAmber, align = TextAlign.Center,
            )
            if (st.result == ResultKind.WINDOW_CLOSED) FlowButton("IMPORT PAIRING", JarvisColors.WarnAmber, filled = true, onClick = flow::importInstead)
        }
    }
}

// ---- keep screen on + full brightness during the window (§4.2 P3) -----------------------------------

private tailrec fun Context.findWindow(): Window? = when (this) {
    is android.app.Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}

@Composable
private fun WindowAwake(active: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(active) {
        val w = ctx.findWindow()
        if (!active || w == null) {
            onDispose { }
        } else {
            val prevBrightness = w.attributes.screenBrightness
            val hadKeepOn = (w.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.also { it.screenBrightness = 1f }
            onDispose {
                w.attributes = w.attributes.also { it.screenBrightness = prevBrightness }
                if (!hadKeepOn) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
