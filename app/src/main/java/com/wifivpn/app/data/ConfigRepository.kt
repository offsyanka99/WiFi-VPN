package com.wifivpn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wifi_vpn_prefs")

class ConfigRepository(context: Context) {

    private val context = context.applicationContext
    private val secureConfig = SecureConfigStore(this.context)
    private val migrateMutex = Mutex()

    @Volatile
    private var migratedConfig = false

    private val keys = object {
        // Legacy plain keys — migrated once into SecureConfigStore then removed
        val wgConfig = stringPreferencesKey("wg_config")
        val wgConfigFileName = stringPreferencesKey("wg_config_file_name")
        val monitoringEnabled = booleanPreferencesKey("monitoring_enabled")
        val autoStartEnabled = booleanPreferencesKey("auto_start_enabled")
        val excludedApps = stringSetPreferencesKey("excluded_apps")
        val trustedWifiSsids = stringSetPreferencesKey("trusted_wifi_ssids")
        /**
         * Saved Wi‑Fi association keys → SSID for trusted networks.
         * Entries: `"nid:3|U6"` or `"bssid:aa:bb:cc:dd:ee:ff|U6"`.
         * Used after reboot when the platform redacts SSID in the background.
         */
        val trustedWifiAssociations = stringSetPreferencesKey("trusted_wifi_associations")
        val vpnRetryAttempts = intPreferencesKey("vpn_retry_attempts")
        val vpnRetryDelaySeconds = intPreferencesKey("vpn_retry_delay_seconds")
        val diagnosticLoggingEnabled = booleanPreferencesKey("diagnostic_logging_enabled")

        /** User acknowledged what the diagnostic log contains before sharing it. */
        val diagnosticShareConsented = booleanPreferencesKey("diagnostic_share_consented")

        /** User chose "Not now" for "Allow all the time" location from the main screen. */
        val backgroundLocationDeclined = booleanPreferencesKey("background_location_declined")
    }

    // Populated by [migrateSecureConfigIfNeeded]; decrypting here would block Application.onCreate.
    private val _wireGuardConfig = MutableStateFlow("")
    private val _wireGuardConfigFileName = MutableStateFlow("")
    private val _configState = MutableStateFlow(SecureConfigStore.ConfigState.EMPTY)

    val wireGuardConfig: Flow<String> = _wireGuardConfig.asStateFlow()

    val wireGuardConfigFileName: Flow<String> = _wireGuardConfigFileName.asStateFlow()

    /** Distinguishes "no config imported" from "config present but undecryptable". */
    val configState: Flow<SecureConfigStore.ConfigState> = _configState.asStateFlow()

