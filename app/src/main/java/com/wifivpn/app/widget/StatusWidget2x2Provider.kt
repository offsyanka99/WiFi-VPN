package com.wifivpn.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.wifivpn.app.R

/** Home-screen status widget — default size 2×2. */
class StatusWidget2x2Provider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        StatusWidgets.updateIds(
            context,
            appWidgetManager,
            appWidgetIds,
            R.layout.widget_status_2x2
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
