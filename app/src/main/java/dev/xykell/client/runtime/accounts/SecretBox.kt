package dev.xykell.client.runtime.accounts

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for small local secrets (auth tokens, refresh
 * tokens), using AES-256-GCM.
 *
 * The key never enters this class: it is supplied by a [SecretKeyProvider],
 * which on Android is backed by the AndroidKeyStore. That split keeps the
 * crypto path testable on a plain JVM with an in-memory key, and keeps the
 * key material out of anything that serializes, logs, or exports.
 *
 * Wire format: `v1:<base64 iv>:<base64 ciphertext+tag>`. AAD binds each
 * record to its purpose, so a token ciphertext cannot be replayed as a
 * refresh token, and a stored blob cannot be moved to another app's file.
 */
class SecretBox(private val keys: SecretKeyProvider) {

    interface SecretKeyProvider {
        /** Key for [purpose], created on first use. */
        fun keyFor(purpose: String): SecretKey
    }

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Corrupt(val detail: String) : Result<Nothing>()
        data class Unavailable(val detail: String) : Result<Nothing>()

        fun valueOrNull(): T? = (this as? Ok)?.value
        fun isOk(): Boolean = this is Ok
    }

    private val random = SecureRandom()

    fun encrypt(purpose: String, plaintext: String): Result<String> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.keyFor(purpose))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Result.Ok("$PREFIX:${b64(cipher.iv)}:${b64(ct)}")
    } catch (e: GeneralSecurityException) {
        Result.Unavailable(e.javaClass.simpleName)
    } catch (e: Exception) {
        Result.Unavailable(e.javaClass.simpleName)
    }

    fun decrypt(purpose: String, stored: String?): Result<String> {
        if (stored.isNullOrEmpty()) return Result.Corrupt("empty")
        val parts = stored.split(':')
        if (parts.size != 3 || parts[0] != PREFIX) return Result.Corrupt("bad envelope")
        return try {
            val iv = unb64(parts[1])
            val ct = unb64(parts[2])
            if (iv.size != IV_LEN) return Result.Corrupt("iv length")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, keys.keyFor(purpose), GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            Result.Ok(String(cipher.doFinal(ct), Charsets.UTF_8))
        } catch (e: Exception) {
            // Wrong key, tampered ciphertext, truncated blob, wrong purpose: all
            // indistinguishable on purpose. Never surface plaintext or the key.
            Result.Corrupt(e.javaClass.simpleName)
        }
    }

    private fun b64(b: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(b)

    private fun unb64(s: String): ByteArray =
        java.util.Base64.getDecoder().decode(s)

    private companion object {
        const val PREFIX = "v1"
        const val IV_LEN = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * JVM stand-in for tests. Real storage uses [KeystoreKeyProvider].
 * Never used in an app build.
 */
class InMemoryKeyProvider : SecretBox.SecretKeyProvider {
    private val keys = HashMap<String, SecretKey>()
    private val random = SecureRandom()
    override fun keyFor(purpose: String): SecretKey = keys.getOrPut(purpose) {
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
    }
}
