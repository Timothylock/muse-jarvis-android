package dev.timlock.jarvis.core.secure

/**
 * §4.2.6 proxy-pairing import. M6 CONTRACT STUB — track C implements.
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

    /** Validate and, if valid, write identity/pairing/token to [store]. Never logs values. */
    fun apply(store: CredentialStore, bundleJson: ByteArray, overwriteIdentity: Boolean = false): Result =
        Result.Invalid("not implemented")

    /** "homelink-" + last 6 hex digits of the mac (musegadget.identity.Identity.node_id). */
    fun nodeIdForMac(mac: String): String = "homelink-" + mac.replace(":", "").takeLast(6)
}
