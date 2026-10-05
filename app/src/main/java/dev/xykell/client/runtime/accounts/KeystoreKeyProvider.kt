package dev.xykell.client.runtime.accounts

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AndroidKeyStore-backed AES-256 key provider.
 *
 * The key is generated inside the keystore and is non-exportable, so it never
 * exists in the app's process memory in plaintext and cannot be read out of a
 * backup, a log, a crash report, or an exported profile. Nothing here needs
 * androidx.security: KeyGenParameterSpec + GCM are platform APIs on API 23+.
 */
class KeystoreKeyProvider(
    private val keyAlias: String = DEFAULT_ALIAS,
) : SecretBox.SecretKeyProvider {

    override fun keyFor(purpose: String): SecretKey {
        val alias = "$keyAlias.$purpose"
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: tokens must stay readable
                // while the screen is locked or the app is restarted by a
                // background task. Confidentiality rests on the keystore, not on
                // a lock screen, because losing a token to a lock screen would
                // silently break re-authentication.
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** Secure logout: destroy the key so the stored ciphertext is undecryptable. */
    fun destroyAll() {
        try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.aliases().toList().forEach { a ->
                if (a.startsWith("$keyAlias.")) store.deleteEntry(a)
            }
        } catch (_: Exception) {
            // Nothing to destroy is a fine outcome for logout.
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "xykell.accounts.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
