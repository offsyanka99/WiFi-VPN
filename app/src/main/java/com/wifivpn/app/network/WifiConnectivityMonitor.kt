package com.wifivpn.app.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.wifivpn.app.data.ConfigRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes Wi‑Fi connectivity and current SSID (when permissions allow).
 *
 * On Android 12+ (API 31), [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO]
 * is required to obtain SSID from network capabilities. Without it, SSID stays unknown
 * after network switches and trusted-Wi‑Fi detection fails.
 */
class WifiConnectivityMonitor(private val context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    data class WifiSnapshot(
        /** Any Wi‑Fi transport with internet is up. */
        val wifiConnected: Boolean,
        /** Current SSID if readable; null if offline or permission/location blocks it. */
        val ssid: String?,
        /** True only when connected to a network whose SSID is in [trustedSsids]. */
        val onTrustedWifi: Boolean
    )

    fun isWifiConnected(): Boolean {
        for (network in connectivityManager.allNetworks) {
            if (isWifiNetwork(network)) return true
        }
        // Fallback: Wi‑Fi manager association (helps when caps lag under VPN)
        return isWifiManagerConnected()
    }

    private fun isWifiNetwork(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Suppress("DEPRECATION")
    private fun isWifiManagerConnected(): Boolean {
        return try {
            wifiManager.isWifiEnabled &&
                wifiManager.connectionInfo != null &&
                wifiManager.connectionInfo.networkId != -1
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Best-effort current SSID from several sources (needed when VPN is the default network).
     */
    fun currentSsid(): String? {
        if (!hasSsidPermission()) {
            Log.w(TAG, "No permission to read SSID")
            return null
        }

        // 1) Prefer Wi‑Fi NetworkCapabilities.transportInfo (works better with VPN default route)
        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            ssidFromCapabilities(caps)?.let { return it }
        }

        // 2) Active network if it is Wi‑Fi
        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    ssidFromCapabilities(caps)?.let { return it }
                }
            }
        }

        // 3) Legacy WifiManager
        return ssidFromWifiManager()
    }

    private fun ssidFromCapabilities(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val transport = caps.transportInfo
        if (transport is WifiInfo) {
            return normalizeSsid(transport.ssid)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun ssidFromWifiManager(): String? {
        return try {
            normalizeSsid(wifiManager.connectionInfo?.ssid)
        } catch (e: SecurityException) {
            Log.w(TAG, "SSID read denied", e)
            null
        }
    }

    fun snapshot(trustedSsids: Set<String>): WifiSnapshot {
        val connected = isWifiConnected()
        val ssid = if (connected) currentSsid() else null
        val onTrusted = connected &&
            ssid != null &&
            trustedSsids.any { it.equals(ssid, ignoreCase = true) }
        return WifiSnapshot(
            wifiConnected = connected,
            ssid = ssid,
            onTrustedWifi = onTrusted
        )
    }

    /**
     * Emits [WifiSnapshot] on network / Wi‑Fi changes.
     * When Wi‑Fi is up but SSID is still unknown, retries a few times (common right after a roam).
     */
    fun wifiStatusFlow(trustedSsidsProvider: () -> Set<String>): Flow<WifiSnapshot> = callbackFlow {
        val retryRunnables = mutableListOf<Runnable>()

        fun clearRetries() {
            retryRunnables.forEach { mainHandler.removeCallbacks(it) }
            retryRunnables.clear()
        }

        fun emitCurrent() {
            val snap = snapshot(trustedSsidsProvider())
            Log.i(
                TAG,
                "WiFi snap connected=${snap.wifiConnected} ssid=${snap.ssid} trusted=${snap.onTrustedWifi}"
            )
            trySend(snap)

            // SSID often lags a few hundred ms after switching networks / while VPN is up
            if (snap.wifiConnected && snap.ssid == null && hasSsidPermission()) {
                clearRetries()
                val delaysMs = longArrayOf(250L, 600L, 1200L, 2500L, 4000L)
                delaysMs.forEach { delay ->
                    val r = Runnable {
                        val retry = snapshot(trustedSsidsProvider())
                        Log.i(
                            TAG,
                            "WiFi retry(+${delay}ms) ssid=${retry.ssid} trusted=${retry.onTrustedWifi}"
                        )
                        trySend(retry)
                    }
                    retryRunnables += r
                    mainHandler.postDelayed(r, delay)
                }
            } else if (snap.ssid != null) {
                clearRetries()
            }
        }

        fun newCallback(): ConnectivityManager.NetworkCallback {
            // API 31+: FLAG_INCLUDE_LOCATION_INFO is required to get SSID in capabilities
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onAvailable(network: Network) = emitCurrent()
                    override fun onLost(network: Network) = emitCurrent()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) = emitCurrent()

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) = emitCurrent()
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = emitCurrent()
                    override fun onLost(network: Network) = emitCurrent()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) = emitCurrent()

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) = emitCurrent()
                }
            }
        }

        // Separate callback instances — Android forbids registering the same instance twice
        val wifiCallback = newCallback()
        val defaultCallback = newCallback()

        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)
        connectivityManager.registerDefaultNetworkCallback(defaultCallback)

        // Wi‑Fi stack SSID updates that ConnectivityManager sometimes misses
        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                emitCurrent()
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(wifiReceiver, filter)
        }

        emitCurrent()

        awaitClose {
            clearRetries()
            runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
            runCatching { connectivityManager.unregisterNetworkCallback(defaultCallback) }
            runCatching { appContext.unregisterReceiver(wifiReceiver) }
        }
    }.distinctUntilChanged()

    fun hasSsidPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            return fine || nearby
        }
        return fine
    }

    companion object {
        private const val TAG = "WifiConnectivityMonitor"

        fun normalizeSsid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            if (raw == WifiManager.UNKNOWN_SSID || raw.equals("<unknown ssid>", ignoreCase = true)) {
                return null
            }
            return ConfigRepository.normalizeSsid(raw)
        }
    }
}
