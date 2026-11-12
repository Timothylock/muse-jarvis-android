package dev.timlock.jarvis.ui.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(1000)
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DATE_SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private val TIME_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

fun LocalDateTime.timeText(clock24h: Boolean = true): String = format(if (clock24h) TIME else TIME_12)
/** e.g. "Wed 11 Nov"; callers upper-case where the role requires it. */
fun LocalDateTime.dateText(): String = format(DATE_SHORT)
