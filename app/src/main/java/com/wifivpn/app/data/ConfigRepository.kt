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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wifi_vpn_prefs")

class ConfigRepository(private val context: Context) {

    private val secureConfig = SecureConfigStore(context)
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
        val vpnRetryAttempts = intPreferencesKey("vpn_retry_attempts")
        val vpnRetryDelaySeconds = intPreferencesKey("vpn_retry_delay_seconds")
        val diagnosticLoggingEnabled = booleanPreferencesKey("diagnostic_logging_enabled")
    }

    private val _wireGuardConfig = MutableStateFlow(secureConfig.config)
    private val _wireGuardConfigFileName = MutableStateFlow(secureConfig.fileName)

    val wireGuardConfig: Flow<String> = _wireGuardConfig.asStateFlow()

    val wireGuardConfigFileName: Flow<String> = _wireGuardConfigFileName.asStateFlow()

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
     * Safe to call multiple times.
     */
    suspend fun migrateSecureConfigIfNeeded() {
        if (migratedConfig) return
        migrateMutex.withLock {
            if (migratedConfig) return
            val prefs = context.dataStore.data.first()
            val legacyConfig = prefs[keys.wgConfig].orEmpty()
            val legacyName = prefs[keys.wgConfigFileName].orEmpty()
            if (legacyConfig.isNotBlank() && secureConfig.isEmpty()) {
                secureConfig.set(legacyConfig.trim(), legacyName)
                _wireGuardConfig.value = secureConfig.config
                _wireGuardConfigFileName.value = secureConfig.fileName
            }
            if (legacyConfig.isNotBlank() || legacyName.isNotBlank()) {
                context.dataStore.edit { p ->
                    p.remove(keys.wgConfig)
                    p.remove(keys.wgConfigFileName)
                }
            }
            // Refresh from secure store (covers process restarts)
            _wireGuardConfig.value = secureConfig.config
            _wireGuardConfigFileName.value = secureConfig.fileName
            migratedConfig = true
        }
    }

    /** Sync read for tile / quick checks after migration. */
    fun getWireGuardConfigSync(): String = secureConfig.config

    fun getWireGuardConfigFileNameSync(): String = secureConfig.fileName

    fun hasWireGuardConfigSync(): Boolean = secureConfig.config.isNotBlank()

    suspend fun getWireGuardConfig(): String {
        migrateSecureConfigIfNeeded()
        return secureConfig.config
    }

    suspend fun getWireGuardConfigFileName(): String {
        migrateSecureConfigIfNeeded()
        return secureConfig.fileName
    }

    suspend fun setWireGuardConfig(config: String, fileName: String) {
        migrateSecureConfigIfNeeded()
        secureConfig.set(config.trim(), fileName)
        _wireGuardConfig.value = secureConfig.config
        _wireGuardConfigFileName.value = secureConfig.fileName
    }

    suspend fun clearWireGuardConfig() {
        migrateSecureConfigIfNeeded()
        secureConfig.clear()
        _wireGuardConfig.value = ""
        _wireGuardConfigFileName.value = ""
        // Ensure legacy keys are gone
        context.dataStore.edit { p ->
            p.remove(keys.wgConfig)
            p.remove(keys.wgConfigFileName)
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

    /** Ready to run monitor in background (config + at least one trusted SSID). */
    suspend fun canStartMonitoring(): Boolean {
        migrateSecureConfigIfNeeded()
        return secureConfig.config.isNotBlank() && getTrustedWifiSsids().isNotEmpty()
    }

    /** Sync after migration — used by tile without blocking the main thread on DataStore. */
    fun canStartMonitoringSync(trustedCached: Set<String>? = null): Boolean {
        if (secureConfig.config.isBlank()) return false
        val trusted = trustedCached ?: return false
        return trusted.isNotEmpty()
    }

    companion object {
        const val DEFAULT_VPN_RETRY_ATTEMPTS = 10
        const val DEFAULT_VPN_RETRY_DELAY_SECONDS = 5
        const val MIN_VPN_RETRY_ATTEMPTS = 1
        const val MAX_VPN_RETRY_ATTEMPTS = 20
        const val MIN_VPN_RETRY_DELAY_SECONDS = 1
        const val MAX_VPN_RETRY_DELAY_SECONDS = 120

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
    }
}
