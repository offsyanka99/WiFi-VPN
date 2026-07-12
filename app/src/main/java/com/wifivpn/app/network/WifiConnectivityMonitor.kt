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
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.wifivpn.app.data.ConfigRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Observes Wi‑Fi connectivity and current SSID (when permissions allow).
 *
 * On Android 12+ (API 31), [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO]
 * is required to obtain SSID from network capabilities. Without it, SSID stays unknown
 * after network switches and trusted-Wi‑Fi detection fails.
 *
 * When the screen is locked, the platform often redacts SSID ("unknown ssid") even if
 * Wi‑Fi stays associated. We keep a last-known SSID **only for the same association key**
 * (networkId / BSSID) while supplicant is still COMPLETED — never after disconnect or a
 * network change.
 *
 * Known networks from callbacks are preferred over deprecated [ConnectivityManager.getAllNetworks].
 */
class WifiConnectivityMonitor(private val context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Networks currently reported available by registered callbacks. */
    private val knownNetworks: MutableSet<Network> =
        ConcurrentHashMap.newKeySet()

    /** Last readable SSID for [cachedAssociationKey]; only used while that association is live. */
    @Volatile
    private var cachedSsid: String? = null

    /** Association key (networkId or BSSID) that [cachedSsid] belongs to. */
    @Volatile
    private var cachedAssociationKey: String? = null

    /** Latest trusted SSID list for snapshots inside [wifiStatusFlow]. */
    private val trustedSsidsRef = AtomicReference<Set<String>>(emptySet())

    fun setTrustedSsids(ssids: Set<String>) {
        trustedSsidsRef.set(ssids)
    }

    fun getTrustedSsids(): Set<String> = trustedSsidsRef.get()

    data class WifiSnapshot(
        /** Any Wi‑Fi transport is up (associated / internet path). */
        val wifiConnected: Boolean,
        /** Current SSID if readable; null if offline or permission/location blocks it. */
        val ssid: String?,
        /** True only when connected to a network whose SSID is in [trustedSsids]. */
        val onTrustedWifi: Boolean,
        /** Cellular transport currently available (may coexist with Wi‑Fi). */
        val cellularConnected: Boolean = false,
        /**
         * Active network transports for diagnostics, e.g. `WIFI`, `CELLULAR`, `VPN`.
         * Sorted, comma-separated; empty when nothing is up.
         */
        val transports: String = "",
        /**
         * Wi‑Fi associated but live SSID unreadable while permission is granted
         * (typical when the screen is locked / platform redacts SSID).
         */
        val ssidRedacted: Boolean = false,
        /** Resolved SSID came from last-known cache, not a live system read. */
        val ssidFromCache: Boolean = false,
        /** [PowerManager.isInteractive] — false when screen is off / non-interactive. */
        val screenInteractive: Boolean = true,
        /** Trusted list match: `exact`, `none`, `unknown` (no SSID), or `n/a` (offline). */
        val trustedMatch: String = "n/a",
        /** Whether location / nearby Wi‑Fi permission allows reading SSID. */
        val hasSsidPermission: Boolean = false
    ) {
        /**
         * Equality for VPN policy decisions (ignores screen-only diagnostic fields).
         */
        fun samePolicyAs(other: WifiSnapshot): Boolean =
            wifiConnected == other.wifiConnected &&
                ssid == other.ssid &&
                onTrustedWifi == other.onTrustedWifi &&
                cellularConnected == other.cellularConnected &&
                transports == other.transports
    }

    fun isWifiConnected(): Boolean {
        // Prefer WifiManager association — avoids scanning network lists when possible
        if (isWifiManagerAssociated()) return true
        for (network in candidateNetworks()) {
            if (isWifiNetwork(network)) return true
        }
        return false
    }

    /**
     * Prefer callback-tracked networks; fall back to platform enumeration only if empty.
     */
    private fun candidateNetworks(): List<Network> {
        if (knownNetworks.isNotEmpty()) {
            return knownNetworks.toList()
        }
        val active = connectivityManager.activeNetwork
        if (active != null) return listOf(active)
        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.toList()
    }

    private fun isWifiNetwork(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * True only when the Wi‑Fi stack reports a completed association.
     * `networkId != -1` alone is not enough — it can linger after disconnect.
     */
    @Suppress("DEPRECATION")
    private fun isWifiManagerAssociated(): Boolean {
        return try {
            if (!wifiManager.isWifiEnabled) return false
            val info = wifiManager.connectionInfo ?: return false
            if (info.networkId == -1) return false
            info.supplicantState == android.net.wifi.SupplicantState.COMPLETED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Best-effort current SSID from several sources (needed when VPN is the default network).
     * Falls back to the last known SSID only when still fully associated to the **same**
     * network (matching association key). Never reuses a name across disconnects or roams.
     */
    fun currentSsid(): String? {
        if (!hasSsidPermission()) {
            Log.w(TAG, "No permission to read SSID")
            return null
        }

        val associationKey = wifiAssociationKey()
        val live = readLiveSsid()

        if (live != null) {
            if (associationKey != null) {
                cachedSsid = live
                cachedAssociationKey = associationKey
            } else {
                // Readable SSID but no stable key — do not keep a sticky cache entry
                clearSsidCache()
            }
            return live
        }

        // Live SSID redacted (e.g. screen locked). Reuse only with matching association key
        // and an actual completed association — never when key is missing (that hid disconnects).
        val cached = cachedSsid
        val cachedKey = cachedAssociationKey
        if (cached == null || cachedKey == null) {
            return null
        }
        if (!isWifiManagerAssociated()) {
            Log.i(TAG, "SSID redacted and not associated; drop cache")
            clearSsidCache()
            return null
        }
        if (associationKey == null) {
            // Cannot prove we are still on the same network — fail closed (VPN may turn on)
            Log.i(TAG, "SSID redacted and association key unavailable; not using cache")
            return null
        }
        if (associationKey != cachedKey) {
            Log.i(
                TAG,
                "Association changed ($cachedKey → $associationKey) with SSID redacted; drop cache"
            )
            clearSsidCache()
            return null
        }
        Log.i(
            TAG,
            "SSID redacted; using cached ssid=$cached for association=$associationKey"
        )
        return cached
    }

    private fun readLiveSsid(): String? {
        // 1) Active network first (cheap, often correct under VPN default route too)
        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    ssidFromCapabilities(caps)?.let { return it }
                }
            }
        }

        // 2) Known / candidate networks with Wi‑Fi transportInfo
        for (network in candidateNetworks()) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            ssidFromCapabilities(caps)?.let { return it }
        }

        // 3) Legacy WifiManager (connectionInfo is deprecated but still needed on some OEMs)
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

    /**
     * Stable key for the current Wi‑Fi association. Only returned when supplicant is COMPLETED
     * so a stale post-disconnect networkId cannot keep the trusted-SSID cache alive.
     */
    @Suppress("DEPRECATION")
    private fun wifiAssociationKey(): String? {
        if (!isWifiManagerAssociated()) return null
        // Prefer WifiManager first — still populated when capabilities redact transportInfo
        associationKeyFromWifiInfo(runCatching { wifiManager.connectionInfo }.getOrNull())
            ?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (network in candidateNetworks()) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val info = caps.transportInfo as? WifiInfo
                associationKeyFromWifiInfo(info)?.let { return it }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun associationKeyFromWifiInfo(info: WifiInfo?): String? {
        if (info == null) return null
        return try {
            if (info.networkId != -1) {
                return "nid:${info.networkId}"
            }
            val bssid = info.bssid
            if (!bssid.isNullOrBlank() &&
                !bssid.equals("02:00:00:00:00:00", ignoreCase = true)
            ) {
                return "bssid:${bssid.lowercase()}"
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun clearSsidCache() {
        cachedSsid = null
        cachedAssociationKey = null
    }

    fun snapshot(trustedSsids: Set<String>): WifiSnapshot {
        val transports = activeTransports()
        val cellular = transports.contains(TRANSPORT_CELLULAR)
        val transportLabel = transports.sorted().joinToString(",")
        val connected = isWifiConnected()
        val screenInteractive = isScreenInteractive()
        val hasPerm = hasSsidPermission()
        if (!connected) {
            clearSsidCache()
            return WifiSnapshot(
                wifiConnected = false,
                ssid = null,
                onTrustedWifi = false,
                cellularConnected = cellular,
                transports = transportLabel,
                ssidRedacted = false,
                ssidFromCache = false,
                screenInteractive = screenInteractive,
                trustedMatch = "n/a",
                hasSsidPermission = hasPerm
            )
        }
        val live = if (hasPerm) readLiveSsid() else null
        val ssid = currentSsid()
        val ssidRedacted = live == null && hasPerm
        val ssidFromCache = live == null && ssid != null
        val onTrusted = ssid != null &&
            trustedSsids.any { it.equals(ssid, ignoreCase = true) }
        val trustedMatch = when {
            ssid == null -> "unknown"
            onTrusted -> "exact"
            else -> "none"
        }
        return WifiSnapshot(
            wifiConnected = true,
            ssid = ssid,
            onTrustedWifi = onTrusted,
            cellularConnected = cellular,
            transports = transportLabel,
            ssidRedacted = ssidRedacted,
            ssidFromCache = ssidFromCache,
            screenInteractive = screenInteractive,
            trustedMatch = trustedMatch,
            hasSsidPermission = hasPerm
        )
    }

    fun isScreenInteractive(): Boolean {
        val pm = appContext.getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }

    /**
     * Active [NetworkCapabilities] transports across all networks (for diagnostics).
     * Values are short labels: WIFI, CELLULAR, VPN, ETHERNET, BLUETOOTH, OTHER.
     */
    fun activeTransports(): Set<String> {
        val result = linkedSetOf<String>()
        for (network in candidateNetworks()) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                result += TRANSPORT_WIFI
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                result += TRANSPORT_CELLULAR
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                result += TRANSPORT_VPN
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                result += TRANSPORT_ETHERNET
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                result += TRANSPORT_BLUETOOTH
            }
        }
        // Association can report Wi‑Fi before callbacks refresh knownNetworks
        if (TRANSPORT_WIFI !in result && isWifiManagerAssociated()) {
            result += TRANSPORT_WIFI
        }
        return result
    }

    fun isCellularConnected(): Boolean =
        activeTransports().contains(TRANSPORT_CELLULAR)

    /**
     * Emits [WifiSnapshot] on network / Wi‑Fi changes.
     *
     * Uses the trusted SSID set from [setTrustedSsids]. Emissions are debounced and
     * filtered to policy-relevant changes (see [WifiSnapshot.samePolicyAs]).
     * When Wi‑Fi is up but SSID is still unknown, retries a few times after a roam.
     */
    @OptIn(FlowPreview::class)
    fun wifiStatusFlow(): Flow<WifiSnapshot> = callbackFlow {
        val retryRunnables = mutableListOf<Runnable>()
        val debounceEmit = object : Runnable {
            override fun run() {
                val snap = snapshot(trustedSsidsRef.get())
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "WiFi snap connected=${snap.wifiConnected} ssid=${snap.ssid} " +
                            "trusted=${snap.onTrustedWifi} transports=${snap.transports}"
                    )
                }
                trySend(snap)

                if (snap.wifiConnected && snap.ssid == null && hasSsidPermission()) {
                    clearRetries()
                    val delaysMs = longArrayOf(300L, 800L, 1600L, 3200L)
                    delaysMs.forEach { delayMs ->
                        val r = Runnable {
                            val retry = snapshot(trustedSsidsRef.get())
                            trySend(retry)
                        }
                        retryRunnables += r
                        mainHandler.postDelayed(r, delayMs)
                    }
                } else if (snap.ssid != null) {
                    clearRetries()
                }
            }

            fun clearRetries() {
                retryRunnables.forEach { mainHandler.removeCallbacks(it) }
                retryRunnables.clear()
            }
        }

        fun scheduleEmit() {
            mainHandler.removeCallbacks(debounceEmit)
            mainHandler.postDelayed(debounceEmit, EMIT_DEBOUNCE_MS)
        }

        fun clearRetries() {
            retryRunnables.forEach { mainHandler.removeCallbacks(it) }
            retryRunnables.clear()
        }

        fun newCallback(): ConnectivityManager.NetworkCallback {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onAvailable(network: Network) {
                        knownNetworks.add(network)
                        scheduleEmit()
                    }

                    override fun onLost(network: Network) {
                        knownNetworks.remove(network)
                        scheduleEmit()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        knownNetworks.add(network)
                        scheduleEmit()
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        knownNetworks.add(network)
                        scheduleEmit()
                    }

                    override fun onLost(network: Network) {
                        knownNetworks.remove(network)
                        scheduleEmit()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        knownNetworks.add(network)
                        scheduleEmit()
                    }
                }
            }
        }

        // Wi‑Fi + default network is enough; no separate cellular callback.
        val wifiCallback = newCallback()
        val defaultCallback = newCallback()
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)
        connectivityManager.registerDefaultNetworkCallback(defaultCallback)

        // NETWORK_STATE covers association; avoid deprecated SUPPLICANT_STATE_CHANGED_ACTION
        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scheduleEmit()
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(wifiReceiver, filter)
        }

        // Immediate first sample (no debounce)
        debounceEmit.run()

        awaitClose {
            mainHandler.removeCallbacks(debounceEmit)
            clearRetries()
            knownNetworks.clear()
            runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
            runCatching { connectivityManager.unregisterNetworkCallback(defaultCallback) }
            runCatching { appContext.unregisterReceiver(wifiReceiver) }
        }
    }
        .debounce(FLOW_DEBOUNCE_MS)
        .distinctUntilChanged { a, b -> a.samePolicyAs(b) }

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
        /** Coalesce bursty ConnectivityManager callbacks on the main handler. */
        private const val EMIT_DEBOUNCE_MS = 250L
        /** Extra Flow-level debounce before policy consumers run. */
        private const val FLOW_DEBOUNCE_MS = 150L

        const val TRANSPORT_WIFI = "WIFI"
        const val TRANSPORT_CELLULAR = "CELLULAR"
        const val TRANSPORT_VPN = "VPN"
        const val TRANSPORT_ETHERNET = "ETHERNET"
        const val TRANSPORT_BLUETOOTH = "BLUETOOTH"

        fun normalizeSsid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            if (raw == WifiManager.UNKNOWN_SSID || raw.equals("<unknown ssid>", ignoreCase = true)) {
                return null
            }
            return ConfigRepository.normalizeSsid(raw)
        }
    }
}
