package com.wifivpn.app.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.wifivpn.app.MainActivity
import com.wifivpn.app.R
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.service.WifiMonitorService
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile: toggle Wi‑Fi monitoring on/off.
 *
 * Label = tunnel (config file) name when loaded.
 * Subtitle = VPN on / VPN off (or setup hint).
 *
 * Starting monitoring is delegated to [MainActivity] so the location
 * foreground service can start from an eligible foreground context
 * (TileService is treated as background on Android 14+).
 */
class MonitorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? WifiVpnApp ?: return
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null

        if (running) {
            WifiMonitorService.stop(this)
            // VPN will drop as service stops; reflect immediately
            applyTileAppearance(
                running = false,
                canStart = true,
                vpnActive = false
            )
            return
        }

        val canStart = runBlocking { app.configRepository.canStartMonitoring() }
        val needsVpn = app.wireGuardManager.prepareVpnPermission() != null
        if (!canStart || needsVpn) {
            openApp(startMonitoring = false)
            applyTileAppearance(
                running = false,
                canStart = canStart && !needsVpn,
                vpnActive = false
            )
            return
        }

        // Must start FGS(location) from a foreground Activity — not from the tile.
        openApp(startMonitoring = true)
        Log.i(TAG, "Requested start monitoring via MainActivity (from tile)")
    }

    private fun openApp(startMonitoring: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (startMonitoring) {
                putExtra(MainActivity.EXTRA_START_MONITORING, true)
                putExtra(MainActivity.EXTRA_FROM_TILE, true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                if (startMonitoring) 1 else 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val app = applicationContext as? WifiVpnApp
        val state = WifiMonitorService.uiState.value
        val running = state.monitoring || WifiMonitorService.instance != null
        val canStart = app != null && runBlocking { app.configRepository.canStartMonitoring() }
        val vpnActive = running && (state.vpnActive || app?.wireGuardManager?.isUp == true)
        applyTileAppearance(running = running, canStart = canStart, vpnActive = vpnActive)
    }

    /**
     * Label = tunnel name; subtitle = VPN status (or setup).
     * Tile selected/active while monitoring is on.
     */
    private fun applyTileAppearance(running: Boolean, canStart: Boolean, vpnActive: Boolean) {
        val tile = qsTile ?: return
        val app = applicationContext as? WifiVpnApp
        val tunnelName = runBlocking {
            app?.configRepository?.getWireGuardConfigFileName().orEmpty()
        }.let { displayTunnelName(it) }

        val appLabel = getString(R.string.tile_label)
        tile.label = tunnelName ?: appLabel

        val vpnText = if (vpnActive) {
            getString(R.string.tile_vpn_on)
        } else {
            getString(R.string.tile_vpn_off)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !canStart && !running -> getString(R.string.tile_need_setup)
                else -> vpnText
            }
        }

        tile.contentDescription = buildString {
            append(appLabel)
            if (tunnelName != null) append(", ").append(tunnelName)
            append(", ").append(
                if (!canStart && !running) getString(R.string.tile_need_setup) else vpnText
            )
        }

        tile.state = when {
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        tile.updateTile()
        Log.d(
            TAG,
            "Tile updated label=${tile.label} subtitle=$vpnText running=$running vpnActive=$vpnActive"
        )
    }

    companion object {
        private const val TAG = "MonitorTileService"

        fun requestUpdate(context: Context) {
            val component = ComponentName(context, MonitorTileService::class.java)
            try {
                requestListeningState(context, component)
            } catch (e: Exception) {
                Log.w(TAG, "requestListeningState failed", e)
            }
        }

        /** File name without extension, or null if missing. */
        fun displayTunnelName(fileName: String): String? {
            val base = fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            if (base.isEmpty()) return null
            val noExt = base
                .removeSuffix(".conf")
                .removeSuffix(".CONF")
                .removeSuffix(".wg")
                .removeSuffix(".WG")
                .trim()
            return noExt.ifEmpty { null }
        }
    }
}
