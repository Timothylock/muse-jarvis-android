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

/** Receives pairing progress (§4.2 P3). Called from any thread; implementations must be cheap and thread-safe. */
interface PairingUiSink {
    /** [stage] is one of [PairingStage]; [fraction] 0..1 for the progress dots. */
    fun onProgress(stage: String, fraction: Float)
    fun onWindowRemaining(seconds: Int)
    /** The setup window opened: show [bleName] ("MuseGadgetXXXXXX") as the device to choose in the Muse app. */
    fun onWindowOpen(bleName: String, timeoutS: Int, hasSdkToken: Boolean) {}
    /** INVALID_SDK_TOKEN detail (config.sdk_token() ValueError text; never contains the token). */
    fun onTokenProblem(text: String) {}
}

/** §4.2 P3 progress dots, in order. */
object PairingStage {
    const val ADVERTISING = "advertising"      // advertiser + GATT server up
    const val CONNECTED = "connected"          // a central connected
    const val SECURE = "secure"                // handshake complete (pairing_confirmed sent)
    const val SAVED = "saved"                  // credentials verified + pairing.json saved
    val ORDER = listOf(ADVERTISING, CONNECTED, SECURE, SAVED)
}

/**
 * Kotlin BLE GATT peripheral (§6.6): a BYTE PIPE with the SDK's service/characteristic UUIDs. All framing and
 * crypto stay in the SDK's Python (ble_setup.SetupController / pairing.py). Implemented in :feature:pairing.
 */
interface BlePeripheral {
    /** Null if this device can advertise; otherwise a short reason ("bluetooth_off", "no_permission", "advertise_unsupported"). */
    fun readinessProblem(): String?
    /** Set the adapter name to [bleName], open the GATT server, start advertising. Returns null on success or an error code. */
    fun open(bleName: String, listener: Listener): String?
    /** ATT MTU of the current connection (23 until onMtuChanged). */
    fun mtu(): Int
    /**
     * Notify each packet on TX in order, waiting for onNotificationSent (bounded) and CHUNK_STAGGER (50 ms) between
     * packets. Blocks the caller (SetupController's worker thread). Packets are dropped when no central is subscribed.
     */
    fun sendPackets(packets: List<ByteArray>)
    /** Disconnect the current central after [delayMs]. */
    fun disconnect(delayMs: Long)
    /** Stop advertising, close the GATT server, restore the adapter name. Idempotent. */
    fun close()

    interface Listener {
        fun onConnected()
        /** A write to RX (one framed packet, exactly as received). */
        fun onWrite(packet: ByteArray)
        fun onDisconnected()
    }

    companion object {
        const val SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
        const val RX_UUID = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"   // write, write-without-response
        const val TX_UUID = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"   // read, notify
        const val CHUNK_STAGGER_MS = 50L
    }
}

/** ble_setup.Network protocol (§ SDK_NOTES): Android-backed online check + current connection entry. */
interface NetworkStatus {
    fun isOnline(): Boolean
    /** Current Wi-Fi SSID if known (needs location permission on Android; null is fine — the SDK uses a label). */
    fun ssid(): String?
}

enum class PairOutcome { PAIRED, ALREADY_PAIRED, INVALID_SDK_TOKEN, WINDOW_CLOSED, BLE_ERROR }

/** Pairing material exported from another device. Contents are opaque to everything but the real transport. */
class PairingBundle(val bytes: ByteArray) {
    override fun toString(): String = "PairingBundle(${bytes.size} bytes)"
}

data class ImportResult(val ok: Boolean, val error: String? = null)

/** Result of the fallback (dictated turn) path. */
data class FallbackTurn(val replyText: String?, val replyPcm: ShortArray? = null, val error: String? = null)