    val monitoringEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keys.monitoringEnabled] ?: false
    }

    val autoStartEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keys.autoStartEnabled] ?: false
    }

    val excludedApps: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[keys.excludedApps].orEmpty()
    }

    val trustedWifiSsids: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[keys.trustedWifiSsids].orEmpty()
    }

    val vpnRetryAttempts: Flow<Int> = context.dataStore.data.map { prefs ->
        clampRetryAttempts(prefs[keys.vpnRetryAttempts] ?: DEFAULT_VPN_RETRY_ATTEMPTS)
    }

    val vpnRetryDelaySeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        clampRetryDelaySeconds(prefs[keys.vpnRetryDelaySeconds] ?: DEFAULT_VPN_RETRY_DELAY_SECONDS)
    }

    /** Opt-in diagnostic file logging (off by default). */
    val diagnosticLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keys.diagnosticLoggingEnabled] ?: false
    }

    /**
     * One-time move of WireGuard config from plain DataStore into encrypted storage.
     * Safe to call multiple times. Must run off the main thread — it opens the Keystore
     * and, on upgrades, the legacy Tink-backed store.
     */
    suspend fun migrateSecureConfigIfNeeded() {
        if (migratedConfig) return
        migrateMutex.withLock {
            if (migratedConfig) return
            withContext(Dispatchers.IO) {
                secureConfig.migrateLegacyIfNeeded()
                val prefs = context.dataStore.data.first()
                val legacyConfig = prefs[keys.wgConfig].orEmpty()
                val legacyName = prefs[keys.wgConfigFileName].orEmpty()
                if (legacyConfig.isNotBlank() && secureConfig.isEmpty()) {
                    secureConfig.set(legacyConfig.trim(), legacyName)
                }
                if (legacyConfig.isNotBlank() || legacyName.isNotBlank()) {
                    context.dataStore.edit { p ->
                        p.remove(keys.wgConfig)
                        p.remove(keys.wgConfigFileName)
                    }
                }
                // Refresh from secure store (covers process restarts)
                publishSecureConfig()
            }
            migratedConfig = true
        }
    }

    private fun publishSecureConfig() {
        _configState.value = secureConfig.state()
        _wireGuardConfig.value = secureConfig.config
        _wireGuardConfigFileName.value = secureConfig.fileName
    }

    /** Sync read for tile / quick checks after migration. */
    fun getWireGuardConfigSync(): String = secureConfig.config

    fun getWireGuardConfigFileNameSync(): String = secureConfig.fileName

    fun hasWireGuardConfigSync(): Boolean = secureConfig.config.isNotBlank()

    /** Last published state. Does not touch the Keystore, so it is safe on the main thread. */
    fun lastConfigState(): SecureConfigStore.ConfigState = _configState.value

    suspend fun getWireGuardConfig(): String {
        migrateSecureConfigIfNeeded()
        return withContext(Dispatchers.IO) {
            secureConfig.config.also { _configState.value = secureConfig.state() }
        }
    }

    suspend fun getWireGuardConfigFileName(): String {
        migrateSecureConfigIfNeeded()
        return withContext(Dispatchers.IO) { secureConfig.fileName }
    }

    suspend fun setWireGuardConfig(config: String, fileName: String) {
        migrateSecureConfigIfNeeded()
        withContext(Dispatchers.IO) {
            secureConfig.set(config.trim(), fileName)
            publishSecureConfig()
        }
    }

    suspend fun clearWireGuardConfig() {
        migrateSecureConfigIfNeeded()
        withContext(Dispatchers.IO) {
            secureConfig.clear()
            publishSecureConfig()
            // Ensure legacy keys are gone
            context.dataStore.edit { p ->
                p.remove(keys.wgConfig)
                p.remove(keys.wgConfigFileName)
            }
        }
    }

    suspend fun getExcludedApps(): Set<String> {
        return context.dataStore.data.first()[keys.excludedApps].orEmpty()
    }

    suspend fun setExcludedApps(packages: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[keys.excludedApps] = packages
        }
    }

    suspend fun getTrustedWifiSsids(): Set<String> {
        return context.dataStore.data.first()[keys.trustedWifiSsids].orEmpty()
    }

    suspend fun setTrustedWifiSsids(ssids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[keys.trustedWifiSsids] = ssids
            // Drop association memory for SSIDs that are no longer trusted.
            val kept = parseAssociationEntries(prefs[keys.trustedWifiAssociations].orEmpty())
                .filter { (_, remembered) ->
                    ssids.any { it.equals(remembered, ignoreCase = true) }
                }
            prefs[keys.trustedWifiAssociations] = encodeAssociationEntries(kept)
        }
    }

    suspend fun addTrustedWifiSsid(ssid: String): Boolean {
        val normalized = normalizeSsid(ssid) ?: return false
        val current = getTrustedWifiSsids()
        if (current.any { it.equals(normalized, ignoreCase = true) }) return false
        setTrustedWifiSsids(current + normalized)
        return true
    }

    suspend fun removeTrustedWifiSsid(ssid: String) {
        val current = getTrustedWifiSsids()
        setTrustedWifiSsids(current.filterNot { it.equals(ssid, ignoreCase = true) }.toSet())
    }

    /**
     * Association key (e.g. `nid:3`) → SSID for networks we previously saw as trusted.
     * Survives reboot so policy can recognize home Wi‑Fi when SSID is still redacted.
     */
    suspend fun getTrustedWifiAssociations(): Map<String, String> {
        val raw = context.dataStore.data.first()[keys.trustedWifiAssociations].orEmpty()
        return parseAssociationEntries(raw)
    }

    /**
     * Remember that [assocKey] belongs to trusted [ssid]. Overwrites any previous mapping
     * for the same key. Ignores blank keys / SSIDs.
     */
    suspend fun rememberTrustedWifiAssociation(assocKey: String, ssid: String) {
        rememberTrustedWifiAssociations(listOf(assocKey), ssid)
    }

    /**
     * Remember every identity key ([bssid:…], [nid:…]) the platform exposed for a trusted
     * network, in one write. Storing both means a renumbered `networkId` cannot alias a
     * different SSID as long as the BSSID still matches.
     */
    suspend fun rememberTrustedWifiAssociations(assocKeys: Collection<String>, ssid: String) {
        val name = normalizeSsid(ssid) ?: return
        val cleanKeys = assocKeys.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanKeys.isEmpty()) return
        context.dataStore.edit { prefs ->
            val map = parseAssociationEntries(prefs[keys.trustedWifiAssociations].orEmpty())
                .toMutableMap()
            for (key in cleanKeys) {
                map[key] = name
            }
            prefs[keys.trustedWifiAssociations] = encodeAssociationEntries(map)
        }
    }

    suspend fun isMonitoringEnabled(): Boolean {
        return context.dataStore.data.first()[keys.monitoringEnabled] ?: false
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keys.monitoringEnabled] = enabled
        }
    }

    suspend fun isAutoStartEnabled(): Boolean {
        return context.dataStore.data.first()[keys.autoStartEnabled] ?: false
    }

    suspend fun setAutoStartEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keys.autoStartEnabled] = enabled
        }
    }

    suspend fun getVpnRetryAttempts(): Int {
        val raw = context.dataStore.data.first()[keys.vpnRetryAttempts]
            ?: DEFAULT_VPN_RETRY_ATTEMPTS
        return clampRetryAttempts(raw)
    }

    suspend fun setVpnRetryAttempts(attempts: Int) {
        context.dataStore.edit { prefs ->
            prefs[keys.vpnRetryAttempts] = clampRetryAttempts(attempts)
        }
    }

    suspend fun getVpnRetryDelaySeconds(): Int {
        val raw = context.dataStore.data.first()[keys.vpnRetryDelaySeconds]
            ?: DEFAULT_VPN_RETRY_DELAY_SECONDS
        return clampRetryDelaySeconds(raw)
    }

    suspend fun setVpnRetryDelaySeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[keys.vpnRetryDelaySeconds] = clampRetryDelaySeconds(seconds)
        }
    }

    suspend fun isDiagnosticLoggingEnabled(): Boolean {
        return context.dataStore.data.first()[keys.diagnosticLoggingEnabled] ?: false
    }

    suspend fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keys.diagnosticLoggingEnabled] = enabled
        }
    }

    suspend fun isDiagnosticShareConsented(): Boolean {
        return context.dataStore.data.first()[keys.diagnosticShareConsented] ?: false
    }

    suspend fun setDiagnosticShareConsented(consented: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keys.diagnosticShareConsented] = consented
        }
    }

    suspend fun isBackgroundLocationDeclined(): Boolean {
        return context.dataStore.data.first()[keys.backgroundLocationDeclined] ?: false
    }

    suspend fun setBackgroundLocationDeclined(declined: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keys.backgroundLocationDeclined] = declined
        }
    }

    /** Ready to run monitor in background (config + at least one trusted SSID). */
    suspend fun canStartMonitoring(): Boolean {
        return getWireGuardConfig().isNotBlank() && getTrustedWifiSsids().isNotEmpty()
    }

    /** Sync after migration — used by tile without blocking the main thread on DataStore. */
    fun canStartMonitoringSync(trustedCached: Set<String>? = null): Boolean {
        if (secureConfig.config.isBlank()) return false
        val trusted = trustedCached ?: return false
        return trusted.isNotEmpty()
    }

    companion object {
        const val DEFAULT_VPN_RETRY_ATTEMPTS = 10
        const val DEFAULT_VPN_RETRY_DELAY_SECONDS = 30
        const val MIN_VPN_RETRY_ATTEMPTS = 1
        const val MAX_VPN_RETRY_ATTEMPTS = 300
        const val MIN_VPN_RETRY_DELAY_SECONDS = 5
        const val MAX_VPN_RETRY_DELAY_SECONDS = 120
        /** Configuration − / + step for wait between attempts. */
        const val VPN_RETRY_DELAY_STEP_SECONDS = 5

        fun clampRetryAttempts(value: Int): Int =
            max(MIN_VPN_RETRY_ATTEMPTS, min(MAX_VPN_RETRY_ATTEMPTS, value))

        fun clampRetryDelaySeconds(value: Int): Int =
            max(MIN_VPN_RETRY_DELAY_SECONDS, min(MAX_VPN_RETRY_DELAY_SECONDS, value))

        fun normalizeSsid(raw: String): String? {
            var s = raw.trim()
            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                s = s.substring(1, s.length - 1).trim()
            }
            return s.ifBlank { null }
        }

        /** Parse `"key|ssid"` association entries. */
        fun parseAssociationEntries(raw: Set<String>): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (entry in raw) {
                val sep = entry.indexOf('|')
                if (sep <= 0 || sep >= entry.length - 1) continue
                val key = entry.substring(0, sep).trim()
                val ssid = normalizeSsid(entry.substring(sep + 1)) ?: continue
                if (key.isNotEmpty()) out[key] = ssid
            }
            return out
        }

        fun encodeAssociationEntries(map: Map<String, String>): Set<String> {
            if (map.isEmpty()) return emptySet()
            return map.mapNotNull { (key, ssid) ->
                val k = key.trim()
                val s = normalizeSsid(ssid) ?: return@mapNotNull null
                if (k.isEmpty()) null else "$k|$s"
            }.toSet()
        }
    }
}
