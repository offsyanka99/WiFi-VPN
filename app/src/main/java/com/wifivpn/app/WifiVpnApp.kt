package com.wifivpn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.wifivpn.app.data.ConfigRepository
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
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "wifi_vpn_monitor"
        const val NOTIFICATION_ID = 1001

        lateinit var instance: WifiVpnApp
            private set
    }
}
