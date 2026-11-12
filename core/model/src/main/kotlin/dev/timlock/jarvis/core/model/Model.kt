package dev.timlock.jarvis.core.model

enum class OrbState { BOOT, UNPAIRED, OFFLINE, IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, STANDBY, ERROR, PAIRING }

enum class Mode { REAL, SIM }

enum class TransportKind { REAL, SIM }

enum class Role { USER, MUSE, SYSTEM }

enum class Level { DEBUG, INFO, WARN, ERROR }

sealed interface LinkState {
    data object Unpaired : LinkState
    data object Connecting : LinkState
    data class Online(val nodeId: String, val vmName: String) : LinkState
    data class Offline(val reason: String) : LinkState
    data class PairingLost(val reason: String) : LinkState
}

sealed interface CallPhase {
    data object Admitting : CallPhase
    data object Ready : CallPhase
    data class Ended(val reason: String) : CallPhase
}

/** Result of a display command. Exactly one of [payloadJson] / [error] is meaningful. */
data class CommandResult(val ok: Boolean, val payloadJson: String? = null, val error: String? = null) {
    companion object {
        fun ok(payloadJson: String = "{}") = CommandResult(true, payloadJson, null)
        fun error(message: String) = CommandResult(false, null, message)
    }
}

/**
 * Caption bar line (§2.5): the most recent utterance. [role] null = system line (e.g. "Offline — reconnecting").
 * [text] may be server content: never log it. [atMs] monotonic; the UI types it in (review C10) from [atMs].
 */
data class Caption(val text: String, val role: Role?, val final: Boolean, val atMs: Long)
