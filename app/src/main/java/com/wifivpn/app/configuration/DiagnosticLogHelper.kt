package com.wifivpn.app.configuration

import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.wifivpn.app.R
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.log.DiagnosticSupport
import com.wifivpn.app.util.AppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostic log card: enable toggle, send email, clear.
 */
class DiagnosticLogHelper(
    private val activity: AppCompatActivity,
    private val app: WifiVpnApp,
    private val switch: MaterialSwitch,
    private val btnSend: MaterialButton,
    private val btnClear: MaterialButton,
    private val logConfig: (String) -> Unit,
    private val toast: (String) -> Unit
) {
    var syncingSwitch = false
        private set

    fun bindClicks() {
        btnSend.setOnClickListener { sendLog() }
        btnClear.setOnClickListener { clearLog() }
        switch.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed || syncingSwitch) return@setOnCheckedChangeListener
            onToggled(isChecked)
        }
    }

    fun applyEnabledFromPrefs(enabled: Boolean) {
        syncingSwitch = true
        if (switch.isChecked != enabled) {
            switch.isChecked = enabled
        }
        updateActionsEnabled(enabled)
        syncingSwitch = false
    }

    fun updateActionsEnabled(loggingEnabled: Boolean) {
        val canUseLog = loggingEnabled || app.diagnosticLogger.logFileExists()
        btnSend.isEnabled = canUseLog
        btnClear.isEnabled = canUseLog
    }

    private fun onToggled(isChecked: Boolean) {
        if (isChecked) {
            activity.lifecycleScope.launch {
                app.configRepository.setDiagnosticLoggingEnabled(true)
                app.diagnosticLogger.setEnabled(true)
                app.diagnosticLogger.logLoggingEnabled()
                updateActionsEnabled(true)
                toast(activity.getString(R.string.msg_diagnostic_log_enabled))
            }
            return
        }

        if (app.diagnosticLogger.logFileExists()) {
            if (activity.isFinishing || activity.isDestroyed) return
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_disable_diagnostic_log_title)
                .setMessage(R.string.dialog_disable_diagnostic_log_message)
                .setPositiveButton(R.string.btn_delete_log) { _, _ ->
                    disableLogging(deleteLog = true)
                }
                .setNegativeButton(R.string.btn_keep_log) { _, _ ->
                    disableLogging(deleteLog = false)
                }
                .setNeutralButton(R.string.btn_cancel) { _, _ ->
                    restoreSwitch(true)
                }
                .setOnCancelListener {
                    restoreSwitch(true)
                }
                .show()
        } else {
            disableLogging(deleteLog = false)
        }
    }

    private fun disableLogging(deleteLog: Boolean) {
        activity.lifecycleScope.launch {
            logConfig("diagnostic_logging disabled delete_log=$deleteLog")
            app.diagnosticLogger.setEnabled(false)
            if (deleteLog) {
                app.diagnosticLogger.deleteLogFile()
            }
            app.configRepository.setDiagnosticLoggingEnabled(false)
            updateActionsEnabled(false)
            toast(
                activity.getString(
                    if (deleteLog) {
                        R.string.msg_diagnostic_log_disabled_deleted
                    } else {
                        R.string.msg_diagnostic_log_disabled_kept
                    }
                )
            )
        }
    }

    private fun restoreSwitch(enabled: Boolean) {
        syncingSwitch = true
        switch.isChecked = enabled
        syncingSwitch = false
    }

    private fun sendLog() {
        val logger = app.diagnosticLogger
        if (!logger.isEnabled() && !logger.hasContent()) return
        if (!logger.hasContent()) {
            toast(activity.getString(R.string.msg_diagnostic_log_empty))
            return
        }
        activity.lifecycleScope.launch {
            if (!app.configRepository.isDiagnosticShareConsented()) {
                confirmShareConsent()
                return@launch
            }
            shareLog()
        }
    }

    /**
     * The log leaves the device, so the user must be told what is in it first.
     * Identifiers are pseudonymised, but device model and behaviour timings are not.
     */
    private fun confirmShareConsent() {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_diagnostic_share_title)
            .setMessage(R.string.dialog_diagnostic_share_message)
            .setPositiveButton(R.string.btn_diagnostic_share_accept) { _, _ ->
                activity.lifecycleScope.launch {
                    app.configRepository.setDiagnosticShareConsented(true)
                    shareLog()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private suspend fun shareLog() {
        val logger = app.diagnosticLogger
        logger.i("UI", "user requested send diagnostic log via email")
        // No-ops when logging is off: never write new entries the user did not ask for.
        DiagnosticSupport.logSupportSummary(app, "send_log")
        logger.i("SUPPORT", "perms at send: ${DiagnosticSupport.permissionSnapshot(activity)}")

        val version = AppInfo.versionName(activity)
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidLabel = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val body = activity.getString(
            R.string.diagnostic_log_email_body,
            version,
            device,
            androidLabel
        )
        // Flushes and fsyncs the log file — must not run on the main thread.
        val intent = withContext(Dispatchers.IO) {
            logger.createEmailShareIntent(
                subject = activity.getString(R.string.diagnostic_log_email_subject),
                body = body,
                toAddress = activity.getString(R.string.about_email),
                chooserTitle = activity.getString(R.string.diagnostic_log_share_title)
            )
        }
        if (intent == null) {
            toast(activity.getString(R.string.msg_diagnostic_log_share_failed))
            return
        }
        try {
            activity.startActivity(intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch log share", e)
            logger.logException("UI", "diagnostic log share failed", e)
            toast(activity.getString(R.string.msg_diagnostic_log_share_failed))
        }
    }

    private fun clearLog() {
        if (!app.diagnosticLogger.isEnabled() && !app.diagnosticLogger.hasContent()) return
        app.diagnosticLogger.clear()
        updateActionsEnabled(app.diagnosticLogger.isEnabled())
        toast(activity.getString(R.string.msg_diagnostic_log_cleared))
    }

    companion object {
        private const val TAG = "DiagnosticLogHelper"
    }
}
