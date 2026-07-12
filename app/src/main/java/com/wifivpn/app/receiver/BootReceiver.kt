package com.wifivpn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.permission.PermissionCheckWorker
import com.wifivpn.app.service.WifiMonitorService
import kotlinx.coroutines.runBlocking

/**
 * Starts Wi‑Fi monitoring after reboot when the user enabled Auto-start.
 * Also re-schedules the weekly permission check.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        PermissionCheckWorker.schedule(context)

        val app = context.applicationContext as? WifiVpnApp ?: return
        val autoStart = runBlocking { app.configRepository.isAutoStartEnabled() }
        if (!autoStart) {
            Log.i(TAG, "Auto-start off — skip ($action)")
            app.diagnosticLogger.i(CAT, "boot action=$action auto_start=off — skip")
            return
        }

        val canStart = runBlocking { app.configRepository.canStartMonitoring() }
        if (!canStart) {
            Log.w(TAG, "Not configured (config/trusted Wi‑Fi) — skip auto-start")
            app.diagnosticLogger.w(
                CAT,
                "boot action=$action auto_start=on but not configured — skip"
            )
            return
        }

        Log.i(TAG, "Auto-starting WiFi monitor after $action")
        app.diagnosticLogger.i(CAT, "boot action=$action auto-starting monitor")
        WifiMonitorService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CAT = "BOOT"
    }
}
