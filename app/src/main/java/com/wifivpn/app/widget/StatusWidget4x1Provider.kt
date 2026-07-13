package com.wifivpn.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.wifivpn.app.R

/** Home-screen status widget — wide bar 4×1. */
class StatusWidget4x1Provider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        StatusWidgets.updateIds(
            context,
            appWidgetManager,
            appWidgetIds,
            R.layout.widget_status_4x1
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == StatusWidgets.ACTION_TOGGLE) {
            StatusWidgets.handleToggle(context)
        }
    }

    override fun onEnabled(context: Context) {
        StatusWidgets.updateAll(context)
    }
}
