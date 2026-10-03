package dev.xykell.client.runtime.observation

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clean-room port of the PROVEN Stage-9 cryptographic parameters
 * (lab: lab/bedrock-websocket/encryption.mjs, verified against
 * Sandertv/mcwss): ephemeral P-384 ECDSA keypair, 16-byte salt,
 * standard-base64-unpadded handshake strings, ECDH x-coordinate
 * (left-padded to 48 bytes), key = SHA-256(salt + secret),
 * AES-256-CFB8 streaming both directions, IVs = key[:16].
 *
 * No algorithm, parameter, or behavior differs from the proven lab.
 * Ephemeral material only: nothing here persists, logs, or exposes
 * keys, salts, or ciphertext. JCE-only (java.security/javax.crypto);
 * pure JVM (no Android dependency) so host unit tests cover it.
 */
object ObservationCrypto {

    const val CURVE = "secp384r1"
    const val SPKI_DER_BYTES = 120
    const val SALT_BYTES = 16
    const val SECRET_BYTES = 48
    const val KEY_BYTES = 32
    const val IV_BYTES = 16

    /** Standard Base64 alphabet, padding stripped (== Go RawStdEncoding). */
    fun b64u(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)

    /** Standard Base64 decode tolerant of absent padding (peer material). */
    fun b64d(s: String): ByteArray {
        var p = s.trim()
        p += "=".repeat((4 - p.length % 4) % 4)
        return Base64.getDecoder().decode(p)
    }

    fun generateEphemeralKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(CURVE), random)
        return gen.generateKeyPair()
    }

    /** X.509 SPKI DER of our public key (120 bytes for P-384). */
    fun spkiDer(pair: KeyPair): ByteArray = pair.public.encoded

    fun importPeerSpki(der: ByteArray): ECPublicKey =
        KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(der)) as ECPublicKey

    /** ECDH x-coordinate, left-padded to exactly 48 bytes (P-384). */
    fun agreeX384(privateKey: java.security.PrivateKey, peer: ECPublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peer, true)
        val secret = ka.generateSecret()
        require(secret.size <= SECRET_BYTES) { "ECDH secret oversize" }
        if (secret.size == SECRET_BYTES) return secret
        val padded = ByteArray(SECRET_BYTES)
        System.arraycopy(secret, 0, padded, SECRET_BYTES - secret.size, secret.size)
        return padded
    }

    /** Session key = SHA-256(salt16 + secret48). */
    fun deriveKey(salt16: ByteArray, secret48: ByteArray): ByteArray {
        require(salt16.size == SALT_BYTES) { "salt must be 16 bytes" }
        require(secret48.size == SECRET_BYTES) { "secret must be 48 bytes" }
        return MessageDigest.getInstance("SHA-256").digest(salt16 + secret48)
    }

    fun randomSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also(random::nextBytes)

    /** Persistent one-direction CFB8 stream (matches mcwss byte-chained use). */
    class Cfb8Stream(key32: ByteArray, decrypt: Boolean) {
        private val cipher: Cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(
                if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
                SecretKeySpec(key32.copyOf(), "AES"),
                IvParameterSpec(key32.copyOfRange(0, IV_BYTES)),
            )
        }

        /** Transforms exactly input.size bytes (stream ciphers never throw). */
        fun update(bytes: ByteArray): ByteArray = cipher.update(bytes) ?: ByteArray(0)
    }

    /** Documented handshake command only. No other commandLine exists. */
    fun enableEncryptionCommand(publicDer: ByteArray, salt: ByteArray): String =
        "enableencryption \"${b64u(publicDer)}\" \"${b64u(salt)}\""
}
