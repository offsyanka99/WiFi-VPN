package com.wifivpn.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for WireGuard config (private keys must not sit in plain DataStore).
 *
 * Uses the Android Keystore AES-GCM key + ordinary [SharedPreferences] for ciphertext.
 * Replaces deprecated Jetpack [androidx.security.crypto.EncryptedSharedPreferences];
 * existing ESP values are migrated once on first open.
 */
class SecureConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME_V2, Context.MODE_PRIVATE)

    init {
        ensureKey()
        migrateFromEncryptedSharedPreferencesIfNeeded()
    }

    var config: String
        get() = read(KEY_CONFIG).orEmpty()
        set(value) {
            write(KEY_CONFIG, value)
        }

    var fileName: String
        get() = read(KEY_FILE_NAME).orEmpty()
        set(value) {
            write(KEY_FILE_NAME, value)
        }

    fun set(config: String, fileName: String) {
        write(KEY_CONFIG, config)
        write(KEY_FILE_NAME, fileName)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_CONFIG)
            .remove(KEY_FILE_NAME)
            .apply()
    }

    fun isEmpty(): Boolean = config.isBlank()

    private fun read(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        if (blob.isEmpty()) return ""
        return try {
            decrypt(blob)
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt failed for $key", e)
            null
        }
    }

    private fun write(key: String, value: String) {
        val encoded = encrypt(value)
        prefs.edit().putString(key, encoded).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // iv_len (1 byte) | iv | ciphertext
        val packed = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(packed)
        val ivLen = buffer.get().toInt() and 0xff
        require(ivLen in 12..32) { "Invalid IV length: $ivLen" }
        val iv = ByteArray(ivLen)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        ensureKey()
        val created = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return created.secretKey
    }

    private fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    /**
     * One-time copy from Jetpack EncryptedSharedPreferences into the Keystore-backed store.
     * Safe to call repeatedly; no-ops when v2 already has data or legacy store is empty.
     */
    @Suppress("DEPRECATION")
    private fun migrateFromEncryptedSharedPreferencesIfNeeded() {
        if (prefs.contains(KEY_CONFIG) || prefs.contains(KEY_FILE_NAME)) return
        val legacyXml = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME_LEGACY.xml")
        if (!legacyXml.exists()) return
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(appContext)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val legacy = androidx.security.crypto.EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME_LEGACY,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val legacyConfig = legacy.getString(KEY_CONFIG, null)
            val legacyFileName = legacy.getString(KEY_FILE_NAME, null)
            if (legacyConfig.isNullOrBlank() && legacyFileName.isNullOrBlank()) {
                return
            }
            if (!legacyConfig.isNullOrBlank()) {
                write(KEY_CONFIG, legacyConfig)
            }
            if (!legacyFileName.isNullOrBlank()) {
                write(KEY_FILE_NAME, legacyFileName)
            }
            legacy.edit().clear().apply()
            Log.i(TAG, "Migrated WireGuard secrets from EncryptedSharedPreferences to Keystore store")
        } catch (e: Exception) {
            // Missing legacy store or unreadable ESP — keep empty v2 store
            Log.w(TAG, "Legacy EncryptedSharedPreferences migration skipped: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SecureConfigStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.wifivpn.app.wg_config_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        /** New Keystore-backed prefs file. */
        private const val PREFS_NAME_V2 = "wg_secure_prefs_v2"
        /** Previous Jetpack EncryptedSharedPreferences file name. */
        private const val PREFS_NAME_LEGACY = "wg_secure_prefs"
        private const val KEY_CONFIG = "wg_config"
        private const val KEY_FILE_NAME = "wg_config_file_name"
    }
}
