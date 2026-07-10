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
 * Add via Edit tiles in the system QS panel.
 */
class MonitorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? WifiVpnApp ?: return
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null

        if (running) {
            WifiMonitorService.stop(this)
            qsTile?.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                qsTile?.subtitle = getString(R.string.tile_subtitle_off)
            }
            qsTile?.updateTile()
            return
        }

        val canStart = runBlocking { app.configRepository.canStartMonitoring() }
        val needsVpn = app.wireGuardManager.prepareVpnPermission() != null
        if (!canStart || needsVpn) {
            openApp()
            return
        }

        WifiMonitorService.start(this)
        qsTile?.state = Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            qsTile?.subtitle = getString(R.string.tile_subtitle_on)
        }
        qsTile?.updateTile()
        Log.i(TAG, "Started monitoring from tile")
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
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
        val tile = qsTile ?: return
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null
        val app = applicationContext as? WifiVpnApp
        val canStart = app != null && runBlocking { app.configRepository.canStartMonitoring() }

        tile.label = getString(R.string.tile_label)
        if (!canStart) {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_need_setup)
            }
        } else if (running) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_on)
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_off)
            }
        }
        tile.updateTile()
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
    }
}
