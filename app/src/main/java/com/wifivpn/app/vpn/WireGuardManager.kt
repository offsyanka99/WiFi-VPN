package com.wifivpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Thin wrapper around the WireGuard tunnel library (userspace Go backend).
 */
class WireGuardManager(private val context: Context) {

    private val backend by lazy { GoBackend(context.applicationContext) }
    private val mutex = Mutex()
    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "Tunnel state -> $newState")
            _state = newState
        }
    }

    @Volatile
    private var _state: Tunnel.State = Tunnel.State.DOWN
    val state: Tunnel.State get() = _state

    val isUp: Boolean get() = _state == Tunnel.State.UP

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
                if (_state == Tunnel.State.UP) {
                    Log.i(TAG, "Tunnel already UP — skip")
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
                val newState = backend.setState(tunnel, Tunnel.State.UP, config)
                _state = newState
                if (newState != Tunnel.State.UP) {
                    error("Tunnel did not reach UP (state=$newState)")
                }
                Log.i(TAG, "WireGuard tunnel UP")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to bring tunnel up: ${formatError(e)}", e)
                // Ensure we don't report UP after a failed attempt
                if (_state == Tunnel.State.UP) {
                    runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                    _state = Tunnel.State.DOWN
                }
            }
        }
    }

    suspend fun setTunnelDown(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (_state == Tunnel.State.DOWN) return@runCatching
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                _state = Tunnel.State.DOWN
                Log.i(TAG, "WireGuard tunnel DOWN")
                Unit
            }.onFailure { e ->
                Log.e(TAG, "Failed to bring tunnel down: ${formatError(e)}", e)
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
