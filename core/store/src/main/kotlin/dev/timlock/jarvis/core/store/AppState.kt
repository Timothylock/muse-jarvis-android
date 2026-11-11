package dev.timlock.jarvis.core.store

import dev.timlock.jarvis.core.model.LinkState
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.model.OrbState
import dev.timlock.jarvis.core.transport.TransportEvent

data class AppState(
    val mode: Mode? = null,
    val link: LinkState = LinkState.Unpaired,
    val orbState: OrbState = OrbState.BOOT,
    val orbStateSince: Long = 0L,
    /** Epoch millis until which the sim interstitial is shown, or null. */
    val simInterstitialUntil: Long? = null,
)

sealed interface Input

sealed interface Intent : Input {
    data class ChooseMode(val mode: Mode) : Intent
    /** Clears the stored mode (back to first run). */
    data object ResetMode : Intent
    data class TransportLink(val link: LinkState) : Intent
    data object BootComplete : Intent
    data object ClearInterstitial : Intent
}

/** A transport event entering the store. */
data class Event(val event: TransportEvent) : Input
