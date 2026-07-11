package com.wifivpn.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.wifivpn.app.R

/**
 * Collects human-readable permission / background-setup issues for this app.
 */
object PermissionStatusChecker {

    fun missingIssues(context: Context): List<String> {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(PowerManager::class.java)
            val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            if (!exempt) {
                issues += context.getString(R.string.perm_issue_battery)
            }
        }

        return issues
    }
}
