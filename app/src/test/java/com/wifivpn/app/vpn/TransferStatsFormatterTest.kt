package com.wifivpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStatsFormatterTest {

    @Test
    fun formatBytes_scales() {
        assertEquals("0 B", TransferStatsFormatter.formatBytes(0))
        assertEquals("512 B", TransferStatsFormatter.formatBytes(512))
        assertEquals("1.0 KB", TransferStatsFormatter.formatBytes(1024))
        assertEquals("1.50 MB", TransferStatsFormatter.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertTrue(TransferStatsFormatter.formatBytes(3L * 1024 * 1024 * 1024).endsWith(" GB"))
    }

    @Test
    fun formatBytes_negativeCoercedToZero() {
        assertEquals("0 B", TransferStatsFormatter.formatBytes(-10))
    }

    @Test
    fun formatRate_scales() {
        assertEquals("0 B/s", TransferStatsFormatter.formatRate(0.0))
        assertEquals("100 B/s", TransferStatsFormatter.formatRate(100.0))
        assertEquals("1.5 KB/s", TransferStatsFormatter.formatRate(1536.0))
        assertTrue(TransferStatsFormatter.formatRate(2.5 * 1024 * 1024).contains("MB/s"))
    }

    @Test
    fun formatRate_negativeCoercedToZero() {
        assertEquals("0 B/s", TransferStatsFormatter.formatRate(-5.0))
    }
}
