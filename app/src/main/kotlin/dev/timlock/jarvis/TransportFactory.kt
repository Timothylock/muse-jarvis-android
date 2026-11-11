package dev.timlock.jarvis

import dev.timlock.jarvis.core.model.Mode
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.transport.sim.SimTransport

/** The only place that knows both transports (§6.1). Never instantiates both at once. */
object TransportFactory {
    fun create(mode: Mode): GadgetTransport = when (mode) {
        Mode.SIM -> SimTransport()
        Mode.REAL -> UnpairedRealStub() // TODO(M6): SdkTransport
    }
}
