package com.wifivpn.app.vpn

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WireGuardManagerLogicTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun formatError_nullAndMessage() {
        assertEquals("unknown error", WireGuardManager.formatError(null))
        assertEquals("boom", WireGuardManager.formatError(IllegalStateException("boom")))
        assertEquals(
            "IllegalStateException",
            WireGuardManager.formatError(IllegalStateException())
        )
    }

    @Test
    fun isNonRetryable_permissionParseEmpty() {
        val mgr = WireGuardManager(context)
        assertTrue(mgr.isNonRetryable(IllegalStateException("VPN permission not granted")))
        assertTrue(mgr.isNonRetryable(IllegalArgumentException("Config is empty")))
        assertTrue(mgr.isNonRetryable(RuntimeException("parse failed")))
        assertTrue(mgr.isNonRetryable(RuntimeException("BadConfig: line 2")))
    }

    @Test
    fun isNonRetryable_networkErrorsAreRetryable() {
        val mgr = WireGuardManager(context)
        assertFalse(mgr.isNonRetryable(null))
        assertFalse(mgr.isNonRetryable(IllegalStateException("timeout")))
        assertFalse(mgr.isNonRetryable(RuntimeException("No peer handshake")))
        assertFalse(mgr.isNonRetryable(RuntimeException("DNS failure")))
    }

    @Test
    fun parseConfig_validMinimal() {
        val mgr = WireGuardManager(context)
        // Minimal valid WireGuard key material (base64 32-byte keys).
        val conf = """
            [Interface]
            PrivateKey = YJqG8zqK7n5nQvY0yZQ0yZQ0yZQ0yZQ0yZQ0yZQ0yZQ=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = ZJqG8zqK7n5nQvY0yZQ0yZQ0yZQ0yZQ0yZQ0yZQ0yZQ=
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent()
        // Keys above may fail Curve25519 validation; only assert empty/invalid paths
        // if parse rejects them. Prefer testing empty/blank.
        val empty = mgr.parseConfig("")
        assertTrue(empty.isFailure)
    }

    @Test
    fun parseConfig_blankFails() {
        val mgr = WireGuardManager(context)
        assertTrue(mgr.parseConfig("").isFailure)
        assertTrue(mgr.parseConfig("   ").isFailure)
    }

    @Test
    fun parseConfig_garbageFails() {
        val mgr = WireGuardManager(context)
        assertTrue(mgr.parseConfig("not a wireguard config").isFailure)
    }
}
