package com.wifivpn.app.log

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pseudonymises network identifiers written to the diagnostic log.
 *
 * The log is shareable by email, so raw SSIDs, BSSIDs and VPN endpoint hosts must not
 * appear in it by default: together they are a strong location/identity fingerprint.
 * Values are replaced by a truncated HMAC-SHA256 under a random per-install salt, which
 * keeps them *correlatable within one log* (so "same network as before" is still visible)
 * while being useless outside this device.
 */
object LogRedactor {

    private const val PREFS = "diagnostic_redaction"
    private const val KEY_SALT = "salt"
    private const val SALT_BYTES = 16
    private const val DIGEST_CHARS = 8

    @Volatile
    private var cachedSalt: ByteArray? = null

    /** `"U6"` -> `"#3f9a1c07"`, blank -> `"none"`. */
    fun ssid(context: Context, value: String?): String = tag(context, value, "none")

    /** `"nid:3"` / `"bssid:aa:bb:.."` -> `"nid#3f9a1c07"` so the key *kind* stays visible. */
    fun assoc(context: Context, value: String?): String {
        if (value.isNullOrBlank()) return "none"
        val sep = value.indexOf(':')
        if (sep <= 0) return tag(context, value, "none")
        val kind = value.substring(0, sep)
        return "$kind#${digest(context, value.substring(sep + 1))}"
    }

    /** `"vpn.example.com:51820"` -> `"#3f9a1c07:51820"`; the port is not identifying. */
    fun endpoint(context: Context, value: String?): String {
        if (value.isNullOrBlank()) return "none"
        val sep = value.lastIndexOf(':')
        // Bare IPv6 without a port has many colons and no bracket terminator — hash whole.
        if (sep <= 0 || !value.substring(sep + 1).all { it.isDigit() }) {
            return tag(context, value, "none")
        }
        return "#${digest(context, value.substring(0, sep))}:${value.substring(sep + 1)}"
    }

    private fun tag(context: Context, value: String?, empty: String): String {
        if (value.isNullOrBlank()) return empty
        return "#${digest(context, value)}"
    }

    private fun digest(context: Context, value: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(salt(context), "HmacSHA256"))
            }
            mac.doFinal(value.lowercase().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(DIGEST_CHARS)
        } catch (_: Exception) {
            "?"
        }
    }

    private fun salt(context: Context): ByteArray {
        cachedSalt?.let { return it }
        synchronized(this) {
            cachedSalt?.let { return it }
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_SALT, null)
            val salt = stored?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                ?: ByteArray(SALT_BYTES).also { fresh ->
                    SecureRandom().nextBytes(fresh)
                    prefs.edit {
                        putString(KEY_SALT, Base64.encodeToString(fresh, Base64.NO_WRAP))
                    }
                }
            cachedSalt = salt
            return salt
        }
    }
}
