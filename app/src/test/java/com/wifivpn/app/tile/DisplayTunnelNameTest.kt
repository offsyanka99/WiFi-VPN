package com.wifivpn.app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTunnelNameTest {

    @Test
    fun displayTunnelName_stripsPathAndExtension() {
        assertEquals(
            "home",
            MonitorTileService.displayTunnelName("/sdcard/Download/home.conf")
        )
        assertEquals(
            "office",
            MonitorTileService.displayTunnelName("C:\\cfgs\\office.CONF")
        )
        assertEquals("wg-tunnel", MonitorTileService.displayTunnelName("wg-tunnel.wg"))
    }

    @Test
    fun displayTunnelName_empty() {
        assertNull(MonitorTileService.displayTunnelName(""))
        assertNull(MonitorTileService.displayTunnelName("   "))
        assertNull(MonitorTileService.displayTunnelName(".conf"))
    }
}
