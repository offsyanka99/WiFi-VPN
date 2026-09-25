package com.wifivpn.app.log

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ensures support fingerprints never embed private keys, PSKs, or the plain endpoint host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticSupportTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun configFingerprint_empty() {
        assertTrue(DiagnosticSupport.configFingerprint(context, "").contains("empty"))
        assertTrue(DiagnosticSupport.configFingerprint(context, "   ").contains("empty"))
    }

    @Test
    fun configFingerprint_invalidIsParseError() {
        val fp = DiagnosticSupport.configFingerprint(context, "not-valid-config")
        assertTrue(fp.startsWith("parse_error="))
    }

    @Test
    fun configFingerprint_validOmitsSecrets() {
        // Deterministic Curve25519 keys (32 bytes) as standard WireGuard base64.
        val privateKey = "cGFzc3dvcmRwYXNzd29yZHBhc3N3b3JkcGFzc3dvcmQ="
        val publicKey = "cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVi="
        val conf = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.7.0.2/32
            DNS = 1.1.1.1
            MTU = 1420

            [Peer]
            PublicKey = $publicKey
            PresharedKey = $privateKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = opnsense.example.com:51821
        """.trimIndent()

        val fp = DiagnosticSupport.configFingerprint(context, conf)
        // If parse fails due to invalid key material, still must not echo secrets.
        assertFalse(fp.contains(privateKey))
        assertFalse(fp.contains(publicKey))
        assertFalse(fp.contains("PresharedKey", ignoreCase = true))
        assertFalse(fp.contains("PrivateKey", ignoreCase = true))
        if (!fp.startsWith("parse_error=")) {
            assertTrue(fp.contains("peers="))
            assertTrue(fp.contains("ep=") || fp.contains("p0{"))
        }
    }

    @Test
    fun configFingerprint_neverEchoesEndpointHost() {
        val conf = """
            [Interface]
            PrivateKey = cGFzc3dvcmRwYXNzd29yZHBhc3N3b3JkcGFzc3dvcmQ=
            Address = 10.7.0.2/32

            [Peer]
            PublicKey = cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVi=
            AllowedIPs = 0.0.0.0/0
            Endpoint = opnsense.example.com:51821
        """.trimIndent()

        val fp = DiagnosticSupport.configFingerprint(context, conf)
        assertFalse(fp.contains("opnsense.example.com"))
    }
}
