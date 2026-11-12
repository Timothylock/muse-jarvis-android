package dev.timlock.jarvis.core.transport

/** Opaque SDK pairing token. toString never reveals the value. */
@JvmInline
value class SdkToken(val value: String) {
    /** Same rule as musegadget.config.sdk_token() (raises ValueError there for impossible tokens). */
    fun isWellFormed(): Boolean = FORMAT.matches(value.trim())

    /** The part gadgets.muse.ai shows (config.sdk_token_hint): the first 12 chars. Safe to display. */
    fun hint(): String = value.trim().take(12)
    override fun toString(): String = "SdkToken(***)"

    companion object {
        val FORMAT = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
    }
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
