package com.wifivpn.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Shared app version helpers (avoid duplicating PackageManager boilerplate). */
object AppInfo {

    fun versionName(context: Context): String {
        return try {
            val info = packageInfo(context)
            info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    fun versionCode(context: Context): Long {
        return try {
            val info = packageInfo(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** e.g. `1.4.2/11` */
    fun versionLabel(context: Context): String =
        "${versionName(context)}/${versionCode(context)}"

    private fun packageInfo(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
}
