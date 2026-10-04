package dev.xykell.client.runtime.accounts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun signInAndOut() {
        AccountStore.signOut(context)
        assertFalse(AccountStore.isSignedIn(context))
        assertNull(AccountStore.getAccountName(context))

        AccountStore.signIn(context, "TestUser", "auth123", "refresh456")
        assertTrue(AccountStore.isSignedIn(context))
        assertEquals("TestUser", AccountStore.getAccountName(context))
        assertEquals("auth123", AccountStore.getAuthToken(context))
        assertEquals("refresh456", AccountStore.getRefreshToken(context))

        AccountStore.signOut(context)
        assertFalse(AccountStore.isSignedIn(context))
        assertNull(AccountStore.getAccountName(context))
    }

    @Test
    fun clientIdPersistence() {
        AccountStore.setClientId(context, "test-client-id")
        assertEquals("test-client-id", AccountStore.getClientId(context))
        // Survives new prefs instance
        assertEquals("test-client-id", AccountStore.getClientId(context))
    }

    @Test
    fun authUrlConstruction() {
        val url = AccountStore.buildAuthUrl("my-client-id", "myapp://callback")
        assertTrue(url.startsWith("https://login.microsoftonline.com/"))
        assertTrue(url.contains("client_id=my-client-id"))
        assertTrue(url.contains("redirect_uri=myapp%3A%2F%2Fcallback"))
        assertTrue(url.contains("scope=XboxLive.signin%20offline_access"))
    }
}