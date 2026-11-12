package dev.timlock.jarvis.core.secure

/**
 * §6.7 log redactor. Applied to every line that leaves the bridge (Python logging → logcat, ring log, exports).
 * Masks SDK tokens, JWT-shaped strings, bearer values, and JSON/kv values of token-like keys.
 */
object Redactor {
    private val SDK_TOKEN = Regex("mgst_[A-Za-z0-9_-]{4,}")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}")
    private val KEYED = Regex(
        "(?i)(\"?(?:access_token|refresh_token|vm_auth_token|sdk_token|token|authorization|password|secret)\"?\\s*[:=]\\s*\"?)([^\"\\s,}]{4,})"
    )

    fun redact(line: String): String {
        var s = SDK_TOKEN.replace(line) { m -> "mgst_…" + m.value.takeLast(4).let { if (m.value.length > 12) it else "" } }
        s = JWT.replace(s, "<jwt>")
        s = BEARER.replace(s) { m -> m.groupValues[1] + "<redacted>" }
        s = KEYED.replace(s) { m -> m.groupValues[1] + "<redacted>" }
        return s
    }
}
