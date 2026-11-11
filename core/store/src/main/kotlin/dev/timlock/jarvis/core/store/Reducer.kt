package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.model.TransportKind

const val SIM_INTERSTITIAL_MS = 1500L

/** Pure reducer. [now] is epoch millis; [log] receives diagnostics such as MODE_MISMATCH. */
fun reduce(state: AppState, input: Input, now: Long, log: (String) -> Unit = {}): AppState {
    val next = when (input) {
        is Intent.ChooseMode -> state.copy(
            mode = input.mode,
            link = LinkState.Connecting,
            simInterstitialUntil = if (input.mode == Mode.SIM) now + SIM_INTERSTITIAL_MS else null,
        )
        Intent.ResetMode -> state.copy(mode = null, link = LinkState.Unpaired, simInterstitialUntil = null)
        is Intent.TransportLink -> state.copy(link = input.link)
        Intent.BootComplete -> state
        Intent.ClearInterstitial -> state.copy(simInterstitialUntil = null)
        is Event -> {
            if (input.event.kind.toMode() != state.mode) {
                log("MODE_MISMATCH event=${input.event::class.simpleName} kind=${input.event.kind} mode=${state.mode}")
                return state
            }
            // TODO(M4): turns, transcripts and commands change state here.
            state
        }
    }
    val booted = state.orbState != OrbState.BOOT || input == Intent.BootComplete
    val orb = if (booted) deriveOrb(next) else OrbState.BOOT
    return if (orb != state.orbState) next.copy(orbState = orb, orbStateSince = now) else next
}

private fun TransportKind.toMode(): Mode = if (this == TransportKind.SIM) Mode.SIM else Mode.REAL

private fun deriveOrb(s: AppState): OrbState = when (s.mode) {
    Mode.SIM -> OrbState.IDLE
    Mode.REAL -> when (s.link) {
        LinkState.Unpaired, is LinkState.PairingLost -> OrbState.UNPAIRED
        LinkState.Connecting, is LinkState.Offline -> OrbState.OFFLINE
        is LinkState.Online -> OrbState.IDLE
    }
    null -> OrbState.UNPAIRED
}
