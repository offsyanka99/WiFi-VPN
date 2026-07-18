package com.wifivpn.app.vpn

/**
 * Snapshot of WireGuard tunnel transfer counters and derived rates.
 *
 * Rates are bytes/second since the previous sample (0 on the first sample).
 * [latestHandshakeEpochMillis] is wall-clock epoch ms of the newest peer handshake, or 0 if none.
 */
data class TunnelTransferStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxRateBps: Double = 0.0,
    val txRateBps: Double = 0.0,
    val latestHandshakeEpochMillis: Long = 0L
)
