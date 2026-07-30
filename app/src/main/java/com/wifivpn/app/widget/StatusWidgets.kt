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
import com.wifivpn.app.util.InternalIntentAuth
import com.wifivpn.app.util.InternalIntentAuth.hasValidInternalAuth
import com.wifivpn.app.util.InternalIntentAuth.putInternalAuth
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

        // 2×2: separate monitoring / Wi‑Fi / VPN lines.
        // 4×1: first line = monitoring only (avoids duplicating trusted + status message).
        if (layoutRes == R.layout.widget_status_2x2) {
            views.setTextViewText(R.id.widgetMonitoring, monitoringText)
            views.setTextColor(R.id.widgetMonitoring, monitoringColor)
            views.setTextViewText(R.id.widgetWifi, wifiText)
            views.setTextColor(R.id.widgetWifi, wifiColor)
            views.setTextViewText(R.id.widgetVpn, vpnText)
            views.setTextColor(R.id.widgetVpn, vpnColor)
        } else {
            views.setTextViewText(R.id.widgetSummary, monitoringText)
            views.setTextColor(R.id.widgetSummary, monitoringColor)
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
        // Prefer explicit status (peer unreachable, connecting, retry) whenever the
        // service reports VPN inactive — even if the tunnel interface is still tearing down.
        val transferStats = if (vpnActive) app?.wireGuardManager?.transferStats?.value else null
        val statusMessage = state.message.trim()
        val showStatusMessage = monitoring && !state.vpnActive && statusMessage.isNotEmpty()
        if (showStatusMessage) {
            // e.g. "Trusted Wi‑Fi — VPN off", "Connecting…", peer unreachable, failures
            views.setViewVisibility(R.id.widgetTransfer, View.VISIBLE)
            views.setTextViewText(R.id.widgetTransfer, statusMessage)
            // Trusted / steady-OK → green; connecting / errors → amber
            val statusColor = when {
                state.onTrustedWifi -> R.color.status_ok
                statusMessage == context.getString(R.string.notification_trusted_wifi) ->
                    R.color.status_ok
                statusMessage == context.getString(R.string.notification_untrusted_wifi) ||
                    statusMessage == context.getString(R.string.notification_wifi_lost) ->
                    R.color.md_theme_primary
                else -> R.color.status_warn
            }
            views.setTextColor(
                R.id.widgetTransfer,
                ContextCompat.getColor(context, statusColor)
            )
            views.setViewVisibility(R.id.widgetHandshake, View.GONE)
            views.setTextViewText(R.id.widgetHandshake, "")
        } else if (transferStats != null) {
            views.setViewVisibility(R.id.widgetTransfer, View.VISIBLE)
            views.setTextViewText(
                R.id.widgetTransfer,
                TransferStatsFormatter.formatWidgetTransferLine(context, transferStats)
            )
            views.setTextColor(
                R.id.widgetTransfer,
                ContextCompat.getColor(context, R.color.status_off)
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
     * Requires [InternalIntentAuth] so third-party apps cannot forge [ACTION_TOGGLE].
     */
    fun handleToggle(context: Context, intent: Intent? = null) {
        if (intent != null && !intent.hasValidInternalAuth(context)) {
            Log.w(TAG, "Ignoring widget toggle without internal auth")
            return
        }
        val appContext = context.applicationContext
        val app = appContext as? WifiVpnApp
        val running = WifiMonitorService.uiState.value.monitoring ||
            WifiMonitorService.instance != null

        if (running) {
            // Open MainActivity so an insecure-connection warning can be shown if needed
            Log.i(TAG, "Stop monitoring from widget — open confirm UI")
            val stopIntent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_STOP_MONITORING, true)
                putExtra(MainActivity.EXTRA_START_SOURCE, WifiMonitorService.SOURCE_WIDGET)
                putInternalAuth(appContext)
            }
            appContext.startActivity(stopIntent)
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
            putInternalAuth(context)
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
}
