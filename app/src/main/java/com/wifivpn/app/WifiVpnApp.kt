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
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * Application-scoped work (no runBlocking on main).
     * Uncaught coroutine failures are written to the diagnostic log when possible.
     *
     * Limits: Java/Kotlin uncaught exceptions and coroutine failures are covered.
     * Native / WireGuard Go crashes, OOM kills, and force-stops are not.
     */
    val applicationScope: CoroutineScope by lazy {
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught coroutine exception", throwable)
            if (::diagnosticLogger.isInitialized) {
                diagnosticLogger.logException(
                    "CRASH",
                    "uncaught coroutine: ${throwable.javaClass.name}: ${throwable.message}",
                    throwable,
                    forceWrite = true
                )
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

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

    /**
     * Records uncaught JVM exceptions to the diagnostic log (always, even if
     * routine logging is off), then delegates to the previous handler.
     *
     * Does **not** cover: native/WireGuard Go process crashes, OOM, ANR,
     * or system force-stop. Prefer also checking logcat / Play vitals for those.
     */
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
