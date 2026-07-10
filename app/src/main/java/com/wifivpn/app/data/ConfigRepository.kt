package com.wifivpn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wifi_vpn_prefs")

class ConfigRepository(private val context: Context) {

    private val keys = object {
        val wgConfig = stringPreferencesKey("wg_config")
        val wgConfigFileName = stringPreferencesKey("wg_config_file_name")
        val monitoringEnabled = booleanPreferencesKey("monitoring_enabled")
        val autoStartEnabled = booleanPreferencesKey("auto_start_enabled")
        val excludedApps = stringSetPreferencesKey("excluded_apps")
        val trustedWifiSsids = stringSetPreferencesKey("trusted_wifi_ssids")
    }

    val wireGuardConfig: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keys.wgConfig].orEmpty()
    }

    val wireGuardConfigFileName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keys.wgConfigFileName].orEmpty()
    }

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

    suspend fun getWireGuardConfig(): String {
        return context.dataStore.data.first()[keys.wgConfig].orEmpty()
    }

    suspend fun getWireGuardConfigFileName(): String {
        return context.dataStore.data.first()[keys.wgConfigFileName].orEmpty()
    }

    suspend fun setWireGuardConfig(config: String, fileName: String) {
        context.dataStore.edit { prefs ->
            prefs[keys.wgConfig] = config.trim()
            prefs[keys.wgConfigFileName] = fileName
        }
    }

    suspend fun clearWireGuardConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(keys.wgConfig)
            prefs.remove(keys.wgConfigFileName)
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

    /** Ready to run monitor in background (config + at least one trusted SSID). */
    suspend fun canStartMonitoring(): Boolean {
        return getWireGuardConfig().isNotBlank() && getTrustedWifiSsids().isNotEmpty()
    }

    companion object {
        fun normalizeSsid(raw: String): String? {
            var s = raw.trim()
            if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
                s = s.substring(1, s.length - 1).trim()
            }
            return s.ifBlank { null }
        }
    }
}
