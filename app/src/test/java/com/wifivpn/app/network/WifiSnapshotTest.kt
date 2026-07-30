package com.wifivpn.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSnapshotTest {

    private fun snap(
        wifi: Boolean = true,
        ssid: String? = "U6",
        trusted: Boolean = true,
        cellular: Boolean = false,
        transports: String = "WIFI"
    ) = WifiConnectivityMonitor.WifiSnapshot(
        wifiConnected = wifi,
        ssid = ssid,
        onTrustedWifi = trusted,
        cellularConnected = cellular,
        transports = transports
    )

    @Test
    fun samePolicyAs_ignoresTransportsVpnOnlyChange() {
        val a = snap(transports = "WIFI")
        val b = snap(transports = "VPN,WIFI")
        assertTrue(a.samePolicyAs(b))
    }

    @Test
    fun samePolicyAs_detectsSsidAndTrustChange() {
        val home = snap(ssid = "U6", trusted = true)
        val cafe = snap(ssid = "Cafe", trusted = false)
        assertFalse(home.samePolicyAs(cafe))
    }

    @Test
    fun samePolicyAs_detectsWifiDisconnect() {
        val up = snap(wifi = true)
        val down = snap(wifi = false, ssid = null, trusted = false, transports = "CELLULAR")
        assertFalse(up.samePolicyAs(down))
    }

    @Test
    fun samePolicyAs_detectsCellularFlip() {
        val a = snap(cellular = false)
        val b = snap(cellular = true, transports = "CELLULAR,WIFI")
        assertFalse(a.samePolicyAs(b))
    }

    @Test
    fun samePolicyAs_identical() {
        val a = snap()
        val b = snap()
        assertTrue(a.samePolicyAs(b))
    }
}
