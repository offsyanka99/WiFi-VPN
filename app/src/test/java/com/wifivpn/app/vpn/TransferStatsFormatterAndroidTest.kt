package com.wifivpn.app.vpn

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferStatsFormatterAndroidTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun formatHandshakeAge_neverAndBuckets() {
        assertEquals(
            context.getString(com.wifivpn.app.R.string.transfer_handshake_never),
            TransferStatsFormatter.formatHandshakeAge(context, 0L, nowMillis = 1_000_000L)
        )
        val now = 1_000_000L
        val age45s = TransferStatsFormatter.formatHandshakeAge(
            context,
            epochMillis = now - 45_000L,
            nowMillis = now
        )
        assertTrue(age45s.contains("45"))
        val age5m = TransferStatsFormatter.formatHandshakeAge(
            context,
            epochMillis = now - 5 * 60_000L,
            nowMillis = now
        )
        assertTrue(age5m.contains("5"))
    }

    @Test
    fun formatWidgetLines() {
        val stats = TunnelTransferStats(
            rxBytes = 2048L,
            txBytes = 1024L,
            rxRateBps = 0.0,
            txRateBps = 0.0,
            latestHandshakeEpochMillis = System.currentTimeMillis() - 10_000L
        )
        val transfer = TransferStatsFormatter.formatWidgetTransferLine(context, stats)
        assertTrue(transfer.contains("KB") || transfer.contains("B"))
        val hs = TransferStatsFormatter.formatWidgetHandshakeLine(context, stats)
        assertTrue(hs.isNotBlank())
    }
}
