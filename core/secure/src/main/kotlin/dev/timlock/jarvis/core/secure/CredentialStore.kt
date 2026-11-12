package dev.timlock.jarvis.core.secure

/**
 * Encrypted at-rest store for the SDK's state files (§6.7). Names are the SDK's file names
 * ([CredentialNames]); values are the raw file bytes (UTF-8 JSON / token text).
 * Implementations must be thread-safe: the SDK bridge calls them from Python worker threads.
 */
interface CredentialStore {
    fun read(name: String): ByteArray?
    /** Atomic: a crash leaves the old value or the new one, never a partial one. */
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
    fun exists(name: String): Boolean = read(name) != null
}

/** The only names the app stores (mirrors musegadget.config). */
object CredentialNames {
    const val IDENTITY = "identity.json"
    const val PAIRING = "pairing.json"
    const val SDK_TOKEN = "sdk_token"
    val ALL = setOf(IDENTITY, PAIRING, SDK_TOKEN)
}

/** Test/sim helper. Never used for real credentials. */
class InMemoryCredentialStore : CredentialStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    override fun read(name: String): ByteArray? = map[name]?.copyOf()
    override fun write(name: String, data: ByteArray) { map[name] = data.copyOf() }
    override fun delete(name: String) { map.remove(name) }
}
