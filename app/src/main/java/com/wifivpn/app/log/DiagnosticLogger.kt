package com.wifivpn.app.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Append-only diagnostic log for multi-device troubleshooting.
 *
 * Captures network changes (Wi‑Fi / cellular), SSID, VPN on/off, tunnel
 * connect success/failure, and retry attempts. The file can be shared via email.
 *
 * Thread-safe; also mirrors lines to logcat under tag [TAG].
 */
class DiagnosticLogger(context: Context) {

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val logDir: File = File(appContext.filesDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val logFile: File = File(logDir, LOG_FILE_NAME)

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    init {
        lock.withLock {
            if (!logFile.exists() || logFile.length() == 0L) {
                writeHeaderUnlocked("log created")
            }
        }
    }

    /** Call when the process starts so sessions are easy to find in the file. */
    fun logSessionStart(appVersion: String) {
        lock.withLock {
            appendUnlocked(
                "INFO",
                "SESSION",
                "app_start version=$appVersion " +
                    "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}"
            )
        }
    }

    fun i(category: String, message: String) = log("INFO", category, message)

    fun w(category: String, message: String) = log("WARN", category, message)

    fun e(category: String, message: String) = log("ERROR", category, message)

    fun log(
        level: String,
        category: String,
        message: String
    ) {
        lock.withLock {
            appendUnlocked(level, category, message)
        }
    }

    fun logFile(): File = logFile

    fun hasContent(): Boolean = lock.withLock {
        logFile.exists() && logFile.length() > 0L
    }

    fun clear() {
        lock.withLock {
            logFile.writeText("")
            writeHeaderUnlocked("log cleared")
        }
    }

    /**
     * Builds an [Intent.ACTION_SEND] chooser with the log file attached.
     * Returns null if the file is empty or the URI cannot be created.
     */
    fun createEmailShareIntent(
        subject: String,
        body: String,
        toAddress: String? = null,
        chooserTitle: String? = null
    ): Intent? {
        val file = logFile
        if (!file.exists() || file.length() == 0L) return null

        val uri: Uri = try {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider failed for log share", e)
            return null
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!toAddress.isNullOrBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(toAddress))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some clients need ClipData to honor read permission on the stream
            clipData = android.content.ClipData.newUri(
                appContext.contentResolver,
                "diagnostic log",
                uri
            )
        }
        return Intent.createChooser(send, chooserTitle)
    }

    private fun appendUnlocked(level: String, category: String, message: String) {
        rotateIfNeededUnlocked()
        val line = "${timeFormat.format(Date())} $level [$category] $message"
        try {
            logFile.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic log", e)
        }
        when (level) {
            "ERROR" -> Log.e(TAG, "[$category] $message")
            "WARN" -> Log.w(TAG, "[$category] $message")
            else -> Log.i(TAG, "[$category] $message")
        }
    }

    private fun writeHeaderUnlocked(reason: String) {
        appendUnlocked(
            "INFO",
            "SESSION",
            "$reason | WiFi VPN diagnostic log (timezone=${TimeZone.getDefault().id})"
        )
    }

    /**
     * If the file exceeds [MAX_BYTES], keep the last ~half so recent events survive.
     */
    private fun rotateIfNeededUnlocked() {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return
        try {
            val text = logFile.readText()
            val keepFrom = (text.length / 2).coerceAtLeast(0)
            val cut = text.indexOf('\n', keepFrom).let { if (it < 0) keepFrom else it + 1 }
            val retained = text.substring(cut.coerceAtMost(text.length))
            logFile.writeText(
                "${timeFormat.format(Date())} INFO [SESSION] log rotated (kept recent half)\n" +
                    retained
            )
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed", e)
        }
    }

    companion object {
        private const val TAG = "DiagnosticLog"
        private const val LOG_DIR_NAME = "logs"
        private const val LOG_FILE_NAME = "wifi-vpn-diagnostic.log"
        /** Soft cap before rotation (~512 KiB). */
        private const val MAX_BYTES = 512 * 1024L
    }
}
