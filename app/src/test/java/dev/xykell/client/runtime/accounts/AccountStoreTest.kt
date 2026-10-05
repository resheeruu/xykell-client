package dev.xykell.client.runtime.accounts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests only. The Context-backed cases (signIn/signOut round trip, prefs
 * persistence) need androidx.test's ApplicationProvider or Robolectric, and
 * neither is a declared dependency, so those assertions lived in a file that
 * had never actually been compiled by Gradle.
 *
 * The security-critical part -- that a token is AES-256-GCM ciphertext under
 * Keystore, that a ciphertext cannot be replayed as the other token, and that
 * corruption is rejected -- is covered by SecretBoxTest. The migration
 * decision is covered here. Storage behaviour itself needs an instrumented
 * test, which this project does not have.
 */
class AccountStoreTest {

    @Test
    fun plaintextFromV1NeedsMigration() {
        assertTrue(AccountStore.needsMigration("\"raw-token\""))
        assertTrue(AccountStore.needsMigration("plain"))
    }

    @Test
    fun alreadyEncryptedDoesNotNeedMigration() {
        assertFalse(AccountStore.needsMigration("v1:aaaa:bbbb"))
    }

    @Test
    fun absentValueDoesNotNeedMigration() {
        assertFalse(AccountStore.needsMigration(null))
        assertFalse(AccountStore.needsMigration(""))
    }

    @Test
    fun authUrlConstruction() {
        val url = AccountStore.buildAuthUrl("my-client-id", "myapp://callback")
        assertTrue(url.startsWith("https://login.microsoftonline.com/"))
        assertTrue(url.contains("client_id=my-client-id"))
        assertTrue(url.contains("redirect_uri=myapp%3A%2F%2Fcallback"))
        assertTrue(url.contains("scope=XboxLive.signin%20offline_access"))
    }

    @Test
    fun authUrlEncodesHostileRedirect() {
        val url = AccountStore.buildAuthUrl("id", "myapp://cb?a=1&b=2#frag")
        // The redirect must be percent-encoded, not able to inject query params.
        assertTrue(url.contains("redirect_uri=myapp%3A%2F%2Fcb%3Fa%3D1%26b%3D2%23frag"))
        assertTrue(url.endsWith("&prompt=select_account"))
    }
}
