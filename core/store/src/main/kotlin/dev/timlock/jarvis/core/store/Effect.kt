package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.CommandResult
import dev.timlock.jarvis.core.model.Role

enum class HapticKind { TICK }
enum class EarconKind { CHIRP, ERROR, TIMER_CHIME }

/** Side effects requested by the reducer; executed by the transport/audio layer (M5). */
sealed interface Effect {
    /** Start call admission (transport.openCall). */
    data object OpenCall : Effect
    data object HangUpCall : Effect
    /** Flush playback, cancel the interrupted response (§4.3 barge-in). */
    data object BargeIn : Effect
    /** Start AudioRecord into the MicSource ring buffer. */
    data object MicStart : Effect
    /** Call is ready: drain the backlog at real-time pace, then live frames. */
    data object MicLive : Effect
    /** Switch to paced zero frames; never EOF while the call is open. Queued behind any backlog. */
    data object MicSilence : Effect
    /** Stop AudioRecord after a delay (fast re-press does not re-init). */
    data class MicStopAfter(val delayMs: Long) : Effect
    /** Stop AudioRecord now (call ended). */
    data object MicStop : Effect
    data class Haptic(val kind: HapticKind) : Effect
    data class Earcon(val kind: EarconKind) : Effect
    /** Reply to a display command (§5.1); the app forwards it to transport.replyCommand. */
    data class ReplyCommand(val invocationId: String, val result: CommandResult) : Effect
    /** Sticky panels/timers changed (real mode): persist [stack] (§5.5). */
    data class PersistPanels(val stack: dev.timlock.jarvis.core.model.PanelStack) : Effect
    /** [text] may be server content: never log it. */
    data class ShowCaption(val text: String, val role: Role? = null, val final: Boolean = true) : Effect
}

data class Reduction(val state: AppState, val effects: List<Effect> = emptyList())
