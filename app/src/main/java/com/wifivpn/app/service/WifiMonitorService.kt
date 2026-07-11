package com.wifivpn.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wifivpn.app.MainActivity
import com.wifivpn.app.R
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.tile.MonitorTileService
import com.wifivpn.app.vpn.WireGuardManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service: watches trusted Wi‑Fi SSIDs and toggles WireGuard.
 *
 * Policy:
 *  - Connected to a **trusted** SSID → VPN off
 *  - Any other Wi‑Fi, or no Wi‑Fi → VPN on
 *
 * VPN bring-up retries up to [VPN_MAX_ATTEMPTS] times with [VPN_RETRY_DELAY_MS] between tries.
 */
class WifiMonitorService : LifecycleService() {

    private val app get() = application as WifiVpnApp
    private lateinit var wifiMonitor: WifiConnectivityMonitor
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        wifiMonitor = WifiConnectivityMonitor(this)
        instance = this
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    stopMonitoringInternal()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        startAsForeground(getString(R.string.notification_waiting))

        if (monitorJob?.isActive == true) return

        _uiState.value = _uiState.value.copy(monitoring = true, message = "Monitoring…")
        MonitorTileService.requestUpdate(this)

        monitorJob = lifecycleScope.launch {
            app.configRepository.setMonitoringEnabled(true)

            // Network changes + trusted SSID list changes both re-evaluate VPN
            // collectLatest cancels in-flight VPN retries when the decision changes
            combine(
                wifiMonitor.wifiStatusFlow { emptySet() },
                app.configRepository.trustedWifiSsids
            ) { _, trustedSsids ->
                wifiMonitor.snapshot(trustedSsids)
            }.collectLatest { snap ->
                applyWifiDecision(snap)
            }
        }
    }

    private suspend fun applyWifiDecision(snap: WifiConnectivityMonitor.WifiSnapshot) {
        Log.i(
            TAG,
            "Decision: connected=${snap.wifiConnected} ssid=${snap.ssid} trusted=${snap.onTrustedWifi}"
        )
        _uiState.value = _uiState.value.copy(
            wifiConnected = snap.wifiConnected,
            onTrustedWifi = snap.onTrustedWifi,
            currentSsid = snap.ssid
        )

        if (snap.onTrustedWifi) {
            val result = app.wireGuardManager.setTunnelDown()
            val msg = if (result.isSuccess) {
                getString(R.string.notification_trusted_wifi)
            } else {
                getString(
                    R.string.notification_error,
                    WireGuardManager.formatError(result.exceptionOrNull())
                )
            }
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
        } else {
            bringVpnUpWithRetry(snap)
        }
    }

    /**
     * Tries to bring VPN up to [VPN_MAX_ATTEMPTS] times, waiting [VPN_RETRY_DELAY_MS]
     * between failed attempts. Shows progress in the UI/notification; final error if all fail.
     */
    private suspend fun bringVpnUpWithRetry(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val config = app.configRepository.getWireGuardConfig()
        if (config.isBlank()) {
            val msg = getString(R.string.msg_config_empty)
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            return
        }

        // Already connected — just refresh status text
        if (app.wireGuardManager.isUp) {
            val msg = successMessage(snap)
            _uiState.value = _uiState.value.copy(vpnActive = true, message = msg)
            updateNotification(msg)
            return
        }

        val excluded = app.configRepository.getExcludedApps()
        var lastError: Throwable? = null

        for (attempt in 1..VPN_MAX_ATTEMPTS) {
            val progressMsg = if (attempt == 1) {
                getString(R.string.vpn_connecting)
            } else {
                getString(R.string.vpn_retry_attempt, attempt, VPN_MAX_ATTEMPTS)
            }
            _uiState.value = _uiState.value.copy(vpnActive = false, message = progressMsg)
            updateNotification(progressMsg)
            Log.i(TAG, "VPN connect attempt $attempt/$VPN_MAX_ATTEMPTS")

            val result = app.wireGuardManager.setTunnelUp(config, excluded)
            if (result.isSuccess) {
                val msg = successMessage(snap)
                _uiState.value = _uiState.value.copy(vpnActive = true, message = msg)
                updateNotification(msg)
                Log.i(TAG, "VPN up on attempt $attempt")
                return
            }

            lastError = result.exceptionOrNull()
            val errText = WireGuardManager.formatError(lastError)
            Log.w(TAG, "VPN attempt $attempt failed: $errText")

            if (app.wireGuardManager.isNonRetryable(lastError)) {
                Log.w(TAG, "Non-retryable error — stopping retries")
                break
            }

            if (attempt < VPN_MAX_ATTEMPTS) {
                val waitMsg = getString(
                    R.string.vpn_retrying,
                    attempt,
                    VPN_MAX_ATTEMPTS
                )
                _uiState.value = _uiState.value.copy(vpnActive = false, message = waitMsg)
                updateNotification(waitMsg)
                try {
                    delay(VPN_RETRY_DELAY_MS)
                } catch (e: CancellationException) {
                    Log.i(TAG, "VPN retry cancelled (network decision changed)")
                    throw e
                }
            }
        }

        val finalMsg = getString(
            R.string.vpn_connect_failed,
            VPN_MAX_ATTEMPTS,
            WireGuardManager.formatError(lastError)
        )
        _uiState.value = _uiState.value.copy(vpnActive = false, message = finalMsg)
        updateNotification(finalMsg)
        Log.e(TAG, finalMsg)
    }

    private fun successMessage(snap: WifiConnectivityMonitor.WifiSnapshot): String {
        return when {
            !snap.wifiConnected -> getString(R.string.notification_wifi_lost)
            else -> getString(R.string.notification_untrusted_wifi)
        }
    }

    private suspend fun stopMonitoringInternal() {
        monitorJob?.cancel()
        monitorJob = null
        app.wireGuardManager.setTunnelDown()
        app.configRepository.setMonitoringEnabled(false)
        val snap = wifiMonitor.snapshot(app.configRepository.getTrustedWifiSsids())
        _uiState.value = MonitorUiState(
            monitoring = false,
            wifiConnected = snap.wifiConnected,
            onTrustedWifi = snap.onTrustedWifi,
            currentSsid = snap.ssid,
            vpnActive = false,
            message = "Monitoring stopped"
        )
        MonitorTileService.requestUpdate(this)
        Log.i(TAG, "Monitoring stopped")
    }

    private fun startAsForeground(content: String) {
        val notification = buildNotification(content)
        // location: SSID is location-sensitive; keeps reads working with screen off while
        // the monitor FGS is running (while-in-use location permission is enough).
        // specialUse: declared purpose of continuous Wi‑Fi / VPN policy monitoring (API 34+).
        val fgsType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                WifiVpnApp.NOTIFICATION_ID,
                notification,
                fgsType
            )
        } else {
            startForeground(WifiVpnApp.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(WifiVpnApp.NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WifiVpnApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.btn_stop_monitoring), stopIntent)
            .build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        if (instance === this) instance = null
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    data class MonitorUiState(
        val monitoring: Boolean = false,
        val wifiConnected: Boolean = false,
        val onTrustedWifi: Boolean = false,
        val currentSsid: String? = null,
        val vpnActive: Boolean = false,
        val message: String = ""
    )

    companion object {
        private const val TAG = "WifiMonitorService"
        const val ACTION_STOP = "com.wifivpn.app.action.STOP_MONITORING"

        /** Max VPN bring-up attempts when not on trusted Wi‑Fi. */
        private const val VPN_MAX_ATTEMPTS = 3

        /** Delay between failed VPN attempts. */
        private const val VPN_RETRY_DELAY_MS = 5_000L

        @Volatile
        var instance: WifiMonitorService? = null
            private set

        private val _uiState = MutableStateFlow(MonitorUiState())
        val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

        fun startIntent(context: Context): Intent =
            Intent(context, WifiMonitorService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, WifiMonitorService::class.java).setAction(ACTION_STOP)

        fun start(context: Context) {
            val intent = startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }
    }
}
