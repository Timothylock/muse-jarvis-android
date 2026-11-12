package dev.timlock.jarvis

import android.content.Context
import android.util.Log
import dev.timlock.jarvis.core.model.TransportKind
import dev.timlock.jarvis.core.secure.CredentialNames
import dev.timlock.jarvis.core.secure.CredentialStore
import dev.timlock.jarvis.core.secure.InMemoryCredentialStore
import dev.timlock.jarvis.core.secure.PairingImport
import dev.timlock.jarvis.core.transport.GadgetTransport
import dev.timlock.jarvis.core.transport.PairingBundle
import dev.timlock.jarvis.core.transport.SdkToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Import / export / token provisioning (§4.2.6, §4.2 P1), adapted for scoped storage: the drop dir is
 * `getExternalFilesDir("import")` (adb-pushable, e.g. /sdcard/Android/data/dev.timlock.jarvis/files/import/),
 * the export goes to `getExternalFilesDir("export")/jarvis-identity.json`. Never logs credential values.
 * TODO(M7): the pairing screens call the same functions (P1 paste, import button).
 */
class PairingController(
    context: Context,
    val credentials: CredentialStore,
    private val transport: () -> GadgetTransport?,
) {
    private val app = context.applicationContext
    private val mutex = Mutex()

    sealed interface Status {
        data object Idle : Status
        data object Running : Status
        data class Imported(val nodeId: String) : Status
        data class NoFiles(val dir: String) : Status
        data class Invalid(val reason: String) : Status
        data class Conflict(val storedNodeId: String, val importedNodeId: String) : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    fun resetStatus() { _status.value = Status.Idle }

    fun importDir(): File? = app.getExternalFilesDir(IMPORT_DIR)
    fun exportDir(): File? = app.getExternalFilesDir(EXPORT_DIR)

    /**
     * Imports whatever is in the drop dir. [overwriteIdentity] is the explicit confirm for a different stored identity.
     * On success the cleartext files are deleted; on any failure they are kept so the owner can fix them.
     */
    suspend fun import(overwriteIdentity: Boolean): Status = mutex.withLock {
        _status.value = Status.Running
        val result = withContext(Dispatchers.IO) { runImport(overwriteIdentity) }
        _status.value = result
        when (result) {
            is Status.Conflict -> Log.w("JARVIS_UI", "import: different identity already stored (${result.storedNodeId} vs ${result.importedNodeId}); confirm to replace")
            is Status.Imported -> Log.i("JARVIS_UI", "import: ok ${result.nodeId}")
            else -> Log.i("JARVIS_UI", "import: ${result.javaClass.simpleName}")
        }
        result
    }

    private suspend fun runImport(overwrite: Boolean): Status {
        val dir = importDir() ?: return Status.Failed("external storage unavailable")
        val t = transport()
        if (t == null || t.kind != TransportKind.REAL) return Status.Failed("real mode is not active")
        val files = ImportFiles.NAMES.map { File(dir, it) }.filter { it.isFile }
        val bytes = when (val b = buildBundle(dir)) {
            is Built.Bytes -> b.value
            Built.NoFiles -> return Status.NoFiles(dir.path)
            is Built.Bad -> return Status.Invalid(b.reason)
        }
        // Dry run against a scratch store that only carries the stored identity: validates and detects a conflict.
        val scratch = InMemoryCredentialStore()
        credentials.read(CredentialNames.IDENTITY)?.let { scratch.write(CredentialNames.IDENTITY, it) }
        when (val r = PairingImport.apply(scratch, bytes, overwrite)) {
            is PairingImport.Result.Invalid -> return Status.Invalid(r.reason)
            is PairingImport.Result.IdentityConflict -> return Status.Conflict(r.storedNodeId, r.importedNodeId)
            is PairingImport.Result.Ok -> Unit
        }
        if (overwrite) {
            // The transport's own apply() runs without the overwrite flag, so replace the identity first; the
            // transport's call below then sees a matching identity, rewrites everything and restarts the link.
            val r = PairingImport.apply(credentials, bytes, overwriteIdentity = true)
            if (r !is PairingImport.Result.Ok) return Status.Failed("storage write failed")
        }
        val res = try { t.importPairing(PairingBundle(bytes)) } catch (e: Exception) {
            return Status.Failed("transport error: ${e.javaClass.simpleName}")
        }
        if (!res.ok) return Status.Failed(res.error ?: "import rejected")
        files.forEach { it.delete() }
        val mac = credentials.read(CredentialNames.IDENTITY)?.let { PairingImport.storedMac(it) }
        return Status.Imported(mac?.let { PairingImport.nodeIdForMac(it) } ?: "?")
    }

    private sealed interface Built {
        data class Bytes(val value: ByteArray) : Built
        data object NoFiles : Built
        data class Bad(val reason: String) : Built
    }

    private fun buildBundle(dir: File): Built {
        val bundle = File(dir, ImportFiles.BUNDLE)
        if (bundle.isFile) return Built.Bytes(bundle.readBytes())
        val pairingFile = File(dir, CredentialNames.PAIRING)
        if (!pairingFile.isFile) return Built.NoFiles
        return try {
            val identityFile = File(dir, CredentialNames.IDENTITY)
            val identity = when {
                identityFile.isFile -> JSONObject(identityFile.readText())
                else -> credentials.read(CredentialNames.IDENTITY)?.let { JSONObject(String(it)) }
                    ?: return Built.Bad("identity.json missing")
            }
            val out = JSONObject().put("identity", identity).put("pairing", JSONObject(pairingFile.readText()))
            File(dir, CredentialNames.SDK_TOKEN).takeIf { it.isFile }?.readText()?.trim()?.let { out.put("sdk_token", it) }
            Built.Bytes(out.toString().toByteArray(Charsets.UTF_8))
        } catch (e: org.json.JSONException) {
            Built.Bad("a pairing file is not valid JSON")
        }
    }

    /** Writes `{"mac": ...}` to export/jarvis-identity.json, creating the identity first if none is stored. Returns node id + path. */
    suspend fun exportIdentity(): Pair<String, String> = withContext(Dispatchers.IO) {
        val dir = exportDir() ?: error("external storage unavailable")
        dir.mkdirs()
        var mac = credentials.read(CredentialNames.IDENTITY)?.let { PairingImport.storedMac(it) }
        if (mac == null) {
            mac = generateMac()
            credentials.write(CredentialNames.IDENTITY, JSONObject().put("mac", mac).toString().toByteArray(Charsets.UTF_8))
        }
        val f = File(dir, "jarvis-identity.json")
        f.writeText(JSONObject().put("mac", mac).toString())
        PairingImport.nodeIdForMac(mac) to f.path
    }

    /** Stores a well-formed SDK token; returns null on success or a reason (never the token). */
    suspend fun provisionToken(raw: String?): String? {
        val t = SdkToken(raw.orEmpty())
        if (!t.isWellFormed()) return "malformed token"
        withContext(Dispatchers.IO) { credentials.write(CredentialNames.SDK_TOKEN, raw!!.trim().toByteArray(Charsets.UTF_8)) }
        Log.i("JARVIS_UI", "token provisioned ${t.hint()}")
        return null
    }

    /** musegadget.identity.generate_mac: 6 random bytes, first octet (b & 0xFC) | 0x02, lowercase colon hex. */
    private fun generateMac(): String {
        val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
        return b.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }

    private object ImportFiles {
        const val BUNDLE = "bundle.json"
        val NAMES = listOf(BUNDLE, CredentialNames.IDENTITY, CredentialNames.PAIRING, CredentialNames.SDK_TOKEN)
    }

    companion object {
        const val IMPORT_DIR = "import"
        const val EXPORT_DIR = "export"
    }
}
