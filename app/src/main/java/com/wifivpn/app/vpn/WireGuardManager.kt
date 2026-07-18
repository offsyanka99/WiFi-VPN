package com.wifivpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.log.DiagnosticLogger
import com.wifivpn.app.log.DiagnosticSupport
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Thin wrapper around the WireGuard tunnel library (userspace Go backend).
 *
 * Tunnel state is exposed as both a snapshot ([state] / [isUp]) and a
 * [stateFlow] for UI collectors (Main screen, tile). Transfer counters are
 * available via [transferStats] after [refreshTransferStats] while the tunnel is up.
 */
class WireGuardManager(private val context: Context) {

    private val backend by lazy { GoBackend(context.applicationContext) }
    private val mutex = Mutex()
    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "Tunnel state -> $newState")
            publishState(newState)
            diagnosticLogger()?.i(CAT_TUNNEL, "state_change -> $newState")
        }
    }

    private fun diagnosticLogger(): DiagnosticLogger? =
        (context.applicationContext as? WifiVpnApp)?.diagnosticLogger

    private val _stateFlow = MutableStateFlow(Tunnel.State.DOWN)
    val stateFlow: StateFlow<Tunnel.State> = _stateFlow.asStateFlow()

    val state: Tunnel.State get() = _stateFlow.value

    val isUp: Boolean get() = _stateFlow.value == Tunnel.State.UP

    private val _transferStats = MutableStateFlow<TunnelTransferStats?>(null)
    val transferStats: StateFlow<TunnelTransferStats?> = _transferStats.asStateFlow()

    /** Previous sample for rate calculation (elapsedRealtime). */
    private var lastSampleElapsedMs: Long = 0L
    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L

    private fun publishState(newState: Tunnel.State) {
        _stateFlow.value = newState
        if (newState != Tunnel.State.UP) {
            clearTransferStats()
        }
    }

    /**
     * Reads WireGuard statistics for the active tunnel and publishes [transferStats].
     * Returns null when the tunnel is down or the backend call fails.
     * Safe to call from the service poller and the main UI; concurrent samples closer
     * than ~200ms reuse the previous rate so dual pollers do not spike B/s values.
     */
    @Synchronized
    fun refreshTransferStats(): TunnelTransferStats? {
        if (!isUp) {
            clearTransferStats()
            return null
        }
        return try {
            val stats = backend.getStatistics(tunnel)
            val nowElapsed = SystemClock.elapsedRealtime()
            val rx = stats.totalRx().coerceAtLeast(0L)
            val tx = stats.totalTx().coerceAtLeast(0L)

            var rxRate = _transferStats.value?.rxRateBps ?: 0.0
            var txRate = _transferStats.value?.txRateBps ?: 0.0
            if (lastSampleElapsedMs > 0L) {
                val dtSec = (nowElapsed - lastSampleElapsedMs) / 1000.0
                // Ignore near-simultaneous samples from UI + service pollers.
                if (dtSec >= 0.2) {
                    rxRate = (rx - lastRxBytes).coerceAtLeast(0L) / dtSec
                    txRate = (tx - lastTxBytes).coerceAtLeast(0L) / dtSec
                    lastSampleElapsedMs = nowElapsed
                    lastRxBytes = rx
                    lastTxBytes = tx
                }
            } else {
                lastSampleElapsedMs = nowElapsed
                lastRxBytes = rx
                lastTxBytes = tx
            }

            var latestHandshake = 0L
            for (key in stats.peers()) {
                val peer = stats.peer(key) ?: continue
                val hs = peer.latestHandshakeEpochMillis()
                if (hs > latestHandshake) latestHandshake = hs
            }

            val snap = TunnelTransferStats(
                rxBytes = rx,
                txBytes = tx,
                rxRateBps = rxRate,
                txRateBps = txRate,
                latestHandshakeEpochMillis = latestHandshake
            )
            _transferStats.value = snap
            snap
        } catch (e: Exception) {
            Log.w(TAG, "getStatistics failed: ${e.message}")
            null
        }
    }

    @Synchronized
    fun clearTransferStats() {
        lastSampleElapsedMs = 0L
        lastRxBytes = 0L
        lastTxBytes = 0L
        if (_transferStats.value != null) {
            _transferStats.value = null
        }
    }

    /**
     * Returns an Intent that must be started for result if VPN permission
     * has not been granted yet. Null means permission is already granted.
     */
    fun prepareVpnPermission(): Intent? = VpnService.prepare(context)

    fun parseConfig(raw: String): Result<Config> = runCatching {
        require(raw.isNotBlank()) { "Config is empty" }
        Config.parse(BufferedReader(StringReader(raw)))
    }

    /**
     * Builds a tunnel config, merging [excludedPackages] into the Interface
     * so those apps bypass the VPN (VpnService.addDisallowedApplication).
     */
    fun buildConfigWithExclusions(
        rawConfig: String,
        excludedPackages: Collection<String>
    ): Result<Config> = runCatching {
        val base = parseConfig(rawConfig).getOrThrow()
        if (excludedPackages.isEmpty() && base.`interface`.excludedApplications.isEmpty()) {
            return@runCatching base
        }

        val old = base.`interface`
        val ifaceBuilder = Interface.Builder()
            .setKeyPair(old.keyPair)
            .addAddresses(old.addresses)
            .addDnsServers(old.dnsServers)
            .addDnsSearchDomains(old.dnsSearchDomains)
            .excludeApplications(old.excludedApplications)
            .excludeApplications(excludedPackages)
            .includeApplications(old.includedApplications)

        old.listenPort.ifPresent { port -> ifaceBuilder.setListenPort(port) }
        old.mtu.ifPresent { mtu -> ifaceBuilder.setMtu(mtu) }

        Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeers(base.peers)
            .build()
    }

    suspend fun setTunnelUp(
        rawConfig: String,
        excludedPackages: Collection<String> = emptySet()
    ): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (isUp) {
                    Log.i(TAG, "Tunnel already UP — skip")
                    diagnosticLogger()?.i(CAT_TUNNEL, "bring UP skipped — already UP")
                    return@runCatching
                }
                val config = buildConfigWithExclusions(rawConfig, excludedPackages).getOrThrow()
                if (prepareVpnPermission() != null) {
                    error("VPN permission not granted")
                }
                Log.i(
                    TAG,
                    "Bringing tunnel UP; excluded=${config.`interface`.excludedApplications}"
                )
                diagnosticLogger()?.i(
                    CAT_TUNNEL,
                    "bringing UP excluded=${config.`interface`.excludedApplications.size} " +
                        "config ${DiagnosticSupport.configFingerprint(rawConfig)}"
                )
                val newState = backend.setState(tunnel, Tunnel.State.UP, config)
                publishState(newState)
                if (newState != Tunnel.State.UP) {
                    error("Tunnel did not reach UP (state=$newState)")
                }
                Log.i(TAG, "WireGuard tunnel UP")
                diagnosticLogger()?.i(
                    CAT_TUNNEL,
                    "UP success state=$newState"
                )
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to bring tunnel up: ${formatError(e)}", e)
                diagnosticLogger()?.logException(
                    CAT_TUNNEL,
                    "UP failure: ${formatError(e)}",
                    e
                )
                if (isUp) {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                    publishState(Tunnel.State.DOWN)
                }
            }
        }
    }

    suspend fun setTunnelDown(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isUp) {
                    diagnosticLogger()?.i(CAT_TUNNEL, "bring DOWN skipped — already DOWN")
                    return@runCatching
                }
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                publishState(Tunnel.State.DOWN)
                clearTransferStats()
                Log.i(TAG, "WireGuard tunnel DOWN")
                diagnosticLogger()?.i(CAT_TUNNEL, "DOWN success")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to bring tunnel down: ${formatError(e)}", e)
                diagnosticLogger()?.logException(
                    CAT_TUNNEL,
                    "DOWN failure: ${formatError(e)}",
                    e
                )
            }
        }
    }

    /** Errors that retries will not fix (skip remaining attempts). */
    fun isNonRetryable(error: Throwable?): Boolean {
        val msg = formatError(error).lowercase()
        return msg.contains("permission") ||
            msg.contains("config is empty") ||
            msg.contains("parse") ||
            msg.contains("badconfig")
    }

    companion object {
        private const val TAG = "WireGuardManager"
        private const val CAT_TUNNEL = "TUNNEL"
        const val TUNNEL_NAME = "wifi-vpn"

        fun formatError(error: Throwable?): String {
            if (error == null) return "unknown error"
            if (error is BackendException) {
                val reason = error.reason?.name ?: "BACKEND"
                val details = error.message?.takeIf { it.isNotBlank() }
                return if (details != null) "$reason: $details" else reason
            }
            return error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
        }
    }
}
