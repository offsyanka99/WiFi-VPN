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
import com.wifivpn.app.log.LogRedactor
import com.wifivpn.app.network.LocalNetwork
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.permission.BackgroundLocation
import com.wifivpn.app.tile.MonitorTileService
import com.wifivpn.app.util.InternalIntentAuth
import com.wifivpn.app.util.InternalIntentAuth.putInternalAuth
import com.wifivpn.app.vpn.TunnelTransferStats
import com.wifivpn.app.vpn.WireGuardManager
import com.wifivpn.app.widget.StatusWidgets
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

/**
 * Foreground service: watches trusted Wi‑Fi SSIDs and toggles WireGuard.
 *
 * Policy:
 *  - Connected to a **trusted** SSID → VPN off
 *  - Any other Wi‑Fi, or no Wi‑Fi → VPN on
 *
 * VPN bring-up retries use Configuration → VPN connection retries.
 * While the tunnel is up, peer liveness is checked from the latest WireGuard
 * handshake; a stale peer triggers tear-down and reconnect with the same retry settings.
 */
class WifiMonitorService : LifecycleService() {

    private val app get() = application as WifiVpnApp
    private val wifiMonitor: WifiConnectivityMonitor get() = app.wifiMonitor
    private var monitorJob: Job? = null
    /** Polls WireGuard transfer stats while the tunnel is up (feeds UI flow + widgets). */
    private var statsPollJob: Job? = null
    /**
     * While Wi‑Fi is up but SSID is still unreadable, keep re-checking so a late
     * trusted name (common after reboot) can turn VPN off.
     */
    private var unknownSsidWatchJob: Job? = null
    /** How monitoring was started (boot needs a longer SSID resolve window). */
    private var startSource: String = SOURCE_UNKNOWN
    /** [SystemClock.elapsedRealtime] when the current tunnel session started. */
    private var tunnelUpAtElapsedMs: Long = 0L
    /** [SystemClock.elapsedRealtime] of the last peer-handshake liveness check. */
    private var lastPeerHealthCheckAtElapsedMs: Long = 0L
    /** True while a dead-peer reconnect is in progress (avoids overlapping reconnects). */
    private var peerReconnectInFlight: Boolean = false
    /**
     * Whether this service currently has location access. Without it the platform hides the
     * SSID, BSSID and network id, so trusted Wi‑Fi cannot be recognised.
     */
    @Volatile
    private var locationAccess: Boolean = false
    /** Serializes all VPN policy decisions (Wi‑Fi flow + trusted-list updates). */
    private val policyMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
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
        startAsForeground(getString(R.string.notification_waiting), source)

        if (monitorJob?.isActive == true) {
            app.diagnosticLogger.i(
                CAT_MONITOR,
                "monitoring start ignored (already active) source=$source"
            )
            // Still refresh tile/widgets (e.g. user tapped Start while already on).
            notifyUiSurfaces()
            return
        }

        startSource = source
        _uiState.value = _uiState.value.copy(
            monitoring = true,
            message = getString(R.string.notification_monitoring)
        )
        notifyUiSurfaces()
        app.diagnosticLogger.i(
            CAT_MONITOR,
            "monitoring started source=$source " +
                "perms: ${DiagnosticSupport.permissionSnapshot(this)}"
        )

        monitorJob = lifecycleScope.launch {
            app.configRepository.setMonitoringEnabled(true)
            // Load trusted list + association memory before any VPN decision.
            // Association memory (networkId/BSSID → SSID) survives reboot when the platform
            // redacts SSID until the UI is opened.
            val initialTrusted = app.configRepository.getTrustedWifiSsids()
            val associations = app.configRepository.getTrustedWifiAssociations()
            wifiMonitor.setTrustedSsids(initialTrusted)
            wifiMonitor.setTrustedAssociations(associations)
            app.diagnosticLogger.i(
                CAT_MONITOR,
                "trusted_ssids=${initialTrusted.size} assoc_memory=${associations.size} " +
                    "keys=${
                        associations.keys.joinToString(",") {
                            LogRedactor.assoc(this@WifiMonitorService, it)
                        }.ifEmpty { "none" }
                    }"
            )
            DiagnosticSupport.logSupportSummary(app, "monitor_start source=$source")

            // After phone reboot only: wait for Wi‑Fi / cellular to settle before policy.
            // Manual / tile / widget starts skip this delay.
            if (source == SOURCE_BOOT) {
                app.diagnosticLogger.i(
                    CAT_MONITOR,
                    "boot network settle wait ${BOOT_NETWORK_SETTLE_MS}ms " +
                        "(Wi‑Fi / cellular check deferred)"
                )
                val waiting = getString(R.string.notification_waiting)
                _uiState.value = _uiState.value.copy(message = waiting)
                updateNotification(waiting)
                delay(BOOT_NETWORK_SETTLE_MS)
            }

