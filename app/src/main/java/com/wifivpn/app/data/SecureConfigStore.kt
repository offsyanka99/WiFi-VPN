package com.wifivpn.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for WireGuard config (private keys must not sit in plain DataStore).
 */
class SecureConfigStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var config: String
        get() = prefs.getString(KEY_CONFIG, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CONFIG, value).apply()
        }

    var fileName: String
        get() = prefs.getString(KEY_FILE_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_FILE_NAME, value).apply()
        }

    fun set(config: String, fileName: String) {
        prefs.edit()
            .putString(KEY_CONFIG, config)
            .putString(KEY_FILE_NAME, fileName)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_CONFIG)
            .remove(KEY_FILE_NAME)
            .apply()
    }

    fun isEmpty(): Boolean = config.isBlank()

    companion object {
        private const val PREFS_NAME = "wg_secure_prefs"
        private const val KEY_CONFIG = "wg_config"
        private const val KEY_FILE_NAME = "wg_config_file_name"
    }
}
