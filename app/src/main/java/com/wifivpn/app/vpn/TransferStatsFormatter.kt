package com.wifivpn.app.vpn

import android.content.Context
import com.wifivpn.app.R
import java.util.Locale
import kotlin.math.roundToLong

/** Human-readable formatting for tunnel transfer totals, rates, and handshake age. */
object TransferStatsFormatter {

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatRate(bytesPerSecond: Double): String {
        val bps = bytesPerSecond.coerceAtLeast(0.0)
        return when {
            bps < 1024.0 -> "${bps.roundToLong()} B/s"
            bps < 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
            bps < 1024.0 * 1024.0 * 1024.0 ->
                String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB/s", bps / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Relative age of the last successful handshake, using wall-clock time.
     * [epochMillis] of 0 means no handshake yet.
     */
    fun formatHandshakeAge(
        context: Context,
        epochMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        if (epochMillis <= 0L) {
            return context.getString(R.string.transfer_handshake_never)
        }
        val ageSec = ((nowMillis - epochMillis) / 1000L).coerceAtLeast(0L)
        return when {
            ageSec < 60L ->
                context.getString(R.string.transfer_handshake_seconds, ageSec)
            ageSec < 3600L ->
                context.getString(R.string.transfer_handshake_minutes, ageSec / 60L)
            ageSec < 86_400L ->
                context.getString(R.string.transfer_handshake_hours, ageSec / 3600L)
            else ->
                context.getString(R.string.transfer_handshake_days, ageSec / 86_400L)
        }
    }

    /** Compact totals line for widgets: ↓rx · ↑tx. */
    fun formatWidgetTransferLine(context: Context, stats: TunnelTransferStats): String {
        return context.getString(
            R.string.widget_transfer_line,
            formatBytes(stats.rxBytes),
            formatBytes(stats.txBytes)
        )
    }

    /** Handshake age line for widgets: Handshake: 45s ago. */
    fun formatWidgetHandshakeLine(context: Context, stats: TunnelTransferStats): String {
        return context.getString(
            R.string.widget_handshake_line,
            formatHandshakeAge(context, stats.latestHandshakeEpochMillis)
        )
    }
}
