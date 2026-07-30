package com.wifivpn.app.util

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wifivpn.app.util.InternalIntentAuth.hasValidInternalAuth
import com.wifivpn.app.util.InternalIntentAuth.putInternalAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InternalIntentAuthTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun token_stableAcrossCalls() {
        val a = InternalIntentAuth.token(context)
        val b = InternalIntentAuth.token(context)
        assertTrue(a.isNotBlank())
        assertEquals(a, b)
    }

    @Test
    fun putInternalAuth_validates() {
        val intent = Intent().putInternalAuth(context)
        assertTrue(intent.hasValidInternalAuth(context))
    }

    @Test
    fun missingOrWrongToken_rejected() {
        assertFalse(Intent().hasValidInternalAuth(context))
        val forged = Intent().putExtra(InternalIntentAuth.EXTRA_TOKEN, "not-the-real-token")
        assertFalse(forged.hasValidInternalAuth(context))
    }
}
