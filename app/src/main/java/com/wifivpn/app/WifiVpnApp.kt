package com.wifivpn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.wifivpn.app.data.ConfigRepository
import com.wifivpn.app.permission.PermissionCheckWorker
import com.wifivpn.app.vpn.WireGuardManager

class WifiVpnApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var wireGuardManager: WireGuardManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configRepository = ConfigRepository(this)
        wireGuardManager = WireGuardManager(this)
        createNotificationChannels()
        PermissionCheckWorker.schedule(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(monitor)

        val alerts = NotificationChannel(
            ALERTS_CHANNEL_ID,
            getString(R.string.notification_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_alerts_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(alerts)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "wifi_vpn_monitor"
        const val ALERTS_CHANNEL_ID = "wifi_vpn_alerts"
        const val NOTIFICATION_ID = 1001
        const val PERMISSION_ALERT_NOTIFICATION_ID = 1002

        lateinit var instance: WifiVpnApp
            private set
    }
}
