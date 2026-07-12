package com.wifivpn.app.configuration

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.wifivpn.app.R

/**
 * Battery optimization + “Manage app if unused” switches for Configuration.
 */
class BackgroundSettingsHelper(
    private val activity: AppCompatActivity,
    private val batterySwitch: MaterialSwitch,
    private val unusedSwitch: MaterialSwitch,
    private val batteryLauncher: ActivityResultLauncher<Intent>,
    private val unusedLauncher: ActivityResultLauncher<Intent>,
    private val logConfig: (String) -> Unit,
    private val toast: (String) -> Unit
) {
    var syncingBattery = false
        private set
    var syncingUnused = false
        private set

    fun refreshAll() {
        refreshBattery()
        refreshUnused()
    }

    fun refreshBattery() {
        val exempt = isIgnoringBatteryOptimizations()
        syncingBattery = true
        batterySwitch.isChecked = exempt
        syncingBattery = false
    }

    fun onBatteryToggled(wantExempt: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            refreshBattery()
            return
        }
        val currentlyExempt = isIgnoringBatteryOptimizations()
        if (wantExempt == currentlyExempt) return

        if (wantExempt) {
            logConfig("battery_optimization user requested exemption")
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                batteryLauncher.launch(intent)
                toast(activity.getString(R.string.msg_battery_opt_on))
            } catch (e: Exception) {
                Log.e(TAG, "Battery optimization request failed", e)
                try {
                    batteryLauncher.launch(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Battery settings open failed", e2)
                    toast(activity.getString(R.string.msg_battery_opt_open_failed))
                }
                refreshBattery()
            }
        } else {
            logConfig("battery_optimization user opened system settings to re-enable")
            try {
                batteryLauncher.launch(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
                toast(activity.getString(R.string.msg_battery_opt_off))
            } catch (e: Exception) {
                Log.e(TAG, "Battery settings open failed", e)
                toast(activity.getString(R.string.msg_battery_opt_open_failed))
            }
            refreshBattery()
        }
    }

    fun refreshUnused() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            unusedSwitch.isEnabled = false
            syncingUnused = true
            unusedSwitch.isChecked = false
            syncingUnused = false
            return
        }
        val manageOn = isManageUnusedEnabledInSystem()
        syncingUnused = true
        unusedSwitch.isChecked = manageOn
        syncingUnused = false
    }

    fun onUnusedToggled(wantManageOn: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast(activity.getString(R.string.msg_manage_unused_unavailable))
            refreshUnused()
            return
        }
        logConfig("manage_unused user opened system settings want_manage_on=$wantManageOn")
        refreshUnused()
        openUnusedSettings()
        toast(activity.getString(R.string.msg_manage_unused_open))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = activity.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    private fun isManageUnusedEnabledInSystem(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val exempt = activity.packageManager.isAutoRevokeWhitelisted
            !exempt
        } catch (e: Exception) {
            Log.w(TAG, "isAutoRevokeWhitelisted failed", e)
            true
        }
    }

    private fun openUnusedSettings() {
        val candidates = buildList {
            add(
                Intent("android.intent.action.AUTO_REVOKE_PERMISSIONS").apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
        }
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(activity.packageManager) != null) {
                    unusedLauncher.launch(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unused-app intent failed: ${intent.action}", e)
            }
        }
        toast(activity.getString(R.string.msg_manage_unused_open_failed))
        refreshUnused()
    }

    companion object {
        private const val TAG = "BackgroundSettings"
    }
}
