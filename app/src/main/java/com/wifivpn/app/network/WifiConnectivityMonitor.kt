package com.wifivpn.app.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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
 * is required to obtain an **unredacted** SSID. [ConnectivityManager.getNetworkCapabilities]
 * almost always redacts [WifiInfo] even when location permission is granted — callers must
 * use the [NetworkCapabilities] delivered to [NetworkCallback.onCapabilitiesChanged] (or
 * the SSID we cache from those callbacks).
 *
 * When the screen is locked or the process has no "active" location client after boot,
 * the platform may redact SSID even with FINE location + location FGS. Mitigations:
 *  - [startSsidLocationBridge]: passive/network location updates so SSID APIs un-redact
 *  - [setTrustedAssociations]: persist networkId/BSSID → SSID for trusted nets across reboot
 *
 * Known networks from callbacks are preferred over deprecated [ConnectivityManager.getAllNetworks].
 */
class WifiConnectivityMonitor(private val context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Networks currently reported available by registered callbacks. */
    private val knownNetworks: MutableSet<Network> =
        ConcurrentHashMap.newKeySet()

    /**
     * Unredacted SSID last seen in a [NetworkCallback] registered with
     * [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO].
     * This is the reliable source on Android 12+; direct [getNetworkCapabilities] is redacted.
     */
    @Volatile
    private var callbackSsid: String? = null

    /** Association key that [callbackSsid] belongs to. */
    @Volatile
    private var callbackAssociationKey: String? = null

    /** Last readable SSID for [cachedAssociationKey]; only used while that association is live. */
    @Volatile
    private var cachedSsid: String? = null

    /** Association key (networkId or BSSID) that [cachedSsid] belongs to. */
    @Volatile
    private var cachedAssociationKey: String? = null

    /**
     * Association key for which we already logged “using cached SSID” at DEBUG.
     * Avoids logcat spam when capabilities fire often with a redacted SSID overnight.
     */
    @Volatile
    private var loggedCacheForAssociation: String? = null

    /** Latest trusted SSID list for snapshots inside [wifiStatusFlow]. */
    private val trustedSsidsRef = AtomicReference<Set<String>>(emptySet())

    /**
     * Persisted association key → SSID for trusted networks (survives reboot).
     * Used when live SSID is redacted but Wi‑Fi is associated to a known home network.
     */
    private val trustedAssociationsRef =
        AtomicReference<Map<String, String>>(emptyMap())

    @Volatile
    private var locationBridgeActive: Boolean = false

    /**
     * Lightweight location listener: we do not use coordinates — only keep location
     * "active" so WifiInfo / transportInfo can expose SSID under a location FGS.
     */
    private val ssidLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // No-op: presence of an active request is what unblocks SSID reads.
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun setTrustedSsids(ssids: Set<String>) {
        trustedSsidsRef.set(ssids)
    }

    fun getTrustedSsids(): Set<String> = trustedSsidsRef.get()

    fun setTrustedAssociations(map: Map<String, String>) {
        trustedAssociationsRef.set(map)
    }

    fun getTrustedAssociations(): Map<String, String> = trustedAssociationsRef.get()

    /** Current Wi‑Fi association key (`nid:…` / `bssid:…`) if fully associated. */
    fun currentAssociationKey(): String? = wifiAssociationKey()

    /**
     * Start low-rate location updates so platform SSID APIs treat us as location-active.
     * Required after BOOT_COMPLETED: without this, SSID stays redacted until the UI opens.
     */
    fun startSsidLocationBridge() {
        if (locationBridgeActive) return
        if (!hasSsidPermission()) {
            Log.w(TAG, "SSID location bridge skipped — no location permission")
            return
        }
        val lm = locationManager ?: return
        if (!isLocationEnabled(lm)) {
            Log.w(TAG, "SSID location bridge: device location is off — SSID may stay redacted")
        }
        var registered = false
        try {
            // NETWORK: uses cell/Wi‑Fi; enough to mark location active for SSID.
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_BRIDGE_INTERVAL_MS,
                    0f,
                    ssidLocationListener,
                    Looper.getMainLooper()
                )
                registered = true
            }
            // PASSIVE: no extra power; receives others' updates.
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    LOCATION_BRIDGE_INTERVAL_MS,
                    0f,
                    ssidLocationListener,
                    Looper.getMainLooper()
                )
                registered = true
            }
            // Last resort on devices with only GPS (rare for SSID unlock, but try).
            if (!registered && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_BRIDGE_INTERVAL_MS,
                    0f,
                    ssidLocationListener,
                    Looper.getMainLooper()
                )
                registered = true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SSID location bridge denied", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "SSID location bridge failed: ${e.message}")
            return
        }
        locationBridgeActive = registered
        if (registered) {
            Log.i(TAG, "SSID location bridge started (unlocks SSID under location FGS)")
        } else {
            Log.w(TAG, "SSID location bridge: no location providers enabled")
        }
    }

    fun stopSsidLocationBridge() {
        if (!locationBridgeActive) return
        try {
            locationManager?.removeUpdates(ssidLocationListener)
        } catch (_: Exception) {
        }
        locationBridgeActive = false
        Log.i(TAG, "SSID location bridge stopped")
    }

    private fun isLocationEnabled(lm: LocationManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

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
         *
         * [transports] is intentionally ignored: bringing the tunnel up adds a VPN
         * transport and must not re-trigger policy (that cancels in-flight connect /
         * handshake wait via collectLatest).
         */
        fun samePolicyAs(other: WifiSnapshot): Boolean =
            wifiConnected == other.wifiConnected &&
                ssid == other.ssid &&
                onTrustedWifi == other.onTrustedWifi &&
                cellularConnected == other.cellularConnected
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
     * True when the Wi‑Fi stack reports a completed association with a usable network id,
     * **or** ConnectivityManager already shows a Wi‑Fi transport (after boot, networkId is
     * often -1 while SSID is redacted, but the radio is fully associated).
     */
    @Suppress("DEPRECATION")
    private fun isWifiManagerAssociated(): Boolean {
        return try {
            if (!wifiManager.isWifiEnabled) return false
            val info = wifiManager.connectionInfo
            if (info != null &&
                info.networkId != -1 &&
                info.supplicantState == android.net.wifi.SupplicantState.COMPLETED
            ) {
                return true
            }
            // Post-boot / location-redacted: nid may be -1 while transport is still WIFI.
            for (network in candidateNetworks()) {
                if (isWifiNetwork(network)) return true
            }
            false
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
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "SSID redacted and not associated; drop cache")
            }
            clearSsidCache()
            return null
        }
        if (associationKey == null) {
            // Cannot prove we are still on the same network — fail closed (VPN may turn on)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "SSID redacted and association key unavailable; not using cache")
            }
            return null
        }
        if (associationKey != cachedKey) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "Association changed ($cachedKey → $associationKey) with SSID redacted; drop cache"
                )
            }
            clearSsidCache()
            return null
        }
        // Once per association — capabilities (RSSI etc.) can fire often while screen is off
        if (loggedCacheForAssociation != associationKey) {
            loggedCacheForAssociation = associationKey
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "SSID redacted; using cached ssid=$cached for association=$associationKey"
                )
            }
        }
        return cached
    }

    private fun readLiveSsid(): String? {
        // 1) Unredacted SSID from FLAG_INCLUDE_LOCATION_INFO NetworkCallbacks (Android 12+).
        //    getNetworkCapabilities() returns redacted WifiInfo and must not be trusted alone.
        callbackSsidForCurrentAssociation()?.let { return it }

        // 2) Active / candidate networks via getNetworkCapabilities (often redacted on API 31+;
        //    still tried for older platforms and rare cases where transportInfo is filled).
        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    ssidFromCapabilities(caps)?.let { return it }
                }
            }
        }
        for (network in candidateNetworks()) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            ssidFromCapabilities(caps)?.let { return it }
        }

        // 3) Legacy WifiManager (may work in foreground; often redacted in background)
        return ssidFromWifiManager()
    }

    /**
     * Returns [callbackSsid] only while still associated to the same network that produced it.
     */
    private fun callbackSsidForCurrentAssociation(): String? {
        val ssid = callbackSsid ?: return null
        if (!isWifiManagerAssociated() && !isWifiConnected()) {
            clearCallbackSsid()
            return null
        }
        val key = wifiAssociationKey()
        val callbackKey = callbackAssociationKey
        if (key != null && callbackKey != null && key != callbackKey) {
            clearCallbackSsid()
            return null
        }
        return ssid
    }

    /**
     * Ingest capabilities from a location-info NetworkCallback. Stores unredacted SSID when present.
     */
    private fun ingestCallbackCapabilities(network: Network, caps: NetworkCapabilities) {
        knownNetworks.add(network)
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val ssid = ssidFromCapabilities(caps) ?: return
        val key = associationKeyFromWifiInfo(caps.transportInfo as? WifiInfo)
            ?: wifiAssociationKey()
        callbackSsid = ssid
        callbackAssociationKey = key
        if (key != null) {
            cachedSsid = ssid
            cachedAssociationKey = key
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Callback SSID=$ssid assoc=$key")
        }
    }

    private fun clearCallbackSsid() {
        callbackSsid = null
        callbackAssociationKey = null
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
     * Stable key for the current Wi‑Fi association (`nid:…` or `bssid:…`).
     * Best-effort after boot when SSID is redacted — networkId may still be present, or
     * only BSSID, or neither (then [resolveFromAssociationMemory] uses sole-SSID fallback).
     */
    @Suppress("DEPRECATION")
    private fun wifiAssociationKey(): String? {
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

    /**
     * When live SSID is redacted, recover a trusted name from persisted association memory.
     *
     * 1. Exact key match (`nid:16` → `U6`) when the platform still exposes networkId/BSSID.
     * 2. **Sole remembered trusted SSID** when the key is also redacted (common after
     *    BOOT_COMPLETED on Pixel) — safe when the user has a single home network in memory.
     *
     * @return pair of (ssid, trustedMatch label) or null
     */
    private fun resolveFromAssociationMemory(
        trustedSsids: Set<String>
    ): Pair<String, String>? {
        val map = trustedAssociationsRef.get()
        if (map.isEmpty() || trustedSsids.isEmpty()) return null

        val assocKey = wifiAssociationKey()
        if (assocKey != null) {
            // Positive key match only. Unknown key (e.g. cafe nid while memory has home)
            // must NOT fall through to sole-SSID — that would fail open (VPN off off-trusted).
            val byKey = map[assocKey]
                ?: map.entries.firstOrNull { it.key.equals(assocKey, ignoreCase = true) }?.value
            val name = byKey?.let { ConfigRepository.normalizeSsid(it) }
            if (name != null && trustedSsids.any { it.equals(name, ignoreCase = true) }) {
                return name to "assoc_memory"
            }
            return null
        }

        // Association key also redacted (common after BOOT_COMPLETED). Only then, if every
        // memory entry points at one trusted SSID, use it — never when a live key is known.
        val trustedRemembered = map.values
            .mapNotNull { ConfigRepository.normalizeSsid(it) }
            .distinctBy { it.lowercase() }
            .filter { rem -> trustedSsids.any { it.equals(rem, ignoreCase = true) } }

        if (trustedRemembered.size == 1) {
            return trustedRemembered.first() to "assoc_memory_sole"
        }

        if (trustedSsids.size == 1) {
            val only = ConfigRepository.normalizeSsid(trustedSsids.first()) ?: return null
            if (map.values.any { it.equals(only, ignoreCase = true) }) {
                return only to "assoc_memory_sole"
            }
        }
        return null
    }

    private fun clearSsidCache() {
        cachedSsid = null
        cachedAssociationKey = null
        loggedCacheForAssociation = null
        clearCallbackSsid()
    }

    /**
     * Signature of [NetworkCapabilities] fields that matter for Wi‑Fi / VPN policy.
     * Ignores pure signal-strength (RSSI) churn so overnight `onCapabilitiesChanged`
     * does not force full SSID snapshots.
     */
    private fun relevantCapsSignature(caps: NetworkCapabilities): String {
        val transports = buildString {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) append('W')
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) append('C')
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) append('V')
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) append('E')
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) append('B')
        }
        val internet =
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) '1' else '0'
        val validated =
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) '1' else '0'
        var ssidPart = "-"
        var assocPart = "-"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            if (info != null) {
                ssidPart = normalizeSsid(info.ssid) ?: "?"
                assocPart = associationKeyFromWifiInfo(info) ?: "-"
            }
        }
        return "$transports|$internet|$validated|$ssidPart|$assocPart"
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
        var ssid = currentSsid()
        var ssidFromCache = live == null && ssid != null
        var trustedMatch: String
        var onTrusted: Boolean

        if (ssid != null) {
            onTrusted = trustedSsids.any { it.equals(ssid, ignoreCase = true) }
            trustedMatch = if (onTrusted) "exact" else "none"
        } else {
            // Live SSID redacted (typical after boot until the app is opened).
            val recovered = resolveFromAssociationMemory(trustedSsids)
            if (recovered != null) {
                ssid = recovered.first
                ssidFromCache = true
                onTrusted = true
                trustedMatch = recovered.second
                val assocKey = wifiAssociationKey()
                if (assocKey != null) {
                    cachedSsid = recovered.first
                    cachedAssociationKey = assocKey
                }
            } else {
                onTrusted = false
                trustedMatch = "unknown"
            }
        }

        val fromMemory =
            trustedMatch == "assoc_memory" || trustedMatch == "assoc_memory_sole"
        val ssidRedacted = live == null && hasPerm && !fromMemory
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
        /** Last policy-relevant caps signature per network — skips RSSI-only updates. */
        val lastCapsSig = ConcurrentHashMap<Network, String>()
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

                // After boot / roam the platform often redacts SSID for many seconds.
                // Keep re-sampling so policy can switch to trusted once the name is readable.
                if (snap.wifiConnected && snap.ssid == null && hasSsidPermission()) {
                    clearRetries()
                    val delaysMs = longArrayOf(
                        500L, 1_500L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L, 45_000L
                    )
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

        fun onCapsChanged(network: Network, caps: NetworkCapabilities) {
            // Prefer SSID from this callback payload (unredacted with FLAG_INCLUDE_LOCATION_INFO).
            ingestCallbackCapabilities(network, caps)
            val sig = relevantCapsSignature(caps)
            // put returns previous value; skip when nothing policy-relevant changed
            if (lastCapsSig.put(network, sig) == sig) return
            scheduleEmit()
        }

        fun onNetworkLost(network: Network) {
            knownNetworks.remove(network)
            lastCapsSig.remove(network)
            if (knownNetworks.none { isWifiNetwork(it) } && !isWifiManagerAssociated()) {
                clearCallbackSsid()
            }
            scheduleEmit()
        }

        fun newCallback(): ConnectivityManager.NetworkCallback {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onAvailable(network: Network) {
                        knownNetworks.add(network)
                        // Pull capabilities through this location-info callback path when possible.
                        connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                            // Still may be redacted here; onCapabilitiesChanged is authoritative.
                            ingestCallbackCapabilities(network, caps)
                        }
                        scheduleEmit()
                    }

                    override fun onLost(network: Network) {
                        onNetworkLost(network)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        onCapsChanged(network, networkCapabilities)
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) {
                        // Link ready often follows association; re-check for late SSID.
                        connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                            onCapsChanged(network, caps)
                        } ?: scheduleEmit()
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        knownNetworks.add(network)
                        connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                            ingestCallbackCapabilities(network, caps)
                        }
                        scheduleEmit()
                    }

                    override fun onLost(network: Network) {
                        onNetworkLost(network)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        onCapsChanged(network, networkCapabilities)
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) {
                        connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                            onCapsChanged(network, caps)
                        } ?: scheduleEmit()
                    }
                }
            }
        }

        // Wi‑Fi + default network is enough; no separate cellular callback.
        // Both use FLAG_INCLUDE_LOCATION_INFO on API 31+ so onCapabilitiesChanged carries SSID.
        val wifiCallback = newCallback()
        val defaultCallback = newCallback()
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        // Handler-bound registration so callbacks run on main looper promptly after boot.
        connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback, mainHandler)
        connectivityManager.registerDefaultNetworkCallback(defaultCallback, mainHandler)

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

        // Unlock SSID APIs under location FGS (especially after BOOT_COMPLETED).
        startSsidLocationBridge()

        // Immediate first sample (no debounce)
        debounceEmit.run()

        awaitClose {
            mainHandler.removeCallbacks(debounceEmit)
            clearRetries()
            knownNetworks.clear()
            lastCapsSig.clear()
            stopSsidLocationBridge()
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
        /** Location request interval — only to keep location "active" for SSID reads. */
        private const val LOCATION_BRIDGE_INTERVAL_MS = 30_000L

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
