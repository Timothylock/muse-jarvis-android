package dev.timlock.jarvis.core.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Production [CredentialStore] (§6.7): files in `noBackupFilesDir/secure/<name>.enc`, encrypted with the
 * non-exportable Android Keystore AES-256-GCM key `jarvis.state.v1` (no user-auth requirement, so it works unattended).
 * If the key is lost (e.g. reinstall), reads return null.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore by FileCredentialStore(
    dir = File(context.noBackupFilesDir, "secure"),
    keys = ::keystoreKey,
    log = { Log.w("JARVIS_SECURE", it) },
) {
    companion object {
        const val KEY_ALIAS = "jarvis.state.v1"
    }
}

private val KEY_LOCK = Any()

private fun keystoreKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(KeystoreCredentialStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
    synchronized(KEY_LOCK) {
        (ks.getKey(KeystoreCredentialStore.KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KeystoreCredentialStore.KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }
}
