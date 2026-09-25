package com.wifivpn.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.wifivpn.app.R
import com.wifivpn.app.network.LocalNetwork
import com.wireguard.config.Config

/**
 * Collects human-readable permission / background-setup issues for this app.
 */
object PermissionStatusChecker {

    /**
     * @param config the active WireGuard config, when known. Only used to decide whether a
     * missing local-network permission matters (it does only for LAN peer endpoints).
     * @param autoStartEnabled background location only matters when monitoring starts at boot.
     */
    fun missingIssues(
        context: Context,
        config: Config? = null,
        autoStartEnabled: Boolean = false
    ): List<String> {
        val issues = mutableListOf<String>()

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasNearby) {
            issues += context.getString(R.string.perm_issue_location)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                issues += context.getString(R.string.perm_issue_notifications)
            }
        }

        if (VpnService.prepare(context) != null) {
            issues += context.getString(R.string.perm_issue_vpn)
        }

        if (LocalNetwork.blocksConfig(context, config)) {
            issues += context.getString(R.string.perm_issue_local_network)
        }

        if (autoStartEnabled && !BackgroundLocation.isGranted(context)) {
            issues += context.getString(R.string.perm_issue_background_location)
        }

        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) != true) {
            issues += context.getString(R.string.perm_issue_battery)
        }

        return issues
    }
}
