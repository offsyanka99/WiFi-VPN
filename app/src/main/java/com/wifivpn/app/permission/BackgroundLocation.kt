package com.wifivpn.app.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wifivpn.app.R

/**
 * "Allow all the time" location. A location foreground service started from
 * BOOT_COMPLETED only gets location access with it, so without it the platform hides the
 * Wi‑Fi name (and BSSID / network id) after a reboot until the app is opened.
 */
object BackgroundLocation {

    /** Below API 29 background location is not a separate permission. */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** The permission to request, or null when not applicable / already granted. */
    fun permissionToRequest(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (isGranted(context)) return null
        return Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }

    /** Explains why before the system screen, which only offers "Allow all the time" there. */
    fun showRationale(activity: Activity, onContinue: () -> Unit, onLater: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_background_location_title)
            .setMessage(R.string.dialog_background_location_message)
            .setPositiveButton(R.string.btn_background_location_allow) { _, _ -> onContinue() }
            .setNegativeButton(R.string.btn_background_location_later) { _, _ -> onLater() }
            .show()
    }
}
