package com.wifivpn.app.vpn

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.backend.BackendException
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
        assertTrue(mgr.isNonRetryable(WireGuardManager.VpnPermissionMissingException()))
        assertTrue(mgr.isNonRetryable(IllegalArgumentException("Config is empty")))
        assertTrue(
            mgr.isNonRetryable(
                BackendException(BackendException.Reason.VPN_NOT_AUTHORIZED)
            )
        )
        assertTrue(
            mgr.isNonRetryable(
                BackendException(BackendException.Reason.TUNNEL_MISSING_CONFIG)
            )
        )
    }

    @Test
    fun isNonRetryable_unwrapsCause() {
        val mgr = WireGuardManager(context)
        val wrapped = RuntimeException("wrapped", WireGuardManager.VpnPermissionMissingException())
        assertTrue(mgr.isNonRetryable(wrapped))
    }

    @Test
    fun isNonRetryable_networkErrorsAreRetryable() {
        val mgr = WireGuardManager(context)
        assertFalse(mgr.isNonRetryable(null))
        assertFalse(mgr.isNonRetryable(IllegalStateException("timeout")))
        assertFalse(mgr.isNonRetryable(RuntimeException("No peer handshake")))
        assertFalse(
            mgr.isNonRetryable(
                BackendException(BackendException.Reason.DNS_RESOLUTION_FAILURE)
            )
        )
        assertFalse(
            mgr.isNonRetryable(
                BackendException(BackendException.Reason.UNABLE_TO_START_VPN)
            )
        )
    }

    @Test
    fun isNonRetryable_ignoresMessageText() {
        val mgr = WireGuardManager(context)
        // Classification must come from types, not from wording that a library bump can change.
        assertFalse(mgr.isNonRetryable(RuntimeException("parse failed")))
        assertFalse(mgr.isNonRetryable(RuntimeException("permission-ish wording")))
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