            // Keep trusted SSID set in the monitor. Skip the first DataStore emission —
            // it matches [initialTrusted] and would race a second concurrent VPN bring-up.
            launch {
                var firstEmission = true
                app.configRepository.trustedWifiSsids.collect { ssids ->
                    wifiMonitor.setTrustedSsids(ssids)
                    if (firstEmission) {
                        firstEmission = false
                        return@collect
                    }
                    applyWifiDecision(wifiMonitor.snapshot(ssids))
                }
            }

            // CRITICAL: Register FLAG_INCLUDE_LOCATION_INFO NetworkCallbacks *before* any
            // policy decision. On Android 12+, getNetworkCapabilities() redacts SSID;
            // only callback payloads (and our cache from them) expose the real name.
            // Previously we called applyWifiDecision() first and blocked in SSID wait,
            // so callbacks never registered until stop/start from the UI — matching the
            // "Wi‑Fi other / VPN on until I reopen the app" bug.
            //
            // First flow emission drives the initial policy (includes boot SSID resolve).
            wifiMonitor.wifiStatusFlow().collectLatest { snap ->
                applyWifiDecision(snap)
            }
        }
    }

    /** Last policy key we acted on — skip redundant tunnel toggles / log lines. */
    private var lastPolicyKey: String? = null

    /**
     * Entry point for all policy decisions. Serialized so the Wi‑Fi flow and trusted-list
     * collector cannot interleave bring-up / tear-down / SSID waits.
     */
    private suspend fun applyWifiDecision(snap: WifiConnectivityMonitor.WifiSnapshot) {
        policyMutex.withLock {
            applyWifiDecisionLocked(snap)
        }
    }

    private suspend fun applyWifiDecisionLocked(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val wantVpnOn = !snap.onTrustedWifi
        // Do not include transports: VPN up/down must not re-key policy (cancels connect).
        val policyKey =
            "${snap.wifiConnected}|${snap.ssid}|${snap.onTrustedWifi}|$wantVpnOn"
        val tunnelMatches =
            (wantVpnOn && app.wireGuardManager.isUp) ||
                (!wantVpnOn && !app.wireGuardManager.isUp)
        if (policyKey == lastPolicyKey && tunnelMatches) {
            // Soft UI refresh only
            val prev = _uiState.value
            val next = prev.copy(
                wifiConnected = snap.wifiConnected,
                onTrustedWifi = snap.onTrustedWifi,
                currentSsid = snap.ssid,
                vpnActive = app.wireGuardManager.isUp && hasLivePeerHandshake()
            )
            _uiState.value = next
            // Keep transfer polling alive if the tunnel is already up.
            if (app.wireGuardManager.isUp) {
                startStatsPolling()
            } else {
                stopStatsPolling()
            }
            // VPN may already be on with unknown SSID (post-boot); keep watching for the name.
            maybeStartUnknownSsidWatch(snap)
            if (snap.onTrustedWifi) rememberTrustedAssociationIfLive(snap)
            if (prev != next) notifyUiSurfaces()
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
            stopUnknownSsidWatch()
            // Persist association → SSID so next boot works while SSID is still redacted.
            rememberTrustedAssociationIfLive(snap)
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
                        "VPN off (trusted Wi‑Fi) result=success wasUp=$wasUp " +
                            "match=${snap.trustedMatch} ssid=${LogRedactor.ssid(this, snap.ssid)}"
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
                    "VPN already off on trusted Wi‑Fi ssid=${LogRedactor.ssid(this, snap.ssid)} " +
                        "match=${snap.trustedMatch}"
                )
            }
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            stopStatsPolling()
            notifyUiSurfaces()
        } else {
            // Peer auto-reconnect owns the tunnel lifecycle — avoid a second concurrent
            // bring-up when tear-down fires a network callback.
            if (peerReconnectInFlight) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "VPN policy deferred — peer reconnect in progress"
                )
                return
            }
            // After update/boot, SSID often lags while still on trusted Wi‑Fi.
            // Poll until the name is readable before treating Wi‑Fi as untrusted.
            val effective = resolveSsidBeforeVpnUp(snap)
            if (effective.onTrustedWifi) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "SSID resolved as trusted after wait — VPN stays off"
                )
                lastPolicyKey = null
                // Already under [policyMutex] — recurse without re-locking.
                applyWifiDecisionLocked(effective)
                return
            }
            // Refresh UI fields if a non-trusted SSID appeared during the wait.
            _uiState.value = _uiState.value.copy(
                wifiConnected = effective.wifiConnected,
                onTrustedWifi = effective.onTrustedWifi,
                currentSsid = effective.ssid
            )
            // Live untrusted SSID still worth remembering? No — only trusted.
            if (effective.onTrustedWifi) {
                rememberTrustedAssociationIfLive(effective)
            }
            bringVpnUpWithRetry(effective)
            // If the name is still missing, keep watching — platform may expose SSID later
            // without a connectivity callback (common after BOOT_COMPLETED).
            maybeStartUnknownSsidWatch(effective)
        }
    }

    /**
     * When Wi‑Fi is associated but SSID is still redacted/unknown, poll for the name
     * before treating the network as untrusted.
     *
     * After reboot/app-update Android often withholds SSID for many seconds, so the wait
     * is longer for those sources. It is still **bounded**: on timeout we fail closed and
     * let the caller bring the VPN up, because an unidentifiable network must be assumed
     * hostile. [maybeStartUnknownSsidWatch] keeps looking afterwards and drops the tunnel
     * as soon as a trusted name appears.
     */
    private suspend fun resolveSsidBeforeVpnUp(
        snap: WifiConnectivityMonitor.WifiSnapshot
    ): WifiConnectivityMonitor.WifiSnapshot {
        if (!snap.wifiConnected ||
            snap.ssid != null ||
            !snap.hasSsidPermission ||
            app.wireGuardManager.isUp
        ) {
            return snap
        }

        val afterRestart = startSource == SOURCE_BOOT || startSource == SOURCE_UPDATE
        val maxWaitMs = if (afterRestart) SSID_RESOLVE_BOOT_MAX_MS else SSID_RESOLVE_MAX_MS
        val resolving = getString(
            if (locationAccess) {
                R.string.notification_resolving_ssid
            } else {
                R.string.notification_resolving_ssid_no_location
            }
        )
        _uiState.value = _uiState.value.copy(
            wifiConnected = true,
            onTrustedWifi = false,
            currentSsid = null,
            vpnActive = false,
            message = resolving
        )
        updateNotification(resolving)
        notifyUiSurfaces()

        app.diagnosticLogger.i(
            CAT_VPN,
            "defer VPN up — Wi‑Fi up but SSID unknown " +
                "(poll ${SSID_RESOLVE_POLL_MS}ms up to ${maxWaitMs}ms source=$startSource)"
        )

        var elapsed = 0L
        var current = snap
        while (elapsed < maxWaitMs) {
            delay(SSID_RESOLVE_POLL_MS)
            elapsed += SSID_RESOLVE_POLL_MS
            current = wifiMonitor.snapshot(wifiMonitor.getTrustedSsids())
            if (!current.wifiConnected) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "SSID resolve aborted — Wi‑Fi down after ${elapsed}ms"
                )
                return current
            }
            if (current.ssid != null) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "SSID resolved after ${elapsed}ms ssid=${LogRedactor.ssid(this, current.ssid)} " +
                        "trusted=${current.onTrustedWifi} from_cache=${current.ssidFromCache}"
                )
                return current
            }
            if (elapsed % 15_000L == 0L) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "SSID still unknown after ${elapsed}ms — still waiting"
                )
                // Keep UI/widgets on the resolving message.
                updateNotification(resolving)
                notifyUiSurfaces()
            }
        }
        app.diagnosticLogger.w(
            CAT_VPN,
            "SSID still unknown after ${maxWaitMs}ms — failing closed, treating as untrusted"
        )
        return current
    }

    /**
     * Continues sampling while Wi‑Fi is up and SSID is unreadable so a late trusted
     * name can turn the VPN off (BOOT_COMPLETED often never re-fires capabilities).
     */
    private fun maybeStartUnknownSsidWatch(snap: WifiConnectivityMonitor.WifiSnapshot) {
        if (!snap.wifiConnected || snap.ssid != null || !snap.hasSsidPermission) {
            stopUnknownSsidWatch()
            return
        }
        if (unknownSsidWatchJob?.isActive == true) return
        unknownSsidWatchJob = lifecycleScope.launch {
            app.diagnosticLogger.i(
                CAT_VPN,
                "SSID still unknown after policy — watching every " +
                    "${SSID_UNKNOWN_WATCH_MS}ms until resolved or Wi‑Fi drops"
            )
            while (true) {
                delay(SSID_UNKNOWN_WATCH_MS)
                if (!_uiState.value.monitoring) break
                val latest = wifiMonitor.snapshot(wifiMonitor.getTrustedSsids())
                if (!latest.wifiConnected) {
                    app.diagnosticLogger.i(CAT_VPN, "SSID watch end — Wi‑Fi down")
                    break
                }
                if (latest.ssid != null) {
                    app.diagnosticLogger.i(
                        CAT_VPN,
                        "SSID watch resolved ssid=" +
                            LogRedactor.ssid(this@WifiMonitorService, latest.ssid) +
                            " trusted=${latest.onTrustedWifi} — re-applying policy"
                    )
                    applyWifiDecision(latest)
                    break
                }
            }
        }
    }

    private fun stopUnknownSsidWatch() {
        unknownSsidWatchJob?.cancel()
        unknownSsidWatchJob = null
    }

    /**
     * When we have a live (not association-memory) trusted SSID, store every identity key
     * the platform exposes (BSSID and networkId) so reboot policy works while Android
     * still redacts SSID in the background.
     */
    private fun rememberTrustedAssociationIfLive(snap: WifiConnectivityMonitor.WifiSnapshot) {
        if (!snap.onTrustedWifi) return
        val ssid = snap.ssid ?: return
        // Only persist when we actually read the name from the platform (not our memory).
        if (snap.trustedMatch == WifiConnectivityMonitor.MATCH_ASSOC_MEMORY) return
        val assocKeys = wifiMonitor.currentAssociationKeys()
        if (assocKeys.isEmpty()) return
        lifecycleScope.launch {
            app.configRepository.rememberTrustedWifiAssociations(assocKeys, ssid)
            val updated = app.configRepository.getTrustedWifiAssociations()
            wifiMonitor.setTrustedAssociations(updated)
            app.diagnosticLogger.i(
                CAT_VPN,
                "remembered trusted association keys=" +
                    assocKeys.joinToString(",") { LogRedactor.assoc(this@WifiMonitorService, it) } +
                    " ssid=${LogRedactor.ssid(this@WifiMonitorService, ssid)} " +
                    "mapSize=${updated.size}"
            )
        }
    }

    private fun logNetworkAndDecision(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val wifiLabel = when {
            !snap.wifiConnected -> "disconnected"
            snap.onTrustedWifi -> "trusted"
            else -> "other"
        }
        val ssidPart = "ssid=${LogRedactor.ssid(this, snap.ssid)}"
        val decision = if (snap.onTrustedWifi) "VPN_OFF" else "VPN_ON"
        val assocKey = LogRedactor.assoc(this, wifiMonitor.currentAssociationKey())
        val memSize = wifiMonitor.getTrustedAssociations().size
        app.diagnosticLogger.i(
            CAT_NETWORK,
            "wifi=$wifiLabel $ssidPart trusted_match=${snap.trustedMatch} " +
                "ssid_redacted=${snap.ssidRedacted} ssid_from_cache=${snap.ssidFromCache} " +
                "ssid_perm=${if (snap.hasSsidPermission) "ok" else "no"} " +
                "assoc=$assocKey mem=$memSize " +
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
     *
     * Tunnel interface UP alone is not success — a live peer handshake is required.
     * If the server is down, each attempt waits for a handshake, then tears down and retries.
     */
    private suspend fun bringVpnUpWithRetry(snap: WifiConnectivityMonitor.WifiSnapshot) {
        val config = app.configRepository.getWireGuardConfig()
        if (config.isBlank()) {
            val msg = getString(R.string.msg_config_empty)
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            stopStatsPolling()
            notifyUiSurfaces()
            app.diagnosticLogger.w(CAT_VPN, "VPN on skipped — WireGuard config empty")
            return
        }

        // Tunnel interface up with a live handshake — healthy, refresh status only.
        if (app.wireGuardManager.isUp && hasLivePeerHandshake()) {
            if (tunnelUpAtElapsedMs == 0L) {
                tunnelUpAtElapsedMs = SystemClock.elapsedRealtime()
            }
            val msg = successMessage(snap)
            _uiState.value = _uiState.value.copy(vpnActive = true, message = msg)
            updateNotification(msg)
            startStatsPolling()
            notifyUiSurfaces()
            app.diagnosticLogger.i(
                CAT_VPN,
                "VPN already on with live handshake — no reconnect " +
                    "(wifi=${if (snap.wifiConnected) "up" else "down"} " +
                    "ssid=${LogRedactor.ssid(this, snap.ssid)} " +
                    "cellular=${if (snap.cellularConnected) "up" else "down"})"
            )
            return
        }

        // Interface up but peer dead / never handshaked — recycle before the retry loop.
        if (app.wireGuardManager.isUp) {
            app.diagnosticLogger.w(
                CAT_VPN,
                "VPN interface up but peer not live — tearing down before retry loop"
            )
            app.wireGuardManager.setTunnelDown()
            app.wireGuardManager.clearTransferStats()
            stopStatsPolling()
        }

        val maxAttempts = app.configRepository.getVpnRetryAttempts()
        val delayMs = app.configRepository.getVpnRetryDelaySeconds() * 1000L
        val excluded = app.configRepository.getExcludedApps()
        var lastError: Throwable? = null

        app.diagnosticLogger.i(
            CAT_VPN,
            "VPN connect starting maxAttempts=$maxAttempts delaySec=${delayMs / 1000} " +
                "handshakeWaitSec=${PEER_HANDSHAKE_WAIT_MS / 1000} " +
                "excludedApps=${excluded.size} " +
                "reason=${if (!snap.wifiConnected) "no_wifi" else "untrusted_wifi"} " +
                "config ${DiagnosticSupport.configFingerprint(this, config)}"
        )

        for (attempt in 1..maxAttempts) {
            val progressMsg = if (attempt == 1) {
                getString(R.string.vpn_connecting)
            } else {
                getString(R.string.vpn_retry_attempt, attempt, maxAttempts)
            }
            // Push connecting / retry text to main UI (uiState), notification, and widgets.
            _uiState.value = _uiState.value.copy(vpnActive = false, message = progressMsg)
            updateNotification(progressMsg)
            notifyUiSurfaces()
            Log.i(TAG, "VPN connect attempt $attempt/$maxAttempts (delay=${delayMs}ms)")
            app.diagnosticLogger.i(
                CAT_VPN,
                "tunnel connect attempt=$attempt/$maxAttempts"
            )

            val result = app.wireGuardManager.setTunnelUp(config, excluded)
            if (result.isSuccess) {
                // Provisional session clock until handshake succeeds (or attempt fails).
                tunnelUpAtElapsedMs = SystemClock.elapsedRealtime()
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "tunnel UP attempt=$attempt/$maxAttempts — waiting for peer handshake " +
                        "(up to ${PEER_HANDSHAKE_WAIT_MS / 1000}s)"
                )
                if (awaitPeerHandshake()) {
                    tunnelUpAtElapsedMs = SystemClock.elapsedRealtime()
                    lastPeerHealthCheckAtElapsedMs = tunnelUpAtElapsedMs
                    val msg = successMessage(snap)
                    _uiState.value = _uiState.value.copy(vpnActive = true, message = msg)
                    updateNotification(msg)
                    startStatsPolling()
                    notifyUiSurfaces()
                    Log.i(TAG, "VPN up with handshake on attempt $attempt")
                    app.diagnosticLogger.i(
                        CAT_VPN,
                        "tunnel connect SUCCESS attempt=$attempt/$maxAttempts " +
                            "vpn=on peer=handshake_ok"
                    )
                    return
                }
                // Server down / peer silent: interface was UP but no handshake.
                lastError = IllegalStateException(getString(R.string.vpn_handshake_timeout))
                app.diagnosticLogger.w(
                    CAT_VPN,
                    "tunnel connect FAILED attempt=$attempt/$maxAttempts " +
                        "error=no peer handshake within ${PEER_HANDSHAKE_WAIT_MS / 1000}s"
                )
                app.wireGuardManager.setTunnelDown()
                app.wireGuardManager.clearTransferStats()
            } else {
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
                notifyUiSurfaces()
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

        val finalMsg = if (
            LocalNetwork.blocksConfig(this, app.wireGuardManager.parseConfig(config).getOrNull())
        ) {
            app.diagnosticLogger.w(
                CAT_VPN,
                "connect failures likely caused by missing ACCESS_LOCAL_NETWORK (LAN peer endpoint)"
            )
            getString(R.string.vpn_connect_failed_local_network)
        } else {
            getString(
                R.string.vpn_connect_failed,
                maxAttempts,
                WireGuardManager.formatError(lastError)
            )
        }
        _uiState.value = _uiState.value.copy(vpnActive = false, message = finalMsg)
        updateNotification(finalMsg)
        stopStatsPolling()
        notifyUiSurfaces()
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

    /**
     * True when the newest peer handshake is present and younger than the dead threshold.
     */
    private fun hasLivePeerHandshake(): Boolean {
        val stats = app.wireGuardManager.transferStats.value
            ?: app.wireGuardManager.refreshTransferStats()
        return hasLivePeerHandshake(stats)
    }

    private fun hasLivePeerHandshake(stats: TunnelTransferStats?): Boolean {
        val hs = stats?.latestHandshakeEpochMillis ?: 0L
        if (hs <= 0L) return false
        val age = (System.currentTimeMillis() - hs).coerceAtLeast(0L)
        return age < PEER_DEAD_HANDSHAKE_AGE_MS
    }

    /**
     * After tunnel interface is UP, poll until a peer handshake appears or [PEER_HANDSHAKE_WAIT_MS].
     * Updates UI/widgets with remaining wait so users see progress when the server is down.
     */
    private suspend fun awaitPeerHandshake(): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= PEER_HANDSHAKE_WAIT_MS) break
            if (!app.wireGuardManager.isUp) {
                app.diagnosticLogger.w(CAT_VPN, "handshake wait aborted — tunnel down")
                return false
            }
            val stats = app.wireGuardManager.refreshTransferStats()
            if (hasLivePeerHandshake(stats)) {
                val ageSec = handshakeAgeMs(stats) / 1000L
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "peer handshake OK ageSec=$ageSec afterWaitMs=$elapsed"
                )
                return true
            }
            val remainSec =
                ((PEER_HANDSHAKE_WAIT_MS - elapsed) / 1000L).toInt().coerceAtLeast(1)
            val msg = getString(R.string.vpn_waiting_handshake, remainSec)
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            notifyUiSurfaces()
            delay(PEER_HANDSHAKE_POLL_MS)
        }
        return false
    }

    /**
     * Age of the newest peer handshake in ms.
     * If WireGuard has never completed a handshake, uses time since this tunnel session started.
     */
    private fun handshakeAgeMs(stats: TunnelTransferStats?): Long {
        val hs = stats?.latestHandshakeEpochMillis ?: 0L
        if (hs > 0L) {
            return (System.currentTimeMillis() - hs).coerceAtLeast(0L)
        }
        if (tunnelUpAtElapsedMs <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - tunnelUpAtElapsedMs).coerceAtLeast(0L)
    }

    /**
     * Peer is considered dead when the tunnel has been up long enough and the latest
     * handshake is older than [PEER_DEAD_HANDSHAKE_AGE_MS] (or never completed).
     */
    private fun isPeerUnreachable(stats: TunnelTransferStats?): Boolean {
        if (!app.wireGuardManager.isUp) return false
        val upForMs = SystemClock.elapsedRealtime() - tunnelUpAtElapsedMs
        // Grace after bring-up: allow the post-connect handshake wait window first.
        if (upForMs < PEER_HANDSHAKE_WAIT_MS) return false
        return !hasLivePeerHandshake(stats)
    }

    private fun peerUnreachableMessage(stats: TunnelTransferStats?): String {
        val hs = stats?.latestHandshakeEpochMillis ?: 0L
        if (hs <= 0L) {
            return getString(R.string.vpn_peer_unreachable_never)
        }
        val ageMin = (handshakeAgeMs(stats) / 60_000L).toInt().coerceAtLeast(1)
        return getString(R.string.vpn_peer_unreachable, ageMin)
    }

    private fun peerHealthCheckDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPeerHealthCheckAtElapsedMs < PEER_HEALTH_CHECK_MS) return false
        lastPeerHealthCheckAtElapsedMs = now
        return true
    }

    /**
     * Tear down a tunnel whose peer looks dead and reconnect using Configuration
     * retry settings. Updates main UI, notification, and widgets with an explicit status.
     * Called from the stats poll loop; returns after reconnect attempt finishes.
     */
    private suspend fun reconnectForDeadPeer(stats: TunnelTransferStats?) {
        if (peerReconnectInFlight) return
        peerReconnectInFlight = true
        try {
            val ageMs = handshakeAgeMs(stats)
            val msg = peerUnreachableMessage(stats)
            Log.w(TAG, "Peer unreachable (handshakeAgeMs=$ageMs) — reconnecting")
            app.diagnosticLogger.w(
                CAT_VPN,
                "peer unreachable handshakeAgeSec=${ageMs / 1000} " +
                    "thresholdSec=${PEER_DEAD_HANDSHAKE_AGE_MS / 1000} — auto-reconnect"
            )

            // Detach this poll job so startStatsPolling can start a new one after UP.
            statsPollJob = null
            // Bring the tunnel down first so widgets do not keep showing transfer stats.
            app.wireGuardManager.setTunnelDown()
            app.wireGuardManager.clearTransferStats()

            // Explicit status on main UI / notification / widgets.
            _uiState.value = _uiState.value.copy(vpnActive = false, message = msg)
            updateNotification(msg)
            notifyUiSurfaces()

            val trusted = app.configRepository.getTrustedWifiSsids()
            val snap = wifiMonitor.snapshot(trusted)
            if (snap.onTrustedWifi) {
                app.diagnosticLogger.i(
                    CAT_VPN,
                    "peer reconnect aborted — now on trusted Wi‑Fi"
                )
                lastPolicyKey = null
                applyWifiDecision(snap)
                return
            }

            // Keep the peer-unreachable line visible briefly, then use normal connect progress
            // (with per-attempt handshake wait + configured retries).
            delay(PEER_UNREACHABLE_STATUS_HOLD_MS)
            lastPolicyKey = null
            bringVpnUpWithRetry(snap)
        } finally {
            peerReconnectInFlight = false
        }
    }

    private suspend fun stopMonitoringInternal() {
        monitorJob?.cancel()
        monitorJob = null
        stopUnknownSsidWatch()
        stopStatsPolling()
        peerReconnectInFlight = false
        lastPolicyKey = null
        lastNotificationContent = null
        startSource = SOURCE_UNKNOWN
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
            message = stoppedMsg
        )
        updateNotification(stoppedMsg)
        notifyUiSurfaces()
        Log.i(TAG, "Monitoring stopped")
        app.diagnosticLogger.i(
            CAT_MONITOR,
            "monitoring stopped vpnWasUp=$wasUp " +
                "vpnDown=${if (downResult.isSuccess) "ok" else "fail"} " +
                "wifi=${if (snap.wifiConnected) "up" else "down"} " +
                "ssid=${LogRedactor.ssid(this, snap.ssid)}"
        )
    }

    /**
     * While the tunnel is up (foreground notification is showing), poll WireGuard
     * transfer counters so [WireGuardManager.transferStats] and home widgets stay current.
     * Also periodically evaluates peer liveness from the latest handshake.
     * MainActivity also polls while visible for snappier speed updates.
     */
    private fun startStatsPolling() {
        if (statsPollJob?.isActive == true) return
        // Do not reset tunnelUpAtElapsedMs here — that is set only on successful connect /
        // live-handshake refresh so peer-dead grace is not extended by poll restarts.
        if (lastPeerHealthCheckAtElapsedMs == 0L) {
            lastPeerHealthCheckAtElapsedMs = SystemClock.elapsedRealtime()
        }
        statsPollJob = lifecycleScope.launch {
            // Immediate sample so widgets/UI are not empty for a full interval.
            app.wireGuardManager.refreshTransferStats()
            StatusWidgets.updateAll(this@WifiMonitorService)
            var exitedForPeerReconnect = false
            try {
                while (true) {
                    delay(STATS_POLL_MS)
                    if (!app.wireGuardManager.isUp) break
                    val stats = app.wireGuardManager.refreshTransferStats()
                    // Push-only widgets: refresh totals / handshake while VPN stays up.
                    StatusWidgets.updateAll(this@WifiMonitorService)

                    if (!peerReconnectInFlight &&
                        peerHealthCheckDue() &&
                        isPeerUnreachable(stats)
                    ) {
                        exitedForPeerReconnect = true
                        reconnectForDeadPeer(stats)
                        // reconnectForDeadPeer starts a new poll job on success; do not clear here.
                        break
                    }
                }
            } finally {
                // Peer reconnect already cleared stats and may have started a new poll job.
                if (!exitedForPeerReconnect) {
                    app.wireGuardManager.clearTransferStats()
                    StatusWidgets.updateAllSoon(this@WifiMonitorService)
                }
            }
        }
    }

    private fun stopStatsPolling() {
        statsPollJob?.cancel()
        statsPollJob = null
        tunnelUpAtElapsedMs = 0L
        lastPeerHealthCheckAtElapsedMs = 0L
        app.wireGuardManager.clearTransferStats()
    }

    private fun startAsForeground(content: String, source: String) {
        lastNotificationContent = content
        val notification = buildNotification(content)
        // location: SSID is location-sensitive; keeps reads working with screen off while
        // the monitor FGS is running (while-in-use location permission is enough).
        // specialUse: declared purpose of continuous Wi‑Fi / VPN policy monitoring (API 34+).
        // A location FGS started from the background (boot, update, system restart) only
        // gets location access with ACCESS_BACKGROUND_LOCATION.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(WifiVpnApp.NOTIFICATION_ID, notification)
            locationAccess = true
            logLocationAccess(source)
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
            // API 29–33 accept the type from the background but still withhold location.
            val fromBackground = source == SOURCE_BOOT ||
                source == SOURCE_UPDATE ||
                source == SOURCE_UNKNOWN
            locationAccess = !fromBackground || BackgroundLocation.isGranted(this)
        } catch (e: SecurityException) {
            Log.w(TAG, "FGS location type rejected, falling back to specialUse: ${e.message}")
            locationAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    ServiceCompat.startForeground(
                        this,
                        WifiVpnApp.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (e2: SecurityException) {
                    Log.e(TAG, "FGS specialUse also rejected", e2)
                    throw e2
                }
            } else {
                throw e
            }
        }
        logLocationAccess(source)
    }

    private fun logLocationAccess(source: String) {
        if (locationAccess) {
            app.diagnosticLogger.i(CAT_MONITOR, "location access=ok source=$source")
        } else {
            app.diagnosticLogger.w(
                CAT_MONITOR,
                "location access=none source=$source " +
                    "bgloc=${if (BackgroundLocation.isGranted(this)) "ok" else "no"} " +
                    "— Wi‑Fi name hidden until the app is opened"
            )
        }
    }

    /**
     * Re-requests the location FGS type while one of our activities is visible, so a monitor
     * started after boot keeps location access — and a readable SSID — after the user leaves.
     *
     * @return true when the service was running without location access before this call.
     */
    fun regainLocationAccessIfNeeded(): Boolean {
        if (locationAccess || monitorJob?.isActive != true) return false
        startAsForeground(
            lastNotificationContent ?: getString(R.string.notification_monitoring),
            SOURCE_UI
        )
        if (locationAccess) {
            lifecycleScope.launch {
                applyWifiDecision(wifiMonitor.snapshot(wifiMonitor.getTrustedSsids()))
            }
        }
        return true
    }

    /** Last posted notification text — skip identical updates (less binder noise). */
    private var lastNotificationContent: String? = null

    private fun updateNotification(content: String) {
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(WifiVpnApp.NOTIFICATION_ID, buildNotification(content))
    }

    /** Keep QS tile and home-screen widgets in sync with [uiState]. */
    private fun notifyUiSurfaces() {
        MonitorTileService.requestUpdate(this)
        // updateAllSoon: main-thread + delayed reinforce (launcher RemoteViews can reorder)
        StatusWidgets.updateAllSoon(this)
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Route Stop through MainActivity so an insecure-connection warning can be shown
        // when not on trusted Wi‑Fi with VPN up.
        val stopPi = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REQUEST_STOP_MONITORING, true)
                putExtra(MainActivity.EXTRA_START_SOURCE, SOURCE_UI)
                putInternalAuth(this@WifiMonitorService)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
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
            .addAction(0, getString(R.string.btn_stop_monitoring), stopPi)
            .build()
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
        const val SOURCE_WIDGET = "widget"
        const val SOURCE_BOOT = "boot"
        /** Restored after app update (MY_PACKAGE_REPLACED) when monitoring was on. */
        const val SOURCE_UPDATE = "update"
        const val SOURCE_UNKNOWN = "unknown"

        /** Poll interval while waiting for a redacted/unknown SSID to become readable. */
        private const val SSID_RESOLVE_POLL_MS = 1_000L

        /**
         * Max time to wait for SSID before treating Wi‑Fi as untrusted (manual/UI start).
         */
        private const val SSID_RESOLVE_MAX_MS = 15_000L

        /**
         * Longer cap after reboot / app update, where Android withholds SSID for much
         * longer. Bounded on purpose: waiting forever would leave traffic unprotected on
         * a network we cannot identify.
         */
        private const val SSID_RESOLVE_BOOT_MAX_MS = 90_000L

        /**
         * If VPN was brought up while SSID was still unknown, re-check this often
         * until the name appears or Wi‑Fi drops.
         */
        private const val SSID_UNKNOWN_WATCH_MS = 5_000L

        /**
         * After phone reboot ([SOURCE_BOOT] only): delay before the first Wi‑Fi / cellular
         * policy check so the stack can associate. Not applied when monitoring is already
         * started from the UI, tile, or widget.
         */
        private const val BOOT_NETWORK_SETTLE_MS = 5_000L

        /** Transfer stats poll interval while the VPN notification / tunnel is active. */
        private const val STATS_POLL_MS = 2_000L

        /**
         * How often to evaluate peer liveness from the latest WireGuard handshake
         * (within the 30–60 s range).
         */
        private const val PEER_HEALTH_CHECK_MS = 45_000L

        /**
         * After tunnel interface is UP, wait this long for a peer handshake before
         * counting the attempt as failed and retrying (server down).
         */
        private const val PEER_HANDSHAKE_WAIT_MS = 30_000L

        /** Poll interval while waiting for the first/renewed peer handshake. */
        private const val PEER_HANDSHAKE_POLL_MS = 2_000L

        /**
         * Handshake older than this → peer considered dead → auto-reconnect
         * (within the 180–300 s range; 4 minutes).
         */
        private const val PEER_DEAD_HANDSHAKE_AGE_MS = 240_000L

        /** Brief hold so UI/widgets show the peer-unreachable line before "Connecting…". */
        private const val PEER_UNREACHABLE_STATUS_HOLD_MS = 1_500L

        fun startIntent(context: Context, source: String = SOURCE_UNKNOWN): Intent =
            Intent(context, WifiMonitorService::class.java).putExtra(EXTRA_START_SOURCE, source)

        fun stopIntent(context: Context, source: String = SOURCE_UNKNOWN): Intent =
            Intent(context, WifiMonitorService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_START_SOURCE, source)

        fun start(context: Context, source: String = SOURCE_UNKNOWN) {
            context.startForegroundService(startIntent(context, source))
        }

        fun stop(context: Context, source: String = SOURCE_UNKNOWN) {
            context.startService(stopIntent(context, source))
        }
    }
}
