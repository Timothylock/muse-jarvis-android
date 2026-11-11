package dev.timlock.jarvis.core.transport

import dev.timlock.jarvis.core.model.Level
import dev.timlock.jarvis.core.model.Role
import dev.timlock.jarvis.core.model.TransportKind

/** Every event is stamped with the [kind] of the transport that produced it (§7.6 rule 7). */
sealed interface TransportEvent {
    val kind: TransportKind

    data class Command(val invocationId: String, val name: String, val paramsJson: String, override val kind: TransportKind) : TransportEvent
    data class TurnStarted(val id: String, override val kind: TransportKind) : TransportEvent
    data class TurnSettled(val id: String, override val kind: TransportKind) : TransportEvent
    data class Transcript(val role: Role, val text: String, val final: Boolean, override val kind: TransportKind) : TransportEvent
    data class AdvisoryError(val count: Int, override val kind: TransportKind) : TransportEvent

    /** Never carries secrets or server messages. */
    data class Diagnostic(val level: Level, val code: String, override val kind: TransportKind) : TransportEvent
}
