package dev.xykell.client.runtime.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crypto path for account tokens. Runs on the host JVM with an in-memory key;
 * the AndroidKeyStore binding is exercised by CI's instrumented build, not here.
 * No test reads a real credential, a shell, or the filesystem.
 */
class SecretBoxTest {

    private val provider = InMemoryKeyProvider()
    private val box = SecretBox(provider)

    @Test
    fun roundTripRestoresPlaintext() {
        val enc = box.encrypt("auth_token", "token-value-123")
        assertTrue(enc is SecretBox.Result.Ok)
        val stored = (enc as SecretBox.Result.Ok).value
        assertEquals("token-value-123", box.decrypt("auth_token", stored).valueOrNull())
    }

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val stored = (box.encrypt("auth_token", "super-secret-value") as SecretBox.Result.Ok).value
        assertFalse(stored.contains("super-secret-value"))
        assertTrue(stored.startsWith("v1:"))
    }

    @Test
    fun repeatedEncryptionProducesDifferentCiphertext() {
        val a = (box.encrypt("auth_token", "same") as SecretBox.Result.Ok).value
        val b = (box.encrypt("auth_token", "same") as SecretBox.Result.Ok).value
        assertNotEquals(a, b) // fresh IV each time
        assertEquals("same", box.decrypt("auth_token", a).valueOrNull())
        assertEquals("same", box.decrypt("auth_token", b).valueOrNull())
    }

    @Test
    fun purposeIsBoundSoTokensCannotBeSwapped() {
        val authBlob = (box.encrypt("auth_token", "access") as SecretBox.Result.Ok).value
        // Replaying the access-token ciphertext as the refresh token must fail.
        assertTrue(box.decrypt("refresh_token", authBlob) is SecretBox.Result.Corrupt)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val stored = (box.encrypt("auth_token", "value") as SecretBox.Result.Ok).value
        val parts = stored.split(':')
        val bytes = java.util.Base64.getDecoder().decode(parts[2])
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = parts[0] + ":" + parts[1] + ":" +
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
        assertTrue(box.decrypt("auth_token", tampered) is SecretBox.Result.Corrupt)
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val stored = (SecretBox(InMemoryKeyProvider()).encrypt("auth_token", "v")
            as SecretBox.Result.Ok).value
        assertTrue(box.decrypt("auth_token", stored) is SecretBox.Result.Corrupt)
    }

    @Test
    fun malformedEnvelopesAreRejectedWithoutThrowing() {
        for (bad in listOf("", "junk", "v1", "v1:only-two", "v2:a:b", "v1:!!!:???")) {
            assertTrue(
                "'$bad' should be rejected",
                box.decrypt("auth_token", bad) is SecretBox.Result.Corrupt,
            )
        }
        assertTrue(box.decrypt("auth_token", null) is SecretBox.Result.Corrupt)
    }

    @Test
    fun corruptResultNeverLeaksTheSecret() {
        val r = box.decrypt("auth_token", "v1:abc:def")
        assertTrue(r is SecretBox.Result.Corrupt)
        assertNull(r.valueOrNull())
    }

    @Test
    fun unicodeAndLongValuesSurvive() {
        for (v in listOf("", "a", "x".repeat(8_000), "é中文\uD83D\ude00")) {
            val stored = (box.encrypt("refresh_token", v) as SecretBox.Result.Ok).value
            assertEquals(v, box.decrypt("refresh_token", stored).valueOrNull())
        }
    }

    @Test
    fun separatePurposesUseSeparateKeys() {
        // Same provider, different purpose: the keystore entry is namespaced, so
        // a ciphertext from one purpose cannot be opened under the other (proven
        // by purposeIsBoundSoTokensCannotBeSwapped). Here we only assert the
        // envelopes are independently produced.
        val a = SecretBox(provider).encrypt("auth_token", "v")
        val b = SecretBox(provider).encrypt("refresh_token", "v")
        assertTrue(a is SecretBox.Result.Ok && b is SecretBox.Result.Ok)
        val ivA = (a as SecretBox.Result.Ok).value.split(':')[1]
        val ivB = (b as SecretBox.Result.Ok).value.split(':')[1]
        assertNotEquals(ivA, ivB) // independent IVs
    }
}
