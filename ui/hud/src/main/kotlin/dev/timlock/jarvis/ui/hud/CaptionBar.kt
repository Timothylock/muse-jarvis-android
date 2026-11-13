package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.timlock.jarvis.core.model.Caption
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.monoMillis

/** C10 typewriter speed. */
const val CAPTION_CHARS_PER_SEC = 60L
private const val CURSOR = "█"

/** How many characters of [text] are typed [elapsedMs] after the caption appeared. */
fun typedChars(elapsedMs: Long, length: Int): Int =
    (elapsedMs.coerceAtLeast(0) * CAPTION_CHARS_PER_SEC / 1000).coerceAtMost(length.toLong()).toInt()

/**
 * USER captions keep their newest words visible: the bar holds ~2 lines, and a long hold must not freeze on its first
 * two lines while the user keeps talking (Fable audit A2 #3). Longer than [USER_TAIL_CHARS] -> leading ellipsis + tail,
 * cut at a word boundary when one is close. MUSE/system captions keep head truncation (maxLines + ellipsis).
 */
const val USER_TAIL_CHARS = 150

fun userCaptionTail(text: String, max: Int = USER_TAIL_CHARS): String {
    if (text.length <= max) return text
    var tail = text.takeLast(max - 1)
    val sp = tail.indexOf(' ')
    if (sp in 0..20) tail = tail.substring(sp + 1)
    return "…$tail"
}

/**
 * §2.5 captions bar text (C10 typewriter, H3 no marquee: at most 2 lines, ellipsis). `YOU ›` text.secondary,
 * `MUSE ›` text.primary, system lines text.secondary without a prefix; `SIM ›` (violet, via [SimStyling]) in sim mode.
 * Recomposes at frame rate only while typing.
 */
@Composable
fun CaptionText(caption: Caption, sim: Boolean, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    var tick by remember { mutableLongStateOf(0L) }
    val total = caption.text.length
    LaunchedEffect(caption.atMs, caption.text) {
        while (typedChars(monoMillis() - caption.atMs, total) < total) {
            tick = withFrameMillis { it }
        }
        tick = monoMillis() // final recomposition without the cursor
    }
    @Suppress("UNUSED_VARIABLE") val subscribe = tick
    val typed = typedChars(monoMillis() - caption.atMs, total)
    val typing = typed < total
    val body = if (caption.role == Role.MUSE) JarvisColors.TextPrimary else JarvisColors.TextSecondary
    val text: AnnotatedString = buildAnnotatedString {
        if (sim) withStyle(SpanStyle(color = SimStyling.accent)) { append("SIM › ") }
        when (caption.role) {
            Role.USER -> withStyle(SpanStyle(color = JarvisColors.TextSecondary)) { append("YOU › ") }
            Role.MUSE -> withStyle(SpanStyle(color = JarvisColors.TextPrimary)) { append("MUSE › ") }
            else -> Unit
        }
        withStyle(SpanStyle(color = body)) {
            val shown = caption.text.take(typed)
            append(if (caption.role == Role.USER) userCaptionTail(shown) else shown)
            if (typing) append(CURSOR)
        }
    }
    Box(modifier.padding(horizontal = px.dp(28f))) {
        Text(
            text,
            style = JarvisType.Caption.style(body),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
