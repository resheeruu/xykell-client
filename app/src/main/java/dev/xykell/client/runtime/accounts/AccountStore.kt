package dev.xykell.client.runtime.accounts

import android.content.Context
import android.content.SharedPreferences

/**
 * Local account state. Tokens are held as AES-256-GCM ciphertext under an
 * AndroidKeyStore-held key, so nothing readable survives in SharedPreferences
 * or in an app backup.
 *
 * Rules enforced here:
 *   - the plaintext token is never written to disk, never returned by any
 *     toString, and never handed to JNI, the script API, or diagnostics
 *   - access and refresh tokens use different AAD purposes, so one ciphertext
 *     cannot be replayed as the other
 *   - corrupt or foreign ciphertext is discarded, not retried and not logged
 *   - a pre-encryption build migrates in place on first read
 *   - logout deletes the entries and destroys the key
 *
 * MSAL itself is not integrated: that needs an Azure app registration. The
 * auth *storage* is secure regardless of whether sign-in is wired up yet.
 */
object AccountStore {

    private const val PREFS_NAME = "xykell_accounts"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_MIGRATED = "v2_migrated"

    private const val PURPOSE_AUTH = "auth_token"
    private const val PURPOSE_REFRESH = "refresh_token"

    @Volatile
    private var box: SecretBox? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun secretBox(): SecretBox = box ?: synchronized(this) {
        box ?: SecretBox(KeystoreKeyProvider()).also { box = it }
    }

    /** Swaps the crypto backend. Test-only; never called from app code. */
    fun useTestKeyProvider(provider: SecretBox.SecretKeyProvider) {
        synchronized(this) { box = SecretBox(provider) }
    }

    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val editor = p.edit()
        // A v1 build stored both tokens as plaintext. Re-encrypt in place; the
        // plaintext is overwritten in the same commit that removes the key.
        for ((key, purpose) in listOf(
            KEY_AUTH_TOKEN to PURPOSE_AUTH,
            KEY_REFRESH_TOKEN to PURPOSE_REFRESH,
        )) {
            val legacy = p.getString(key, null)
            if (!legacy.isNullOrEmpty() && !legacy.startsWith(ENVELOPE_PREFIX)) {
                when (val enc = secretBox().encrypt(purpose, legacy)) {
                    is SecretBox.Result.Ok -> editor.putString(key, enc.value)
                    else -> editor.remove(key) // cannot protect it, so drop it
                }
            }
        }
        editor.putBoolean(KEY_MIGRATED, true).commit()
    }

    fun isSignedIn(context: Context): Boolean {
        migrateIfNeeded(context)
        val stored = prefs(context).getString(KEY_AUTH_TOKEN, null)
        return !stored.isNullOrEmpty() && secretBox().decrypt(PURPOSE_AUTH, stored).isOk()
    }

    fun getAccountName(context: Context): String? {
        migrateIfNeeded(context)
        return prefs(context).getString(KEY_ACCOUNT_NAME, null)
    }

    /** Caller must treat the value as secret: never log it, never export it. */
    fun getAuthToken(context: Context): String? = readToken(context, KEY_AUTH_TOKEN, PURPOSE_AUTH)

    fun getRefreshToken(context: Context): String? =
        readToken(context, KEY_REFRESH_TOKEN, PURPOSE_REFRESH)

    private fun readToken(context: Context, key: String, purpose: String): String? {
        migrateIfNeeded(context)
        val stored = prefs(context).getString(key, null)
        if (stored.isNullOrEmpty()) return null
        return when (val r = secretBox().decrypt(purpose, stored)) {
            is SecretBox.Result.Ok -> r.value
            // Corrupt, tampered, or written under a lost key: drop it so the
            // user is asked to sign in again rather than looping on bad data.
            is SecretBox.Result.Corrupt -> {
                prefs(context).edit().remove(key).commit()
                null
            }
            is SecretBox.Result.Unavailable -> null
        }
    }

    fun setClientId(context: Context, clientId: String) {
        // Not a secret: an app id is public in the auth request.
        prefs(context).edit().putString(KEY_CLIENT_ID, clientId).apply()
    }

    fun getClientId(context: Context): String? = prefs(context).getString(KEY_CLIENT_ID, null)

    fun signIn(context: Context, name: String, authToken: String, refreshToken: String): Boolean {
        migrateIfNeeded(context)
        val a = secretBox().encrypt(PURPOSE_AUTH, authToken)
        val r = secretBox().encrypt(PURPOSE_REFRESH, refreshToken)
        if (a !is SecretBox.Result.Ok || r !is SecretBox.Result.Ok) return false
        return prefs(context).edit()
            .putString(KEY_ACCOUNT_NAME, name)
            .putString(KEY_AUTH_TOKEN, a.value)
            .putString(KEY_REFRESH_TOKEN, r.value)
            .commit()
    }

    /** Deletes the ciphertext and destroys the key, so the old tokens cannot be
     *  recovered even from a pre-delete backup of the app's data. */
    fun signOut(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .commit()
        (KeystoreKeyProvider()).destroyAll()
        synchronized(this) { box = null }
    }

    /**
     * Account fields safe to show, export, or put in a crash report. There is
     * deliberately no token field here.
     */
    fun redactedSummary(context: Context): String {
        migrateIfNeeded(context)
        val name = getAccountName(context) ?: "(none)"
        val client = getClientId(context) ?: "(unset)"
        val signedIn = isSignedIn(context)
        return "account=$name client_id=$client signed_in=$signedIn tokens=<redacted>"
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

    private const val ENVELOPE_PREFIX = "v1:"
}
