package dev.xykell.client.runtime.accounts

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Local account state model. No real MSAL integration (requires
 * app registration client_id). Provides scaffold for official
 * Microsoft/Xbox auth handoff.
 *
 * SECURITY NOTE: This implementation uses plain SharedPreferences.
 * Production builds MUST replace getPrefs() with EncryptedSharedPreferences
 * from androidx.security:security-crypto using Android Keystore-backed
 * master key. This scaffold stores tokens in plaintext — DO NOT USE
 * in production without enabling encrypted storage.
 *
 * See: https://developer.android.com/topic/security/data
 * Dependency: androidx.security:security-crypto:1.1.0-alpha03+
 */
object AccountStore {

    private const val PREFS_NAME = "xykell_accounts"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_CLIENT_ID = "client_id"

    /** Returns SharedPreferences. SCAFFOLD: replace with EncryptedSharedPreferences in production. */
    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSignedIn(context: Context): Boolean =
        getPrefs(context).getString(KEY_AUTH_TOKEN, null) != null

    fun getAccountName(context: Context): String? =
        getPrefs(context).getString(KEY_ACCOUNT_NAME, null)

    fun getAuthToken(context: Context): String? =
        getPrefs(context).getString(KEY_AUTH_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        getPrefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun setClientId(context: Context, clientId: String) {
        getPrefs(context).edit().putString(KEY_CLIENT_ID, clientId).apply()
    }

    fun getClientId(context: Context): String? =
        getPrefs(context).getString(KEY_CLIENT_ID, null)

    fun signIn(context: Context, name: String, authToken: String, refreshToken: String) {
        getPrefs(context).edit()
            .putString(KEY_ACCOUNT_NAME, name)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun signOut(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    /** Build Microsoft auth URL for external browser handoff.
     *  Requires a valid client_id (app registration). */
    fun buildAuthUrl(clientId: String, redirectUri: String): String {
        val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        return "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?" +
            "client_id=$clientId" +
            "&response_type=code" +
            "&redirect_uri=$encodedRedirect" +
            "&scope=XboxLive.signin%20offline_access" +
            "&prompt=select_account"
    }
}