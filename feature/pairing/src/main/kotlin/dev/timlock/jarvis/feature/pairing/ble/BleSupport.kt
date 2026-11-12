package dev.timlock.jarvis.feature.pairing.ble

/** Pure helpers for [AndroidBlePeripheral] (no Android types, so they run in JVM unit tests). */
internal object BleSupport {
    // android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_*
    const val ADV_DATA_TOO_LARGE = 1
    const val ADV_TOO_MANY_ADVERTISERS = 2
    const val ADV_ALREADY_STARTED = 3
    const val ADV_INTERNAL_ERROR = 4
    const val ADV_FEATURE_UNSUPPORTED = 5

    fun advertiseError(code: Int): String = when (code) {
        ADV_DATA_TOO_LARGE -> "advertise_data_too_large"
        ADV_TOO_MANY_ADVERTISERS -> "advertise_too_many_advertisers"
        ADV_ALREADY_STARTED -> "advertise_already_started"
        ADV_INTERNAL_ERROR -> "advertise_internal_error"
        ADV_FEATURE_UNSUPPORTED -> "advertise_unsupported"
        else -> "advertise_failed_$code"
    }

    /**
     * Client Characteristic Configuration write: true = notifications on, false = off, null = not acceptable
     * (indications are not offered on TX; wrong length).
     */
    fun parseCcc(value: ByteArray?): Boolean? {
        if (value == null || value.size != 2 || value[1].toInt() != 0) return null
        return when (value[0].toInt()) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    /** WifiInfo.getSSID(): quoted text, or "<unknown ssid>" when the platform hides it. */
    fun normalizeSsid(raw: String?): String? {
        var s = raw?.trim() ?: return null
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
        return s
    }
}

/**
 * Assembles GATT "prepared" (long) writes: each Prepare Write carries (offset, chunk); Execute Write delivers the
 * whole value once. Bounded so a misbehaving central cannot grow it without limit.
 */
internal class PreparedWriteBuffer(private val maxBytes: Int = MAX_VALUE_BYTES) {
    private var data = ByteArray(0)

    /** False (and the buffer is dropped) if the write would exceed the limit. */
    @Synchronized
    fun add(offset: Int, chunk: ByteArray): Boolean {
        val end = offset.toLong() + chunk.size
        if (offset < 0 || end > maxBytes) { clear(); return false }
        if (end > data.size) data = data.copyOf(end.toInt())
        chunk.copyInto(data, offset)
        return true
    }

    /** The assembled value (null if nothing was staged); resets the buffer. */
    @Synchronized
    fun take(): ByteArray? {
        val out = if (data.isEmpty()) null else data
        clear()
        return out
    }

    @Synchronized
    fun clear() { data = ByteArray(0); }

    companion object {
        /** GATT attribute values are at most 512 bytes. */
        const val MAX_VALUE_BYTES = 512
    }
}
