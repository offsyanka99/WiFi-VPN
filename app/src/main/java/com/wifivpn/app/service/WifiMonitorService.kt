package com.wifivpn.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
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
import com.wifivpn.app.widget.StatusWidgets
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * Foreground service: watches trusted Wi‑Fi SSIDs and toggles WireGuard.
 *
 * Policy:
 *  - Connected to a **trusted** SSID → VPN off
 *  - Any other Wi‑Fi, or no Wi‑Fi → VPN on
 *
 * VPN bring-up retries up to the configured attempt count with configured delay between tries.
 * If the tunnel drops while policy still wants VPN on, reconnect is attempted the same way.
 * When attempts are exhausted, the user can **Retry** (reset attempts) or **Stop monitoring**
 * (after an insecure-connection warning).
 */
class WifiMonitorService : LifecycleService() {

    private val app get() = application as WifiVpnApp
    private lateinit var wifiMonitor: WifiConnectivityMonitor
    private var monitorJob: Job? = null
    /** Polls WireGuard transfer stats while the tunnel is up (feeds UI flow + widgets). */
    private var statsPollJob: Job? = null
    /**
     * [SystemClock.elapsedRealtime] when the current monitoring session began.
     * Used for a short Wi‑Fi/SSID settle grace after process start (boot / update).
     */
    private var monitoringStartedAtElapsed: Long = 0L
    /**
     * Policy currently wants the tunnel up (not on trusted Wi‑Fi).
     * Used to distinguish intentional VPN-off from unexpected drops.
     */
    @Volatile
    private var policyWantsVpnUp: Boolean = false
    /** True while [bringVpnUpWithRetry] is running (avoids nested reconnects). */
    @Volatile
    private var vpnConnectInProgress: Boolean = false
    /** Snapshot used when the user taps Retry after exhausted attempts. */
    private var lastVpnPolicySnap: WifiConnectivityMonitor.WifiSnapshot? = null
    /** Serializes policy decisions (Wi‑Fi flow, tunnel drop, user Retry). */
    private val decisionMutex = Mutex()

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
            ACTION_RETRY_VPN -> {
                lifecycleScope.launch {
                    app.diagnosticLogger.i(CAT_VPN, "VPN retry requested by user")
                    handleUserRetryVpn()
                }
                return START_STICKY
            }
            ACTION_PROMPT_STOP_INSECURE -> {
                // Open UI for the “connection will not be secure” confirmation
                val open = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra(MainActivity.EXTRA_PROMPT_STOP_INSECURE, true)
                }
                startActivity(open)
                return START_STICKY
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
            // Still refresh tile/widgets (e.g. user tapped Start while already on).
            notifyUiSurfaces()
            return
        }

        _uiState.value = _uiState.value.copy(
            monitoring = true,
            reconnectChoicePending = false,
            message = getString(R.string.notification_monitoring)
        )
        notifyUiSurfaces()
        app.diagnosticLogger.i(
            CAT_MONITOR,
            "monitoring started source=$source " +
                "perms: ${DiagnosticSupport.permissionSnapshot(this)}"
        )

        monitoringStartedAtElapsed = SystemClock.elapsedRealtime()
        policyWantsVpnUp = false
        vpnConnectInProgress = false
        lastVpnPolicySnap = null
        monitorJob = lifecycleScope.launch {
            app.configRepository.setMonitoringEnabled(true)
            // Load trusted list before any VPN decision (avoids empty-list race after update/boot)
            val initialTrusted = app.configRepository.getTrustedWifiSsids()
            wifiMonitor.setTrustedSsids(initialTrusted)
            DiagnosticSupport.logSupportSummary(app, "monitor_start source=$source")

            // Keep trusted SSID set in the monitor (used by wifiStatusFlow snapshots).
            // Skip the first emission (already set above) so we do not race the Wi‑Fi flow's
            // initial decision with a second concurrent applyWifiDecision.
            launch {
                var isFirst = true
                app.configRepository.trustedWifiSsids.collect { ssids ->
                    wifiMonitor.setTrustedSsids(ssids)
                    if (isFirst) {
                        isFirst = false
                        return@collect
                    }
                    // Re-evaluate when the user edits the trusted list
                    applyWifiDecision(wifiMonitor.snapshot(ssids))
                }
            }

            // Unexpected tunnel drop while policy still wants VPN → re-run connect retries
            launch {
                var wasUp = app.wireGuardManager.isUp
                app.wireGuardManager.stateFlow.collect { state ->
                    val up = state == Tunnel.State.UP
                    if (wasUp && !up) {
                        onTunnelDroppedWhileUp()
                    }
                    wasUp = up
                }
            }

            // Collect immediately so network changes can cancel VPN retries / settle waits.
            // Do not block on a separate first decision first — after boot Wi‑Fi is often still
            // coming up, and a blocking VPN bring-up would miss the trusted-SSID transition.
            wifiMonitor.wifiStatusFlow().collectLatest { snap ->
                applyWifiDecision(snap)
            }
        }
    }

    /**
     * Tunnel went from UP → not UP. If we still want VPN and the user is not choosing
     * Retry/Stop, clear the policy key and reconnect (same attempt budget as a fresh connect).
     */
    private suspend fun onTunnelDroppedWhileUp() {
        if (!policyWantsVpnUp) {
            app.diagnosticLogger.i(CAT_VPN, "tunnel down — expected (policy wants VPN off)")
            return
        }
        if (_uiState.value.reconnectChoicePending) {
            app.diagnosticLogger.i(CAT_VPN, "tunnel down — waiting for user Retry/Stop")
            return
        }
        if (vpnConnectInProgress) {
            app.diagnosticLogger.i(CAT_VPN, "tunnel down during connect attempts — connect loop owns retries")
            return
        }
        app.diagnosticLogger.w(
            CAT_VPN,
            "VPN connection lost while policy wants VPN on — reestablishing"
        )
        lastPolicyKey = null
        val snap = lastVpnPolicySnap
            ?: wifiMonitor.snapshot(wifiMonitor.getTrustedSsids())
        applyWifiDecision(snap)
    }

    /** User chose Retry after exhausted attempts — reset and connect again. */
    private suspend fun handleUserRetryVpn() {
        if (monitorJob?.isActive != true) {
            app.diagnosticLogger.w(CAT_VPN, "VPN retry ignored — monitoring not active")
            return
        }
        decisionMutex.withLock {
            _uiState.value = _uiState.value.copy(reconnectChoicePending = false)
            lastPolicyKey = null
            val snap = lastVpnPolicySnap
                ?: wifiMonitor.snapshot(wifiMonitor.getTrustedSsids())
            if (snap.onTrustedWifi) {
                // Network became trusted while waiting — just apply policy (VPN stays off)
                applyWifiDecisionLocked(snap)
                return
            }
            app.diagnosticLogger.i(CAT_VPN, "user Retry — restarting VPN connect attempts from 0")
            bringVpnUpWithRetry(snap)
        }
    }

    /** Last policy key we acted on — skip redundant tunnel toggles / log lines. */
    private var lastPolicyKey: String? = null

    private suspend fun applyWifiDecision(snap: WifiConnectivityMonitor.WifiSnapshot) {
        decisionMutex.withLock {
            applyWifiDecisionLocked(snap)
        }
    }

    private suspend fun applyWifiDecisionLocked(snap: WifiConnectivityMonitor.WifiSnapshot) {
        // User must choose Retry / Stop after failed reestablish — only auto-clear when
        // policy no longer wants VPN (e.g. joined trusted Wi‑Fi).
        if (_uiState.value.reconnectChoicePending) {
            if (snap.onTrustedWifi) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "trusted Wi‑Fi while reconnect choice pending — clear choice, VPN stays off"
                )
                _uiState.value = _uiState.value.copy(reconnectChoicePending = false)
                // fall through to normal trusted path
            } else {
                val prev = _uiState.value
                val next = prev.copy(
                    wifiConnected = snap.wifiConnected,
                    onTrustedWifi = false,
                    currentSsid = snap.ssid,
                    vpnActive = false
                )
                if (prev != next) {
                    _uiState.value = next
                    notifyUiSurfaces()
                }
                return
            }
        }

        // After boot/update, association + SSID often lag while still on trusted Wi‑Fi.
        // Resolve identity before committing a VPN-on decision (collectLatest cancels this wait
        // if a fresher network snapshot arrives).
        var effective = snap
        if (!snap.onTrustedWifi &&
            !app.wireGuardManager.isUp &&
            needsWifiIdentitySettle(snap)
        ) {
            // Soft UI while waiting — do not lock lastPolicyKey yet
            _uiState.value = _uiState.value.copy(
                wifiConnected = snap.wifiConnected,
                onTrustedWifi = false,
                currentSsid = snap.ssid,
                message = getString(R.string.notification_waiting)
            )
            updateNotification(getString(R.string.notification_waiting))
            effective = waitForWifiIdentity(snap)
            if (effective.onTrustedWifi) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "Wi‑Fi resolved as trusted during settle — VPN stays off"
                )
            }
        }

        val wantVpnOn = !effective.onTrustedWifi
        val policyKey =
            "${effective.wifiConnected}|${effective.ssid}|${effective.onTrustedWifi}|" +
                "${effective.transports}|$wantVpnOn"
        val tunnelMatches =
            (wantVpnOn && app.wireGuardManager.isUp) ||
                (!wantVpnOn && !app.wireGuardManager.isUp)
        if (policyKey == lastPolicyKey && tunnelMatches) {
            // Soft UI refresh only
            val prev = _uiState.value
            val next = prev.copy(
                wifiConnected = effective.wifiConnected,
                onTrustedWifi = effective.onTrustedWifi,
                currentSsid = effective.ssid,
                vpnActive = app.wireGuardManager.isUp
            )
            _uiState.value = next
            // Keep transfer polling alive if the tunnel is already up.
            if (app.wireGuardManager.isUp) {
                startStatsPolling()
            } else {
                stopStatsPolling()
            }
            if (prev != next) notifyUiSurfaces()
            return
        }
        lastPolicyKey = policyKey
        lastVpnPolicySnap = effective
        policyWantsVpnUp = wantVpnOn

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "Decision: connected=${effective.wifiConnected} ssid=${effective.ssid} " +
                    "trusted=${effective.onTrustedWifi}"
            )
        }
        logNetworkAndDecision(effective)
        _uiState.value = _uiState.value.copy(
            wifiConnected = effective.wifiConnected,
            onTrustedWifi = effective.onTrustedWifi,
            currentSsid = effective.ssid
        )

        if (effective.onTrustedWifi) {
            policyWantsVpnUp = false
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
                    "VPN already off on trusted Wi‑Fi ssid=${effective.ssid ?: "unknown"}"
                )
            }
            _uiState.value = _uiState.value.copy(
                vpnActive = false,
                reconnectChoicePending = false,
                message = msg
            )
            updateNotification(msg)
            stopStatsPolling()
            notifyUiSurfaces()
        } else {
            bringVpnUpWithRetry(effective)
        }
    }

    /**
     * True while we should hold off turning VPN on: within settle grace and either
     * Wi‑Fi is still down, or it is up but SSID is not readable yet (with permission).
     */
    private fun needsWifiIdentitySettle(snap: WifiConnectivityMonitor.WifiSnapshot): Boolean {
        if (!withinWifiSettleGrace()) return false
        if (!snap.wifiConnected) return true
        return snap.ssid == null && snap.hasSsidPermission
    }

    private fun withinWifiSettleGrace(): Boolean {
        if (monitoringStartedAtElapsed <= 0L) return false
        return SystemClock.elapsedRealtime() - monitoringStartedAtElapsed < WIFI_SETTLE_GRACE_MS
    }

    /**
     * Poll until SSID/association is known, grace expires, or [collectLatest] cancels us
     * because a fresher network snapshot arrived.
     */
    private suspend fun waitForWifiIdentity(
        initial: WifiConnectivityMonitor.WifiSnapshot
    ): WifiConnectivityMonitor.WifiSnapshot {
        var current = initial
        val deadline = monitoringStartedAtElapsed + WIFI_SETTLE_GRACE_MS
        while (needsWifiIdentitySettle(current)) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            val reason = when {
                !current.wifiConnected -> "Wi‑Fi not up yet"
                else -> "SSID unknown"
            }
            app.diagnosticLogger.i(
                CAT_VPN,
                "defer VPN up — $reason " +
                    "(poll ${WIFI_SETTLE_POLL_MS}ms, grace remaining=${remaining}ms)"
            )
            delay(min(WIFI_SETTLE_POLL_MS, remaining))
            current = wifiMonitor.snapshot(wifiMonitor.getTrustedSsids())
            if (current.onTrustedWifi) return current
            // Definitive untrusted SSID — stop waiting and turn VPN on
            if (current.wifiConnected && current.ssid != null) return current
        }
        return current
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
     * (Configuration → VPN connection retries). Starts attempt count from zero
     * (attempt 1…N) on every call — including after the user taps **Retry**.
     */
    private suspend fun bringVpnUpWithRetry(snap: WifiConnectivityMonitor.WifiSnapshot) {
        lastVpnPolicySnap = snap
        policyWantsVpnUp = true
        val config = app.configRepository.getWireGuardConfig()
        if (config.isBlank()) {
            val msg = getString(R.string.msg_config_empty)
            presentReconnectChoice(msg, detailError = msg, attempts = 0)
            app.diagnosticLogger.w(CAT_VPN, "VPN on skipped — WireGuard config empty")
            return
        }

        // Already connected — just refresh status text
        if (app.wireGuardManager.isUp) {
            val msg = successMessage(snap)
            _uiState.value = _uiState.value.copy(
                vpnActive = true,
                reconnectChoicePending = false,
                message = msg
            )
            updateNotification(msg)
            startStatsPolling()
            notifyUiSurfaces()
            app.diagnosticLogger.i(
                CAT_VPN,
                "VPN already on — no reconnect " +
                    "(wifi=${if (snap.wifiConnected) "up" else "down"} " +
                    "ssid=${snap.ssid ?: "none"} cellular=${if (snap.cellularConnected) "up" else "down"})"
            )
            return
        }

        if (vpnConnectInProgress) {
            app.diagnosticLogger.i(CAT_VPN, "VPN connect already in progress — skip duplicate")
            return
        }

        val maxAttempts = app.configRepository.getVpnRetryAttempts()
        val delayMs = app.configRepository.getVpnRetryDelaySeconds() * 1000L
        val excluded = app.configRepository.getExcludedApps()
        var lastError: Throwable? = null

        vpnConnectInProgress = true
        try {
            app.diagnosticLogger.i(
                CAT_VPN,
                "VPN connect starting maxAttempts=$maxAttempts delaySec=${delayMs / 1000} " +
                    "excludedApps=${excluded.size} " +
                    "reason=${if (!snap.wifiConnected) "no_wifi" else "untrusted_wifi"} " +
                    "config ${DiagnosticSupport.configFingerprint(config)}"
            )

            for (attempt in 1..maxAttempts) {
                // Policy may have flipped to trusted Wi‑Fi (e.g. race with other collectors)
                if (!policyWantsVpnUp) {
                    app.diagnosticLogger.i(
                        CAT_VPN,
                        "VPN connect aborted — policy no longer wants VPN up"
                    )
                    return
                }

                val progressMsg = if (attempt == 1) {
                    getString(R.string.vpn_connecting)
                } else {
                    getString(R.string.vpn_retry_attempt, attempt, maxAttempts)
                }
                _uiState.value = _uiState.value.copy(
                    vpnActive = false,
                    reconnectChoicePending = false,
                    message = progressMsg
                )
                updateNotification(progressMsg)
                Log.i(TAG, "VPN connect attempt $attempt/$maxAttempts (delay=${delayMs}ms)")
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "tunnel connect attempt=$attempt/$maxAttempts"
                )

                val result = app.wireGuardManager.setTunnelUp(config, excluded)
                if (result.isSuccess) {
                    val msg = successMessage(snap)
                    _uiState.value = _uiState.value.copy(
                        vpnActive = true,
                        reconnectChoicePending = false,
                        message = msg
                    )
                    updateNotification(msg)
                    startStatsPolling()
                    notifyUiSurfaces()
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
                    _uiState.value = _uiState.value.copy(
                        vpnActive = false,
                        reconnectChoicePending = false,
                        message = waitMsg
                    )
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
        } finally {
            vpnConnectInProgress = false
        }

        val errText = WireGuardManager.formatError(lastError)
        val detailMsg = getString(R.string.vpn_reestablish_failed_detail, maxAttempts, errText)
        presentReconnectChoice(
            shortMessage = getString(R.string.vpn_reestablish_failed),
            detailError = detailMsg,
            attempts = maxAttempts
        )
        Log.e(TAG, detailMsg)
        app.diagnosticLogger.logException(
            CAT_VPN,
            "tunnel connect GAVE UP after attempts vpn=off " +
                "error=$errText — awaiting user Retry/Stop",
            lastError
        )
    }

    /**
     * All connect attempts failed: keep monitoring on, surface Retry / Stop monitoring.
     */
    private fun presentReconnectChoice(
        shortMessage: String,
        detailError: String,
        attempts: Int
    ) {
        _uiState.value = _uiState.value.copy(
            vpnActive = false,
            reconnectChoicePending = true,
            message = shortMessage
        )
        // Force notification rebuild (actions differ from normal monitoring)
        lastNotificationSignature = null
        updateNotification(shortMessage, reconnectChoice = true)
        stopStatsPolling()
        notifyUiSurfaces()
        // Open main UI so the user sees Retry / Stop when the app is usable
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(MainActivity.EXTRA_PROMPT_RECONNECT_CHOICE, true)
            putExtra(MainActivity.EXTRA_RECONNECT_DETAIL, detailError)
        }
        runCatching { startActivity(open) }
            .onFailure { e ->
                Log.w(TAG, "Could not open reconnect choice UI: ${e.message}")
                app.diagnosticLogger.w(
                    CAT_VPN,
                    "reconnect choice activity launch failed: ${e.message}"
                )
            }
        app.diagnosticLogger.i(
            CAT_VPN,
            "reconnect choice presented attempts=$attempts message=$shortMessage"
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
        stopStatsPolling()
        lastPolicyKey = null
        monitoringStartedAtElapsed = 0L
        policyWantsVpnUp = false
        vpnConnectInProgress = false
        lastVpnPolicySnap = null
        lastNotificationSignature = null
        val wasUp = app.wireGuardManager.isUp
        val downResult = app.wireGuardManager.setTunnelDown()
        app.configRepository.setMonitoringEnabled(false)
        val snap = wifiMonitor.snapshot(app.configRepository.getTrustedWifiSsids())
        val stoppedMsg = getString(R.string.notification_monitoring_stopped)
        _uiState.value = MonitorUiState(
            monitoring = false,
            wifiConnected = snap.wifiConnected,
            onTrustedWifi = snap.onTrustedWifi,
            currentSsid = snap.ssid,
            vpnActive = false,
            reconnectChoicePending = false,
            message = stoppedMsg
        )
        // Ongoing FGS notification may still be required until stopForeground/stopSelf
        updateNotification(stoppedMsg, reconnectChoice = false)
        notifyUiSurfaces()
        Log.i(TAG, "Monitoring stopped")
        app.diagnosticLogger.i(
            CAT_MONITOR,
            "monitoring stopped vpnWasUp=$wasUp " +
                "vpnDown=${if (downResult.isSuccess) "ok" else "fail"} " +
                "wifi=${if (snap.wifiConnected) "up" else "down"} " +
                "ssid=${snap.ssid ?: "none"}"
        )
    }

    /**
     * While the tunnel is up (foreground notification is showing), poll WireGuard
     * transfer counters so [WireGuardManager.transferStats] and home widgets stay current.
     * MainActivity also polls while visible for snappier speed updates.
     */
    private fun startStatsPolling() {
        if (statsPollJob?.isActive == true) return
        statsPollJob = lifecycleScope.launch {
            // Immediate sample so widgets/UI are not empty for a full interval.
            app.wireGuardManager.refreshTransferStats()
            StatusWidgets.updateAll(this@WifiMonitorService)
            while (true) {
                delay(STATS_POLL_MS)
                if (!app.wireGuardManager.isUp) break
                app.wireGuardManager.refreshTransferStats()
                // Push-only widgets: refresh totals / handshake while VPN stays up.
                StatusWidgets.updateAll(this@WifiMonitorService)
            }
            app.wireGuardManager.clearTransferStats()
            StatusWidgets.updateAllSoon(this@WifiMonitorService)
        }
    }

    private fun stopStatsPolling() {
        statsPollJob?.cancel()
        statsPollJob = null
        app.wireGuardManager.clearTransferStats()
    }

    private fun startAsForeground(content: String) {
        lastNotificationSignature = "n|$content"
        val notification = buildNotification(content, reconnectChoice = false)
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

    /** Last posted notification signature (text + mode) — skip identical updates. */
    private var lastNotificationSignature: String? = null

    private fun updateNotification(content: String, reconnectChoice: Boolean = false) {
        val signature = "${if (reconnectChoice) "r" else "n"}|$content"
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(WifiVpnApp.NOTIFICATION_ID, buildNotification(content, reconnectChoice))
    }

    /** Keep QS tile and home-screen widgets in sync with [uiState]. */
    private fun notifyUiSurfaces() {
        MonitorTileService.requestUpdate(this)
        // updateAllSoon: main-thread + delayed reinforce (launcher RemoteViews can reorder)
        StatusWidgets.updateAllSoon(this)
    }

    private fun buildNotification(content: String, reconnectChoice: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                if (reconnectChoice) {
                    putExtra(MainActivity.EXTRA_PROMPT_RECONNECT_CHOICE, true)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, WifiVpnApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(
                if (reconnectChoice) {
                    getString(R.string.dialog_vpn_reconnect_title)
                } else {
                    getString(R.string.notification_title)
                }
            )
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(!reconnectChoice)
            .setCategory(
                if (reconnectChoice) {
                    NotificationCompat.CATEGORY_ERROR
                } else {
                    NotificationCompat.CATEGORY_SERVICE
                }
            )

        if (reconnectChoice) {
            val retryPi = PendingIntent.getService(
                this,
                2,
                Intent(this, WifiMonitorService::class.java).setAction(ACTION_RETRY_VPN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopConfirmPi = PendingIntent.getService(
                this,
                3,
                Intent(this, WifiMonitorService::class.java).setAction(ACTION_PROMPT_STOP_INSECURE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder
                .addAction(0, getString(R.string.btn_retry), retryPi)
                .addAction(0, getString(R.string.btn_stop_monitoring), stopConfirmPi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        } else {
            val stopIntent = PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.btn_stop_monitoring), stopIntent)
        }
        return builder.build()
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        stopStatsPolling()
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
        /**
         * True after connect attempts were exhausted while policy still wants VPN.
         * UI shows Retry / Stop monitoring (with insecure-connection warning).
         */
        val reconnectChoicePending: Boolean = false,
        val message: String = ""
    )

    companion object {
        private const val TAG = "WifiMonitorService"
        private const val CAT_MONITOR = "MONITOR"
        private const val CAT_NETWORK = "NETWORK"
        private const val CAT_VPN = "VPN"
        const val ACTION_STOP = "com.wifivpn.app.action.STOP_MONITORING"
        const val ACTION_RETRY_VPN = "com.wifivpn.app.action.RETRY_VPN"
        /** Opens the insecure-stop confirmation UI (does not stop immediately). */
        const val ACTION_PROMPT_STOP_INSECURE = "com.wifivpn.app.action.PROMPT_STOP_INSECURE"

        @Volatile
        var instance: WifiMonitorService? = null
            private set

        private val _uiState = MutableStateFlow(MonitorUiState())
        val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

        const val EXTRA_START_SOURCE = "com.wifivpn.app.extra.START_SOURCE"
        const val SOURCE_UI = "ui"
        const val SOURCE_TILE = "tile"
        const val SOURCE_WIDGET = "widget"
        const val SOURCE_BOOT = "boot"
        /** Restored after app update (MY_PACKAGE_REPLACED) when monitoring was on. */
        const val SOURCE_UPDATE = "update"
        const val SOURCE_UNKNOWN = "unknown"

        /**
         * After process start (especially boot), Wi‑Fi may take several seconds to associate
         * and expose SSID. Hold off VPN-on while identity is still unknown during this window.
         */
        private const val WIFI_SETTLE_GRACE_MS = 15_000L

        /** Poll interval while waiting for Wi‑Fi association / SSID during [WIFI_SETTLE_GRACE_MS]. */
        private const val WIFI_SETTLE_POLL_MS = 500L

        /** Transfer stats poll interval while the VPN notification / tunnel is active. */
        private const val STATS_POLL_MS = 2_000L

        fun startIntent(context: Context, source: String = SOURCE_UNKNOWN): Intent =
            Intent(context, WifiMonitorService::class.java).putExtra(EXTRA_START_SOURCE, source)

        fun stopIntent(context: Context, source: String = SOURCE_UNKNOWN): Intent =
            Intent(context, WifiMonitorService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_START_SOURCE, source)

        fun retryVpnIntent(context: Context): Intent =
            Intent(context, WifiMonitorService::class.java).setAction(ACTION_RETRY_VPN)

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

        fun retryVpn(context: Context) {
            context.startService(retryVpnIntent(context))
        }
    }
}
