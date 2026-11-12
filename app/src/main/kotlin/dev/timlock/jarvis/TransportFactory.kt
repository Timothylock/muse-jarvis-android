package dev.timlock.jarvis

import android.content.Context
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.secure.InMemoryCredentialStore
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.transport.sdk.SdkTransport
import dev.timlock.jarvis.transport.sim.SimTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The only place that knows both transports (§6.1). Never instantiates both at once. */
object TransportFactory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // TODO(M6): the Keystore-backed store from :core:secure replaces this (lead merges); in-memory loses state on restart.
    private val store = InMemoryCredentialStore()

    fun create(mode: Mode, context: Context): GadgetTransport = when (mode) {
        Mode.SIM -> SimTransport()
        Mode.REAL -> SdkTransport(context.applicationContext, store, scope)
    }
}
