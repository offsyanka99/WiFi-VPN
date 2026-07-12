package com.wifivpn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.wifivpn.app.data.ConfigRepository
import com.wifivpn.app.log.DiagnosticLogger
import com.wifivpn.app.permission.PermissionCheckWorker
import com.wifivpn.app.util.AppInfo
import com.wifivpn.app.vpn.WireGuardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WifiVpnApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var wireGuardManager: WireGuardManager
        private set

    lateinit var diagnosticLogger: DiagnosticLogger
        private set

    /** Application-scoped work (no runBlocking on main). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cached for tile / quick checks without DataStore suspend. */
    @Volatile
    var cachedTrustedSsids: Set<String> = emptySet()
        private set

    @Volatile
    var cachedCanStartMonitoring: Boolean = false
        private set

    @Volatile
    var cachedConfigFileName: String = ""
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configRepository = ConfigRepository(this)
        diagnosticLogger = DiagnosticLogger(this)
        installUncaughtExceptionHandler()
        // Default logging off until prefs load (avoids runBlocking on main)
        diagnosticLogger.setEnabled(false)
        // Sync-warm config caches from encrypted store (no DataStore)
        cachedConfigFileName = configRepository.getWireGuardConfigFileNameSync()
        wireGuardManager = WireGuardManager(this)
        createNotificationChannels()
        PermissionCheckWorker.schedule(this)

        applicationScope.launch {
            runCatching { configRepository.migrateSecureConfigIfNeeded() }
                .onFailure { Log.e(TAG, "Secure config migration failed", it) }

            refreshRuntimeCaches()

            val loggingEnabled = configRepository.isDiagnosticLoggingEnabled()
            diagnosticLogger.setEnabled(loggingEnabled)
            if (loggingEnabled) {
                diagnosticLogger.logSessionStart(AppInfo.versionLabel(this@WifiVpnApp))
            }

            launch {
                configRepository.trustedWifiSsids.collectLatest { ssids ->
                    cachedTrustedSsids = ssids
                    recomputeCanStart()
                }
            }
            launch {
                configRepository.wireGuardConfigFileName.collectLatest { name ->
                    cachedConfigFileName = name
                    recomputeCanStart()
                }
            }
            launch {
                configRepository.wireGuardConfig.collectLatest {
                    recomputeCanStart()
                }
            }
            launch {
                configRepository.diagnosticLoggingEnabled.collectLatest { enabled ->
                    if (diagnosticLogger.isEnabled() != enabled) {
                        diagnosticLogger.setEnabled(enabled)
                    }
                }
            }
        }
    }

    suspend fun refreshRuntimeCaches() {
        cachedConfigFileName = configRepository.getWireGuardConfigFileNameSync()
        cachedTrustedSsids = configRepository.getTrustedWifiSsids()
        recomputeCanStart()
    }

    private fun recomputeCanStart() {
        cachedCanStartMonitoring =
            configRepository.hasWireGuardConfigSync() && cachedTrustedSsids.isNotEmpty()
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                diagnosticLogger.logUncaughtCrash(thread, throwable)
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun createNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(monitor)

        val alerts = NotificationChannel(
            ALERTS_CHANNEL_ID,
            getString(R.string.notification_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_alerts_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(alerts)
    }

    companion object {
        private const val TAG = "WifiVpnApp"
        const val NOTIFICATION_CHANNEL_ID = "wifi_vpn_monitor"
        const val ALERTS_CHANNEL_ID = "wifi_vpn_alerts"
        const val NOTIFICATION_ID = 1001
        const val PERMISSION_ALERT_NOTIFICATION_ID = 1002

        lateinit var instance: WifiVpnApp
            private set
    }
}
