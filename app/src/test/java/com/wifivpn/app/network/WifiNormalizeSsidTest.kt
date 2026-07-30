package com.wifivpn.app.network

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiNormalizeSsidTest {

    @Test
    fun normalizeSsid_rejectsUnknownPlatformMarker() {
        assertNull(WifiConnectivityMonitor.normalizeSsid(null))
        assertNull(WifiConnectivityMonitor.normalizeSsid(""))
        assertNull(WifiConnectivityMonitor.normalizeSsid(WifiManager.UNKNOWN_SSID))
        assertNull(WifiConnectivityMonitor.normalizeSsid("<unknown ssid>"))
        assertNull(WifiConnectivityMonitor.normalizeSsid("<UNKNOWN SSID>"))
    }

    @Test
    fun normalizeSsid_acceptsNormalNames() {
        assertEquals("U6", WifiConnectivityMonitor.normalizeSsid("U6"))
        assertEquals("U6", WifiConnectivityMonitor.normalizeSsid("\"U6\""))
        assertEquals("My Network", WifiConnectivityMonitor.normalizeSsid("  My Network  "))
    }
}
