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
import com.wifivpn.app.log.DiagnosticSupport
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
        app.diagnosticLogger.i(CAT_MONITOR, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                val stopSource = intent.getStringExtra(EXTRA_START_SOURCE) ?: SOURCE_UNKNOWN
                lifecycleScope.launch {
                    app.diagnosticLogger.i(CAT_MONITOR, "monitoring stop requested source=$stopSource")
                    stopMonitoringInternal()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                val source = intent?.getStringExtra(EXTRA_START_SOURCE) ?: SOURCE_UNKNOWN
                startMonitoring(source)
            }
        }
        return START_STICKY
    }

    private fun startMonitoring(source: String) {
        startAsForeground(getString(R.string.notification_waiting))

        if (monitorJob?.isActive == true) {
            app.diagnosticLogger.i(
                CAT_MONITOR,
                "monitoring start ignored (already active) source=$source"
            )
            return
        }

        _uiState.value = _uiState.value.copy(monitoring = true, message = "Monitoring…")
        MonitorTileService.requestUpdate(this)
        app.diagnosticLogger.i(
            CAT_MONITOR,
            "monitoring started source=$source " +
                "perms: ${DiagnosticSupport.permissionSnapshot(this)}"
        )

        monitorJob = lifecycleScope.launch {
            app.configRepository.setMonitoringEnabled(true)
            DiagnosticSupport.logSupportSummary(app, "monitor_start source=$source")

            // Keep trusted SSID set in the monitor (used by wifiStatusFlow snapshots)
            launch {
                app.configRepository.trustedWifiSsids.collect { ssids ->
                    wifiMonitor.setTrustedSsids(ssids)
                    // Re-evaluate immediately when the trusted list changes
                    applyWifiDecision(wifiMonitor.snapshot(ssids))
                }
            }

            // Network changes → debounced policy snapshots; collectLatest cancels VPN retries
            wifiMonitor.wifiStatusFlow().collectLatest { snap ->
                applyWifiDecision(snap)
            }
        }
    }

    /** Last policy key we acted on — skip redundant tunnel toggles / log lines. */
    private var lastPolicyKey: String? = null

    private suspend fun applyWifiDecision(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val wantVpnOn = !snap.onTrustedWifi
        val policyKey =
            "${snap.wifiConnected}|${snap.ssid}|${snap.onTrustedWifi}|${snap.transports}|$wantVpnOn"
        val tunnelMatches =
            (wantVpnOn && app.wireGuardManager.isUp) ||
                (!wantVpnOn && !app.wireGuardManager.isUp)
        if (policyKey == lastPolicyKey && tunnelMatches) {
            // Soft UI refresh only
            _uiState.value = _uiState.value.copy(
                wifiConnected = snap.wifiConnected,
                onTrustedWifi = snap.onTrustedWifi,
                currentSsid = snap.ssid,
                vpnActive = app.wireGuardManager.isUp
            )
            return
        }
        lastPolicyKey = policyKey

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "Decision: connected=${snap.wifiConnected} ssid=${snap.ssid} trusted=${snap.onTrustedWifi}"
            )
        }
        logNetworkAndDecision(snap)
        _uiState.value = _uiState.value.copy(
            wifiConnected = snap.wifiConnected,
            onTrustedWifi = snap.onTrustedWifi,
            currentSsid = snap.ssid
        )

        if (snap.onTrustedWifi) {
            val wasUp = app.wireGuardManager.isUp
            val result = app.wireGuardManager.setTunnelDown()
            val msg = if (result.isSuccess) {
                getString(R.string.notification_trusted_wifi)
            } else {
                getString(
                    R.string.notification_error,
                    WireGuardManager.formatError(result.exceptionOrNull())
                )
            }
            if (wasUp || result.isFailure) {
                if (result.isSuccess) {
                    app.diagnosticLogger.i(
                        CAT_VPN,
                        "VPN off (trusted Wi‑Fi) result=success wasUp=$wasUp"
                    )
                } else {
                    app.diagnosticLogger.logException(
                        CAT_VPN,
                        "VPN off (trusted Wi‑Fi) result=failure " +
                            "error=${WireGuardManager.formatError(result.exceptionOrNull())}",
                        result.exceptionOrNull()
                    )
                }
            } else {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "VPN already off on trusted Wi‑Fi ssid=${snap.ssid ?: "unknown"}"
                )
            }
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            MonitorTileService.requestUpdate(this)
        } else {
            bringVpnUpWithRetry(snap)
        }
    }

    private fun logNetworkAndDecision(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val wifiLabel = when {
            !snap.wifiConnected -> "disconnected"
            snap.onTrustedWifi -> "trusted"
            else -> "other"
        }
        val ssidPart = snap.ssid?.let { "ssid=\"$it\"" } ?: "ssid=unknown"
        val decision = if (snap.onTrustedWifi) "VPN_OFF" else "VPN_ON"
        app.diagnosticLogger.i(
            CAT_NETWORK,
            "wifi=$wifiLabel $ssidPart trusted_match=${snap.trustedMatch} " +
                "ssid_redacted=${snap.ssidRedacted} ssid_from_cache=${snap.ssidFromCache} " +
                "ssid_perm=${if (snap.hasSsidPermission) "ok" else "no"} " +
                "screen=${if (snap.screenInteractive) "on" else "off"} " +
                "cellular=${if (snap.cellularConnected) "up" else "down"} " +
                "transports=${snap.transports.ifEmpty { "none" }} " +
                "vpn_before=${if (app.wireGuardManager.isUp) "on" else "off"} " +
                "decision=$decision"
        )
    }

    /**
     * Tries to bring VPN up using configured attempt count and delay
     * (Configuration → VPN connection retries).
     */
    private suspend fun bringVpnUpWithRetry(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val config = app.configRepository.getWireGuardConfig()
        if (config.isBlank()) {
            val msg = getString(R.string.msg_config_empty)
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            app.diagnosticLogger.w(CAT_VPN, "VPN on skipped — WireGuard config empty")
            return
        }

        // Already connected — just refresh status text
        if (app.wireGuardManager.isUp) {
            val msg = successMessage(snap)
            _uiState.value = _uiState.value.copy(vpnActive = true, message = msg)
            updateNotification(msg)
            MonitorTileService.requestUpdate(this)
            app.diagnosticLogger.i(
                CAT_VPN,
                "VPN already on — no reconnect " +
                    "(wifi=${if (snap.wifiConnected) "up" else "down"} " +
                    "ssid=${snap.ssid ?: "none"} cellular=${if (snap.cellularConnected) "up" else "down"})"
            )
            return
        }

        val maxAttempts = app.configRepository.getVpnRetryAttempts()
        val delayMs = app.configRepository.getVpnRetryDelaySeconds() * 1000L
        val excluded = app.configRepository.getExcludedApps()
        var lastError: Throwable? = null

        app.diagnosticLogger.i(
            CAT_VPN,
            "VPN connect starting maxAttempts=$maxAttempts delaySec=${delayMs / 1000} " +
                "excludedApps=${excluded.size} " +
                "reason=${if (!snap.wifiConnected) "no_wifi" else "untrusted_wifi"} " +
                "config ${DiagnosticSupport.configFingerprint(config)}"
        )

        for (attempt in 1..maxAttempts) {
            val progressMsg = if (attempt == 1) {
                getString(R.string.vpn_connecting)
            } else {
                getString(R.string.vpn_retry_attempt, attempt, maxAttempts)
            }
            _uiState.value = _uiState.value.copy(vpnActive = false, message = progressMsg)
            updateNotification(progressMsg)
            Log.i(TAG, "VPN connect attempt $attempt/$maxAttempts (delay=${delayMs}ms)")
            app.diagnosticLogger.i(
                CAT_VPN,
                "tunnel connect attempt=$attempt/$maxAttempts"
            )

            val result = app.wireGuardManager.setTunnelUp(config, excluded)
            if (result.isSuccess) {
                val msg = successMessage(snap)
                _uiState.value = _uiState.value.copy(vpnActive = true, message = msg)
                updateNotification(msg)
                MonitorTileService.requestUpdate(this)
                Log.i(TAG, "VPN up on attempt $attempt")
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "tunnel connect SUCCESS attempt=$attempt/$maxAttempts vpn=on"
                )
                return
            }

            lastError = result.exceptionOrNull()
            val errText = WireGuardManager.formatError(lastError)
            Log.w(TAG, "VPN attempt $attempt failed: $errText")
            app.diagnosticLogger.w(
                CAT_VPN,
                "tunnel connect FAILED attempt=$attempt/$maxAttempts error=$errText"
            )

            if (app.wireGuardManager.isNonRetryable(lastError)) {
                Log.w(TAG, "Non-retryable error — stopping retries")
                app.diagnosticLogger.w(
                    CAT_VPN,
                    "non-retryable error — stopping retries error=$errText"
                )
                break
            }

            if (attempt < maxAttempts) {
                val waitMsg = getString(
                    R.string.vpn_retrying,
                    attempt,
                    maxAttempts,
                    (delayMs / 1000L).toInt()
                )
                _uiState.value = _uiState.value.copy(vpnActive = false, message = waitMsg)
                updateNotification(waitMsg)
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "retry scheduled attempt=${attempt + 1}/$maxAttempts " +
                        "waitSec=${delayMs / 1000}"
                )
                try {
                    delay(delayMs)
                } catch (e: CancellationException) {
                    Log.i(TAG, "VPN retry cancelled (network decision changed)")
                    app.diagnosticLogger.i(
                        CAT_VPN,
                        "tunnel connect cancelled (network decision changed) " +
                            "after attempt=$attempt/$maxAttempts"
                    )
                    throw e
                }
            }
        }

        val finalMsg = getString(
            R.string.vpn_connect_failed,
            maxAttempts,
            WireGuardManager.formatError(lastError)
        )
        _uiState.value = _uiState.value.copy(vpnActive = false, message = finalMsg)
        updateNotification(finalMsg)
        MonitorTileService.requestUpdate(this)
        Log.e(TAG, finalMsg)
        app.diagnosticLogger.logException(
            CAT_VPN,
            "tunnel connect GAVE UP after attempts vpn=off " +
                "error=${WireGuardManager.formatError(lastError)}",
            lastError
        )
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
        lastPolicyKey = null
        lastNotificationContent = null
        val wasUp = app.wireGuardManager.isUp
        val downResult = app.wireGuardManager.setTunnelDown()
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
        app.diagnosticLogger.i(
            CAT_MONITOR,
            "monitoring stopped vpnWasUp=$wasUp " +
                "vpnDown=${if (downResult.isSuccess) "ok" else "fail"} " +
                "wifi=${if (snap.wifiConnected) "up" else "down"} " +
                "ssid=${snap.ssid ?: "none"}"
        )
    }

    private fun startAsForeground(content: String) {
        lastNotificationContent = content
        val notification = buildNotification(content)
        // location: SSID is location-sensitive; keeps reads working with screen off while
        // the monitor FGS is running (while-in-use location permission is enough).
        // specialUse: declared purpose of continuous Wi‑Fi / VPN policy monitoring (API 34+).
        // Note: location FGS cannot start from background (e.g. raw TileService) on API 34+
        // without ACCESS_BACKGROUND_LOCATION — callers should start from an Activity.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(WifiVpnApp.NOTIFICATION_ID, notification)
            return
        }

        val locationAndSpecial =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }

        try {
            ServiceCompat.startForeground(
                this,
                WifiVpnApp.NOTIFICATION_ID,
                notification,
                locationAndSpecial
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "FGS location type rejected, falling back to specialUse: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    ServiceCompat.startForeground(
                        this,
                        WifiVpnApp.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    return
                } catch (e2: SecurityException) {
                    Log.e(TAG, "FGS specialUse also rejected", e2)
                    throw e2
                }
            }
            throw e
        }
    }

    /** Last posted notification text — skip identical updates (less binder noise). */
    private var lastNotificationContent: String? = null

    private fun updateNotification(content: String) {
        if (content == lastNotificationContent) return
        lastNotificationContent = content
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
        app.diagnosticLogger.i(CAT_MONITOR, "service destroyed")
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
        private const val CAT_MONITOR = "MONITOR"
        private const val CAT_NETWORK = "NETWORK"
        private const val CAT_VPN = "VPN"
        const val ACTION_STOP = "com.wifivpn.app.action.STOP_MONITORING"

        @Volatile
        var instance: WifiMonitorService? = null
            private set

        private val _uiState = MutableStateFlow(MonitorUiState())
        val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

        const val EXTRA_START_SOURCE = "com.wifivpn.app.extra.START_SOURCE"
        const val SOURCE_UI = "ui"
        const val SOURCE_TILE = "tile"
        const val SOURCE_BOOT = "boot"
        const val SOURCE_UNKNOWN = "unknown"

        fun startIntent(context: Context, source: String = SOURCE_UNKNOWN): Intent =
            Intent(context, WifiMonitorService::class.java).putExtra(EXTRA_START_SOURCE, source)

        fun stopIntent(context: Context, source: String = SOURCE_UNKNOWN): Intent =
            Intent(context, WifiMonitorService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_START_SOURCE, source)

        fun start(context: Context, source: String = SOURCE_UNKNOWN) {
            val intent = startIntent(context, source)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, source: String = SOURCE_UNKNOWN) {
            context.startService(stopIntent(context, source))
        }
    }
}
