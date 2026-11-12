package dev.timlock.jarvis

import android.content.Context
import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.secure.CredentialStore
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.transport.sdk.SdkTransport
import dev.timlock.jarvis.transport.sim.SimTransport
import kotlinx.coroutines.CoroutineScope

/** The only place that knows both transports (§6.1). Never instantiates both at once. */
object TransportFactory {
    /** [scope] belongs to the created transport (the caller cancels it when the transport is stopped). */
    fun create(context: Context, mode: Mode, credentials: CredentialStore, scope: CoroutineScope): GadgetTransport = when (mode) {
        Mode.SIM -> SimTransport()
        Mode.REAL -> SdkTransport(context.applicationContext, credentials, scope)
    }
}
