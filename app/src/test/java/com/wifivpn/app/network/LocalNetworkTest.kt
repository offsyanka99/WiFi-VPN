package com.wifivpn.app.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalNetworkTest {

    @Test
    fun documentedLocalIpv4Ranges() {
        for (ip in listOf(
            "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.254",
            "192.168.1.1",
            "169.254.10.20",
            "100.64.0.1", "100.127.255.255",
            "224.0.0.251", "239.255.255.250",
            "255.255.255.255"
        )) {
            assertTrue(ip, LocalNetwork.isLocalAddressLiteral(ip))
        }
    }

    @Test
    fun publicIpv4JustOutsideEachRange() {
        for (ip in listOf(
            "9.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.32.0.0",
            "192.167.1.1", "192.169.1.1",
            "169.253.1.1",
            "100.63.255.255", "100.128.0.0",
            "223.255.255.255", "240.0.0.1",
            "1.1.1.1", "8.8.8.8"
        )) {
            assertFalse(ip, LocalNetwork.isLocalAddressLiteral(ip))
        }
    }

    @Test
    fun hostnamesAreNeverResolved() {
        assertFalse(LocalNetwork.isLocalAddressLiteral("vpn.example.com"))
        assertFalse(LocalNetwork.isLocalAddressLiteral("router.local"))
        assertFalse(LocalNetwork.isLocalAddressLiteral("localhost"))
    }

    @Test
    fun ipv6() {
        assertTrue(LocalNetwork.isLocalAddressLiteral("fe80::1"))
        assertTrue(LocalNetwork.isLocalAddressLiteral("[fe80::1]"))
        assertTrue(LocalNetwork.isLocalAddressLiteral("ff02::1"))
        assertFalse(LocalNetwork.isLocalAddressLiteral("2001:db8::1"))
        assertFalse(LocalNetwork.isLocalAddressLiteral("[2606:4700::1111]"))
    }

    @Test
    fun malformedInputIsNotLocal() {
        for (s in listOf("", " ", "10.0.0", "10.0.0.0.1", "10.0.0.256", "a.b.c.d", "10.0.0.-1")) {
            assertFalse("'$s'", LocalNetwork.isLocalAddressLiteral(s))
        }
    }

    @Test
    fun permissionNotRequiredBeforeApi37() {
        val ctx: Application = ApplicationProvider.getApplicationContext()
        assertFalse(LocalNetwork.isPermissionMissing(ctx))
        assertFalse(LocalNetwork.blocksConfig(ctx, null))
    }
}
