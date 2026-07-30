package com.wifivpn.app.util

import android.content.Context
import android.content.Intent
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

    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    fun Intent.putInternalAuth(context: Context): Intent {
        putExtra(EXTRA_TOKEN, token(context))
        return this
    }

    fun Intent.hasValidInternalAuth(context: Context): Boolean {
        val provided = getStringExtra(EXTRA_TOKEN) ?: return false
        return provided == token(context)
    }
}
