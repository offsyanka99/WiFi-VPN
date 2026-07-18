package com.wifivpn.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import com.wifivpn.app.MainActivity
import com.wifivpn.app.R
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.service.WifiMonitorService
import com.wifivpn.app.tile.MonitorTileService
import com.wifivpn.app.vpn.TransferStatsFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared home-screen widget helpers for status (2×2 and 4×1).
 *
 * Updates are push-driven from [WifiMonitorService] / UI — not periodic.
 */
object StatusWidgets {

    private const val TAG = "StatusWidgets"
    private const val STOP_WAIT_MS = 3_000L
    private const val REINFORCE_DELAY_MS = 350L

    const val ACTION_TOGGLE = "com.wifivpn.app.widget.ACTION_TOGGLE"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        updateProvider(appContext, manager, StatusWidget2x2Provider::class.java, R.layout.widget_status_2x2)
        updateProvider(appContext, manager, StatusWidget4x1Provider::class.java, R.layout.widget_status_4x1)
    }

    /**
     * Schedule [updateAll] on the main looper (safe from any thread) and once more
     * after a short delay so a stale RemoteViews binder call cannot win the race.
     */
    fun updateAllSoon(context: Context) {
        val appContext = context.applicationContext
        val run = Runnable {
            try {
                updateAll(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "updateAll failed", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            mainHandler.post(run)
        }
        mainHandler.postDelayed(run, REINFORCE_DELAY_MS)
    }

    fun updateIds(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        @LayoutRes layoutRes: Int
    ) {
        if (appWidgetIds.isEmpty()) return
        val views = buildRemoteViews(context.applicationContext, layoutRes)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    fun buildRemoteViews(context: Context, @LayoutRes layoutRes: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        val app = context.applicationContext as? WifiVpnApp
        val state = WifiMonitorService.uiState.value
        val monitoring = state.monitoring || WifiMonitorService.instance != null
        val vpnActive = monitoring && (state.vpnActive || app?.wireGuardManager?.isUp == true)
        val tunnelName = MonitorTileService.displayTunnelName(app?.cachedConfigFileName.orEmpty())
        val title = tunnelName ?: context.getString(R.string.app_name)

        views.setTextViewText(R.id.widgetTitle, title)

        val monitoringText = context.getString(
            if (monitoring) R.string.status_monitoring_on else R.string.status_monitoring_off
        )
        val wifiText = formatWifiStatus(context, state)
        val vpnText = context.getString(
            if (vpnActive) R.string.status_vpn_on else R.string.status_vpn_off
        )

        val monitoringColor = ContextCompat.getColor(
            context,
            if (monitoring) R.color.status_ok else R.color.status_off
        )
        val wifiColor = ContextCompat.getColor(
            context,
            when {
                state.onTrustedWifi -> R.color.status_ok
                state.wifiConnected -> R.color.status_warn
                else -> R.color.status_warn
            }
        )
        val vpnColor = ContextCompat.getColor(
            context,
            if (vpnActive) R.color.md_theme_primary else R.color.status_off
        )

        // 2×2 layout has separate lines; 4×1 uses a compact summary.
        if (layoutRes == R.layout.widget_status_2x2) {
            views.setTextViewText(R.id.widgetMonitoring, monitoringText)
            views.setTextColor(R.id.widgetMonitoring, monitoringColor)
            views.setTextViewText(R.id.widgetWifi, wifiText)
            views.setTextColor(R.id.widgetWifi, wifiColor)
            views.setTextViewText(R.id.widgetVpn, vpnText)
            views.setTextColor(R.id.widgetVpn, vpnColor)
        } else {
            val wifiShort = formatWifiShort(context, state)
            val vpnShort = if (vpnActive) {
                context.getString(R.string.widget_summary_vpn_on)
            } else {
                context.getString(R.string.widget_summary_vpn_off)
            }
            val summary = context.getString(
                R.string.widget_summary_line,
                wifiShort,
                vpnShort
            )
            views.setTextViewText(R.id.widgetSummary, summary)
            views.setTextColor(
                R.id.widgetSummary,
                when {
                    !monitoring -> ContextCompat.getColor(context, R.color.status_off)
                    vpnActive -> ContextCompat.getColor(context, R.color.md_theme_primary)
                    state.onTrustedWifi -> ContextCompat.getColor(context, R.color.status_ok)
                    else -> ContextCompat.getColor(context, R.color.status_warn)
                }
            )
        }

        val toggleLabel = if (monitoring) {
            if (layoutRes == R.layout.widget_status_4x1) {
                context.getString(R.string.widget_btn_stop)
            } else {
                context.getString(R.string.btn_stop_monitoring)
            }
        } else {
            if (layoutRes == R.layout.widget_status_4x1) {
                context.getString(R.string.widget_btn_start)
            } else {
                context.getString(R.string.btn_start_monitoring)
            }
        }
        views.setTextViewText(R.id.widgetToggle, toggleLabel)

        // Totals + last handshake age while VPN is up (rates stay on the main screen).
        val transferStats = if (vpnActive) app?.wireGuardManager?.transferStats?.value else null
        if (transferStats != null) {
            views.setViewVisibility(R.id.widgetTransfer, View.VISIBLE)
            views.setTextViewText(
                R.id.widgetTransfer,
                TransferStatsFormatter.formatWidgetTransferLine(context, transferStats)
            )
            views.setViewVisibility(R.id.widgetHandshake, View.VISIBLE)
            views.setTextViewText(
                R.id.widgetHandshake,
                TransferStatsFormatter.formatWidgetHandshakeLine(context, transferStats)
            )
        } else {
            views.setViewVisibility(R.id.widgetTransfer, View.GONE)
            views.setTextViewText(R.id.widgetTransfer, "")
            views.setViewVisibility(R.id.widgetHandshake, View.GONE)
            views.setTextViewText(R.id.widgetHandshake, "")
        }

        val openApp = openAppPendingIntent(context, requestCode = 10)
        views.setOnClickPendingIntent(R.id.widgetRoot, openApp)
        views.setOnClickPendingIntent(R.id.widgetTitle, openApp)

        val toggle = togglePendingIntent(context, requestCode = 20 + layoutRes)
        views.setOnClickPendingIntent(R.id.widgetToggle, toggle)

        views.setViewVisibility(R.id.widgetToggle, View.VISIBLE)

        return views
    }

    /**
     * Handles start/stop from the widget toggle.
     * Start goes through [WidgetStartActivity] (translucent) so the location FGS
     * is eligible on Android 14+ without flashing the main UI.
     */
    fun handleToggle(context: Context) {
        val appContext = context.applicationContext
        val app = appContext as? WifiVpnApp
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null

        if (running) {
            Log.i(TAG, "Stop monitoring from widget")
            runCatching {
                app?.diagnosticLogger?.i(
                    "UI",
                    "stop monitoring requested source=${WifiMonitorService.SOURCE_WIDGET}"
                )
            }
            WifiMonitorService.stop(appContext, WifiMonitorService.SOURCE_WIDGET)
            // Wait for service to clear monitoring, then push widget state (async stop).
            val scope = app?.applicationScope
            if (scope != null) {
                scope.launch {
                    withTimeoutOrNull(STOP_WAIT_MS) {
                        WifiMonitorService.uiState.first { !it.monitoring }
                    }
                    // instance may linger briefly after monitoring=false
                    delay(50)
                    updateAllSoon(appContext)
                    MonitorTileService.requestUpdate(appContext)
                }
            } else {
                updateAllSoon(appContext)
                MonitorTileService.requestUpdate(appContext)
            }
            return
        }

        val canStart = app?.cachedCanStartMonitoring == true
        val needsVpn = app?.wireGuardManager?.prepareVpnPermission() != null
        if (!canStart || needsVpn) {
            Log.i(TAG, "Open app for setup (canStart=$canStart needsVpn=$needsVpn)")
            openMainApp(appContext)
            return
        }

        Log.i(TAG, "Start monitoring via WidgetStartActivity")
        appContext.startActivity(WidgetStartActivity.intent(appContext))
    }

    private fun openMainApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun togglePendingIntent(context: Context, requestCode: Int): PendingIntent {
        // Route through 2×2 provider; both providers handle ACTION_TOGGLE the same way.
        val intent = Intent(context, StatusWidget2x2Provider::class.java).apply {
            action = ACTION_TOGGLE
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        providerClass: Class<*>,
        @LayoutRes layoutRes: Int
    ) {
        val component = ComponentName(context, providerClass)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        updateIds(context, manager, ids, layoutRes)
    }

    private fun formatWifiStatus(
        context: Context,
        state: WifiMonitorService.MonitorUiState
    ): String {
        return when {
            !state.wifiConnected -> context.getString(R.string.status_wifi_disconnected)
            state.onTrustedWifi && state.currentSsid != null ->
                context.getString(R.string.status_wifi_trusted, state.currentSsid)
            state.currentSsid != null ->
                context.getString(R.string.status_wifi_other, state.currentSsid)
            else -> context.getString(R.string.status_wifi_unknown)
        }
    }

    private fun formatWifiShort(
        context: Context,
        state: WifiMonitorService.MonitorUiState
    ): String {
        return when {
            !state.wifiConnected -> context.getString(R.string.widget_summary_wifi_off)
            state.onTrustedWifi -> context.getString(R.string.widget_summary_wifi_trusted)
            else -> context.getString(R.string.widget_summary_wifi_other)
        }
    }
}
