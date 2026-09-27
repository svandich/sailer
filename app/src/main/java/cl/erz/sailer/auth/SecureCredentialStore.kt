package cl.erz.sailer.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class SavedCredentials(val username: String, val password: String)

/**
 * Persists the u-cursos.cl username/password on-device so sailer can retry a
 * login automatically after a network error or an expired session, without
 * asking the user to type their password again every time.
 *
 * The values are stored in [EncryptedSharedPreferences], which encrypts both
 * keys and values with AES-256 using a master key that lives in the Android
 * Keystore. The key material never leaves secure hardware/OS-protected
 * storage, and the encrypted file is meaningless without it - so a copy of
 * the file alone (e.g. from a backup or a rooted device's filesystem) is not
 * enough to recover the credentials. `allowBackup="false"` in the manifest
 * additionally keeps this file out of Auto Backup entirely.
 */
class SecureCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun read(): SavedCredentials? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return SavedCredentials(username, password)
    }

    fun hasSavedCredentials(): Boolean = prefs.contains(KEY_USERNAME) && prefs.contains(KEY_PASSWORD)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "sailer_secure_credentials"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
