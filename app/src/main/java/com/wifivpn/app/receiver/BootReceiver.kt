package com.wifivpn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.permission.PermissionCheckWorker
import com.wifivpn.app.service.WifiMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts Wi‑Fi monitoring after reboot (auto-start) or restores it after app update
 * when monitoring was previously on.
 *
 * Also re-schedules the weekly permission check.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Credential-encrypted storage only — ignore locked-boot (receiver is not directBootAware)
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        PermissionCheckWorker.schedule(context)

        val app = context.applicationContext as? WifiVpnApp ?: return
        val pending = goAsync()
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                app.configRepository.migrateSecureConfigIfNeeded()

                val shouldStart = when (action) {
                    // Fresh boot: honor auto-start preference
                    Intent.ACTION_BOOT_COMPLETED ->
                        app.configRepository.isAutoStartEnabled()

                    // App update/replace: only restore if monitoring was active before update.
                    // Using auto-start here incorrectly turned VPN on for users who only
                    // enabled "auto-start after reboot" while currently on trusted Wi‑Fi.
                    Intent.ACTION_MY_PACKAGE_REPLACED ->
                        app.configRepository.isMonitoringEnabled()

                    else -> false
                }

                if (!shouldStart) {
                    Log.i(TAG, "Skip start after $action (policy off)")
                    app.diagnosticLogger.i(
                        CAT,
                        "boot action=$action skip start " +
                            "(boot_auto=${app.configRepository.isAutoStartEnabled()} " +
                            "was_monitoring=${app.configRepository.isMonitoringEnabled()})"
                    )
                    return@launch
                }

                val canStart = app.configRepository.canStartMonitoring()
                if (!canStart) {
                    Log.w(TAG, "Not configured (config/trusted Wi‑Fi) — skip start after $action")
                    app.diagnosticLogger.w(
                        CAT,
                        "boot action=$action configured=false — skip"
                    )
                    return@launch
                }

                val source = when (action) {
                    Intent.ACTION_MY_PACKAGE_REPLACED -> WifiMonitorService.SOURCE_UPDATE
                    else -> WifiMonitorService.SOURCE_BOOT
                }
                Log.i(TAG, "Starting WiFi monitor after $action source=$source")
                app.diagnosticLogger.i(
                    CAT,
                    "boot action=$action starting monitor source=$source"
                )
                WifiMonitorService.start(context, source)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CAT = "BOOT"
    }
}
