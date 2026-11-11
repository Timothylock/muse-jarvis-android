package dev.timlock.jarvis.transport.sim

import dev.timlock.jarvis.core.model.CallPhase
import dev.timlock.jarvis.core.store.Effect
import dev.timlock.jarvis.core.store.Input
import dev.timlock.jarvis.core.store.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TODO(M5): replaced by SimCall/scenario engine.
 * Tiny stand-in so the orb can be demoed in SIM before audio/transport effects exist:
 * OpenCall -> Ready after 600 ms; PTT release in a call (MicSilence) -> first audible after 1.5 s,
 * drained 3 s later. Feed it every store effect while mode == SIM.
 */
class SimDemoDriver(private val scope: CoroutineScope, private val dispatch: (Input) -> Unit) {
    private var callJob: Job? = null
    private var replyJob: Job? = null

    fun onEffect(e: Effect) {
        when (e) {
            Effect.OpenCall -> {
                callJob?.cancel()
                callJob = scope.launch {
                    delay(600)
                    dispatch(Intent.CallPhaseChanged(CallPhase.Ready))
                }
            }
            Effect.MicSilence -> {
                replyJob?.cancel()
                replyJob = scope.launch {
                    delay(1500)
                    dispatch(Intent.SinkFirstAudible)
                    delay(3000)
                    dispatch(Intent.SinkDrained)
                }
            }
            Effect.BargeIn -> replyJob?.cancel()
            Effect.HangUpCall, Effect.MicStop -> {
                callJob?.cancel()
                replyJob?.cancel()
            }
            else -> Unit
        }
    }
}
