package dev.timlock.jarvis.core.secure

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM file store (§6.7). One file per name: `[version=1][12-byte IV][ciphertext+tag]`.
 * The name is bound in as GCM associated data so files cannot be swapped. The key comes from [keys] (the Android
 * Keystore in production, a software key in JVM tests). Thread-safe; writes are atomic (tmp + fsync + rename).
 * Never logs values: failures log only the name and the exception class.
 */
class FileCredentialStore(
    private val dir: File,
    private val keys: () -> SecretKey,
    private val log: (String) -> Unit = {},
) : CredentialStore {
    private val lock = Any()

    override fun read(name: String): ByteArray? = synchronized(lock) {
        requireValidName(name)
        val f = fileFor(name)
        if (!f.isFile) return null
        try {
            val raw = f.readBytes()
            require(raw.size > 1 + IV_BYTES + TAG_BYTES) { "short" }
            require(raw[0] == VERSION) { "version" }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, keys(), GCMParameterSpec(TAG_BYTES * 8, raw, 1, IV_BYTES))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            cipher.doFinal(raw, 1 + IV_BYTES, raw.size - 1 - IV_BYTES)
        } catch (t: Throwable) {
            log("secure: cannot read '$name': ${t.javaClass.simpleName}")
            null
        }
    }

    override fun write(name: String, data: ByteArray): Unit = synchronized(lock) {
        requireValidName(name)
        dir.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keys()) // randomized IV (required by the Keystore)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV size" }
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(data)
        val tmp = File(dir, "$name.enc.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(byteArrayOf(VERSION))
                out.write(iv)
                out.write(ct)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), fileFor(name).toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), fileFor(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
        Unit
    }

    override fun delete(name: String): Unit = synchronized(lock) {
        requireValidName(name)
        fileFor(name).delete()
        Unit
    }

    /** True when an encrypted file is present (does not decrypt). */
    fun hasFile(name: String): Boolean = synchronized(lock) { requireValidName(name); fileFor(name).isFile }

    private fun fileFor(name: String) = File(dir, "$name.enc")

    companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val NAME = Regex("[A-Za-z0-9._-]{1,64}")

        fun requireValidName(name: String) {
            require(NAME.matches(name) && name != "." && name != "..") { "invalid credential name" }
        }
    }
}
