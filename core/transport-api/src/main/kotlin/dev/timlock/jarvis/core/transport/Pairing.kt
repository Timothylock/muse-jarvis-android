package dev.timlock.jarvis.core.transport

/** Opaque SDK pairing token. toString never reveals the value. */
@JvmInline
value class SdkToken(val value: String) {
    /** Format check stub. TODO(M7): real token format validation. */
    fun isWellFormed(): Boolean = value.isNotBlank()
    override fun toString(): String = "SdkToken(***)"
}

interface PairingUiSink {
    fun onProgress(stage: String, fraction: Float)
    fun onWindowRemaining(seconds: Int)
}

enum class PairOutcome { PAIRED, ALREADY_PAIRED, INVALID_SDK_TOKEN, WINDOW_CLOSED, BLE_ERROR }

/** Pairing material exported from another device. Contents are opaque to everything but the real transport. */
class PairingBundle(val bytes: ByteArray) {
    override fun toString(): String = "PairingBundle(${bytes.size} bytes)"
}

data class ImportResult(val ok: Boolean, val error: String? = null)

/** Result of the fallback (dictated turn) path. */
data class FallbackTurn(val replyText: String?, val replyPcm: ShortArray? = null, val error: String? = null)
