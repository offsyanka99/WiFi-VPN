package com.wifivpn.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
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
 * existing ESP values are migrated once by [migrateLegacyIfNeeded].
 *
 * Nothing touches the Keystore or disk until the first read/write, so constructing this
 * from `Application.onCreate` does not block the main thread.
 */
class SecureConfigStore(context: Context) {

    /** Whether the stored ciphertext could be decrypted on the last attempt. */
    enum class ConfigState {
        /** Config present and readable. */
        OK,

        /** Nothing stored yet. */
        EMPTY,

        /** Ciphertext present but undecryptable (Keystore key lost or blob corrupt). */
        UNREADABLE
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME_V2, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedKey: SecretKey? = null

    /** Sticky until a successful read, write, or clear. */
    @Volatile
    private var readFailure: Boolean = false

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

    /**
     * Reads the config once and reports whether it is usable. Callers must prefer this
     * over `config.isBlank()` so an invalidated Keystore key is not mistaken for
     * "user never imported a config".
     */
    fun state(): ConfigState {
        val value = read(KEY_CONFIG)
        return when {
            readFailure -> ConfigState.UNREADABLE
            value.isNullOrBlank() -> ConfigState.EMPTY
            else -> ConfigState.OK
        }
    }

    fun set(config: String, fileName: String) {
        write(KEY_CONFIG, config)
        write(KEY_FILE_NAME, fileName)
    }

    fun clear() {
        prefs.edit {
            remove(KEY_CONFIG)
            remove(KEY_FILE_NAME)
        }
        readFailure = false
    }

    fun isEmpty(): Boolean = config.isBlank()

    private fun read(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        if (blob.isEmpty()) return ""
        return try {
            decrypt(blob).also { readFailure = false }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Lock-screen credential reset / restore to a new device destroys the key.
            Log.e(TAG, "Keystore key invalidated — $key can no longer be decrypted", e)
            cachedKey = null
            readFailure = true
            null
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Decrypt failed for $key", e)
            readFailure = true
            null
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt failed for $key", e)
            readFailure = true
            null
        }
    }

    private fun write(key: String, value: String) {
        val encoded = encrypt(value)
        prefs.edit { putString(key, encoded) }
        readFailure = false
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
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            val key = existing?.secretKey ?: run {
                ensureKey()
                (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            }
            cachedKey = key
            return key
        }
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
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    /**
     * One-time copy from Jetpack EncryptedSharedPreferences into the Keystore-backed store.
     * Safe to call repeatedly; no-ops when v2 already has data or legacy store is empty.
     * Call off the main thread — opening the legacy store initialises Tink.
     */
    @Suppress("DEPRECATION")
    fun migrateLegacyIfNeeded() {
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
            // `this` is the Editor; bare clear() would read as SecureConfigStore.clear().
            legacy.edit { this.clear() }
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
