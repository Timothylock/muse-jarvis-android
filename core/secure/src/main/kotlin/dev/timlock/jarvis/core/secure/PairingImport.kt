package dev.timlock.jarvis.core.secure

import dev.timlock.jarvis.core.transport.SdkToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * §4.2.6 proxy-pairing import. 
 *
 * Bundle wire format (UTF-8 JSON, what `PairingBundle.bytes` carries):
 *   {"identity": {"mac": "02:ab:..."}, "pairing": {<pairing.json as written by the SDK>}, "sdk_token": "mgst_..."?}
 * Rules (mirror the SDK): identity.mac matches `[0-9a-f]{2}(:[0-9a-f]{2}){5}`; pairing passes
 * config.pairing_problem() (non-blank string access_token + refresh_token; access_token_saved_at absent or a
 * finite number >= 0); sdk_token, when present, matches SdkToken.FORMAT.
 * A stored identity with a DIFFERENT mac is not overwritten unless [overwriteIdentity] (UI confirm dialog).
 */
object PairingImport {
    sealed interface Result {
        data class Ok(val nodeId: String) : Result
        data class Invalid(val reason: String) : Result
        data class IdentityConflict(val storedNodeId: String, val importedNodeId: String) : Result
    }

    private val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    /** Validate and, if valid, write identity/pairing/token to [store]. Never logs values; reasons never contain values. */
    fun apply(store: CredentialStore, bundleJson: ByteArray, overwriteIdentity: Boolean = false): Result {
        val root = try {
            Json.parseToJsonElement(String(bundleJson, Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Result.Invalid("bundle is not a JSON object")

        val identity = root["identity"] as? JsonObject ?: return Result.Invalid("identity missing")
        val mac = (identity["mac"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (mac == null || !MAC_RE.matches(mac)) return Result.Invalid("identity.mac invalid")

        val pairing = root["pairing"] as? JsonObject ?: return Result.Invalid("pairing missing")
        for (key in listOf("access_token", "refresh_token")) {
            val v = (pairing[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (v == null || v.isBlank()) return Result.Invalid("pairing lacks device tokens")
        }
        pairing["access_token_saved_at"]?.let { stamp ->
            val n = (stamp as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (n == null || !n.isFinite() || n < 0.0) return Result.Invalid("pairing has an invalid token timestamp")
        }

        val tokenEl = root["sdk_token"]
        var token: String? = null
        if (tokenEl != null && tokenEl !is JsonNull) {
            val t = (tokenEl as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (t == null || !SdkToken(t).isWellFormed()) return Result.Invalid("sdk_token malformed")
            token = t
        }

        // An unreadable (present but undecryptable) identity is only replaced on an explicit overwrite confirm.
        val storedMac = try {
            store.read(CredentialNames.IDENTITY)?.let { storedMac(it) }
        } catch (e: CredentialUnreadableException) {
            if (!overwriteIdentity) return Result.Invalid("stored identity unreadable")
            null
        }
        if (storedMac != null && storedMac != mac && !overwriteIdentity) {
            return Result.IdentityConflict(nodeIdForMac(storedMac), nodeIdForMac(mac))
        }

        try {
            store.write(CredentialNames.IDENTITY, JsonObject(mapOf("mac" to JsonPrimitive(mac))).toString().toByteArray(Charsets.UTF_8))
            store.write(CredentialNames.PAIRING, pairing.toString().toByteArray(Charsets.UTF_8))
            if (token != null) store.write(CredentialNames.SDK_TOKEN, token.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            return Result.Invalid("storage write failed: ${e.javaClass.simpleName}")
        }
        return Result.Ok(nodeIdForMac(mac))
    }

    /** The stored identity's mac, or null when absent/unreadable (an unreadable identity is not worth protecting). */
    fun storedMac(identityJson: ByteArray): String? = try {
        ((Json.parseToJsonElement(String(identityJson, Charsets.UTF_8)) as? JsonObject)?.get("mac") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { MAC_RE.matches(it) }
    } catch (e: Exception) {
        null
    }

    /** "homelink-" + last 6 hex digits of the mac (musegadget.identity.Identity.node_id). */
    fun nodeIdForMac(mac: String): String = "homelink-" + mac.replace(":", "").takeLast(6)
}
