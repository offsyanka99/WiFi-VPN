package com.wifivpn.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID

/**
 * Shared secret for monitor start/stop / widget toggle intents.
 *
 * [MainActivity] and widget receivers are exported entry points; without this token,
 * any app could start or stop monitoring by forging extras or [ACTION_TOGGLE].
 * Only our own PendingIntents / same-app components embed the token.
 */
object InternalIntentAuth {

    const val EXTRA_TOKEN = "com.wifivpn.app.extra.INTERNAL_AUTH_TOKEN"

    private const val PREFS = "internal_intent_auth"
    private const val KEY_TOKEN = "token"

    @Volatile
    private var cachedToken: String? = null

    fun token(context: Context): String {
        cachedToken?.let { return it }
        synchronized(this) {
            cachedToken?.let { return it }
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also {
                    prefs.edit { putString(KEY_TOKEN, it) }
                }
            cachedToken = token
            return token
        }
    }

    fun Intent.putInternalAuth(context: Context): Intent {
        putExtra(EXTRA_TOKEN, token(context))
        return this
    }

    fun Intent.hasValidInternalAuth(context: Context): Boolean {
        val provided = getStringExtra(EXTRA_TOKEN) ?: return false
        // Constant-time compare so a forged intent cannot probe the token byte by byte.
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            token(context).toByteArray(Charsets.UTF_8)
        )
    }
}
