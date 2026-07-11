package com.wifivpn.app.permission

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wifivpn.app.MainActivity
import com.wifivpn.app.R
import com.wifivpn.app.WifiVpnApp
import java.util.concurrent.TimeUnit

/**
 * Weekly scan of critical permissions / background settings.
 * Posts an alert notification when something required is missing or disabled.
 */
class PermissionCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val issues = PermissionStatusChecker.missingIssues(applicationContext)
        Log.i(TAG, "Weekly permission check: ${issues.size} issue(s)")
        if (issues.isEmpty()) {
            return Result.success()
        }
        postAlert(issues)
        return Result.success()
    }

    private fun postAlert(issues: List<String>) {
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = applicationContext.getString(
            R.string.notification_permission_check_body,
            issues.joinToString(separator = "\n") { "• $it" }
        )
        val notification = NotificationCompat.Builder(
            applicationContext,
            WifiVpnApp.ALERTS_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                applicationContext.getString(R.string.notification_permission_check_title)
            )
            .setContentText(issues.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(WifiVpnApp.PERMISSION_ALERT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "PermissionCheckWorker"
        private const val UNIQUE_WORK = "weekly_permission_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PermissionCheckWorker>(7, TimeUnit.DAYS)
                .addTag(UNIQUE_WORK)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Scheduled weekly permission check")
        }

        /** Run once soon (e.g. after install / boot) without waiting a week. */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<PermissionCheckWorker>()
                .addTag("${UNIQUE_WORK}_once")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
