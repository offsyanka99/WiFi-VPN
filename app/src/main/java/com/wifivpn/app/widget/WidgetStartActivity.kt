package com.wifivpn.app.widget

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wifivpn.app.ConfigurationActivity
import com.wifivpn.app.MainActivity
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.service.WifiMonitorService
import com.wifivpn.app.tile.MonitorTileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible trampoline for home-widget **Start**.
 *
 * Location FGS must start from a foreground-eligible Activity (Android 14+).
 * We avoid flashing [MainActivity]: wait until monitoring is on, refresh widgets,
 * then finish back to the launcher.
 */
class WidgetStartActivity : AppCompatActivity() {

    private val app get() = application as WifiVpnApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                startMonitoringFromWidget()
            } catch (e: Exception) {
                Log.e(TAG, "Widget start failed", e)
                app.diagnosticLogger.logException("UI", "Widget start monitoring failed", e)
                openMainApp()
            } finally {
                if (!isFinishing) finish()
            }
        }
    }

    private suspend fun startMonitoringFromWidget() {
        val raw = app.configRepository.getWireGuardConfig()
        if (raw.isBlank()) {
            openMainApp()
            return
        }
        val parsed = app.wireGuardManager.parseConfig(raw)
        if (parsed.isFailure) {
            openMainApp()
            return
        }
        if (app.configRepository.getTrustedWifiSsids().isEmpty()) {
            openMainApp()
            return
        }

        val wifiMonitor = WifiConnectivityMonitor(this)
        if (!wifiMonitor.hasSsidPermission()) {
            openMainApp()
            return
        }
        if (app.wireGuardManager.prepareVpnPermission() != null) {
            startActivity(
                Intent(this, ConfigurationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }

        // Already running — just refresh surfaces
        if (WifiMonitorService.uiState.value.monitoring || WifiMonitorService.instance != null) {
            refreshSurfaces()
            return
        }

        app.diagnosticLogger.i(
            "UI",
            "start monitoring requested source=${WifiMonitorService.SOURCE_WIDGET}"
        )
        WifiMonitorService.start(this, WifiMonitorService.SOURCE_WIDGET)

        // Wait for service to publish monitoring=true (startForegroundService is async)
        val started = withTimeoutOrNull(START_WAIT_MS) {
            WifiMonitorService.uiState.first { it.monitoring }
            true
        } == true

        if (!started) {
            Log.w(TAG, "Timed out waiting for monitoring=true; refreshing anyway")
        }

        // Apply widget state only after the real status is known (avoids a late
        // "Start" RemoteViews overwriting a correct "Stop" update in the launcher).
        refreshSurfaces()
        // Launcher can apply AppWidget updates out of order — reinforce once more.
        delay(REINFORCE_DELAY_MS)
        refreshSurfaces()
    }

    private fun refreshSurfaces() {
        StatusWidgets.updateAll(this)
        MonitorTileService.requestUpdate(this)
    }

    private fun openMainApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    companion object {
        private const val TAG = "WidgetStartActivity"
        private const val START_WAIT_MS = 5_000L
        private const val REINFORCE_DELAY_MS = 350L

        fun intent(context: android.content.Context): Intent =
            Intent(context, WidgetStartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}
