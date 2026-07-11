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
 * Label / subtitle carry the tunnel (config file) name when loaded.
 * Note: on Android 16, **small circular** QS tiles often hide text — use a
 * **wide** tile in QS edit mode to see the label next to the icon.
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
            applyTileAppearance(running = false, canStart = true)
            return
        }

        val canStart = runBlocking { app.configRepository.canStartMonitoring() }
        val needsVpn = app.wireGuardManager.prepareVpnPermission() != null
        if (!canStart || needsVpn) {
            openApp()
            applyTileAppearance(running = false, canStart = canStart && !needsVpn)
            return
        }

        WifiMonitorService.start(this)
        applyTileAppearance(running = true, canStart = true)
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
        val app = applicationContext as? WifiVpnApp
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null
        val canStart = app != null && runBlocking { app.configRepository.canStartMonitoring() }
        applyTileAppearance(running = running, canStart = canStart)
    }

    /**
     * Sets label (text by the icon on wide tiles) and subtitle (second line).
     * Tunnel name comes from the loaded WireGuard config file name.
     */
    private fun applyTileAppearance(running: Boolean, canStart: Boolean) {
        val tile = qsTile ?: return
        val app = applicationContext as? WifiVpnApp
        val tunnelName = runBlocking {
            app?.configRepository?.getWireGuardConfigFileName().orEmpty()
        }.let { displayTunnelName(it) }

        val appLabel = getString(R.string.tile_label)

        // Primary text next to the icon (visible on classic / wide QS tiles)
        tile.label = when {
            tunnelName != null -> tunnelName
            else -> appLabel
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !canStart -> getString(R.string.tile_need_setup)
                tunnelName != null && running ->
                    getString(R.string.tile_subtitle_on)
                tunnelName != null && !running ->
                    getString(R.string.tile_subtitle_off)
                running -> getString(R.string.tile_subtitle_on)
                else -> getString(R.string.tile_subtitle_off)
            }
        }

        // Content description for accessibility (always includes name + state)
        val stateText = when {
            !canStart -> getString(R.string.tile_need_setup)
            running -> getString(R.string.tile_subtitle_on)
            else -> getString(R.string.tile_subtitle_off)
        }
        tile.contentDescription = if (tunnelName != null) {
            "$appLabel, $tunnelName, $stateText"
        } else {
            "$appLabel, $stateText"
        }

        tile.state = when {
            !canStart -> Tile.STATE_INACTIVE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        tile.updateTile()
        Log.d(
            TAG,
            "Tile updated label=${tile.label} subtitle=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle else "n/a"} state=${tile.state}"
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
