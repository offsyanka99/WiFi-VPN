package com.wifivpn.app.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
 * connect success/failure, retry attempts, and user configuration changes.
 * The file can be shared via email.
 *
 * Logging is opt-in via [setEnabled]. Thread-safe; also mirrors lines to logcat
 * under tag [TAG] when enabled. Rotates (keeps recent half) when the file
 * exceeds [MAX_BYTES] (~3 MiB), using a stream so the full file is never held
 * in memory.
 */
class DiagnosticLogger(context: Context) {

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val logDir: File = File(appContext.filesDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val logFile: File = File(logDir, LOG_FILE_NAME)

    @Volatile
    private var enabled: Boolean = false

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Enables or disables file (and logcat mirror) logging.
     * Does not write to the file; use [logLoggingEnabled] when the user turns logging on.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** User turned logging on in settings — write a clear marker. */
    fun logLoggingEnabled() {
        if (!enabled) return
        lock.withLock {
            if (!logFile.exists() || logFile.length() == 0L) {
                writeHeaderUnlocked("logging enabled")
            } else {
                appendUnlocked("INFO", "SESSION", "logging enabled")
            }
        }
    }

    /** Call when the process starts so sessions are easy to find in the file. */
    fun logSessionStart(appVersion: String) {
        if (!enabled) return
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
        if (!enabled) return
        lock.withLock {
            appendUnlocked(level, category, message)
        }
    }

    fun logFile(): File = logFile

    fun logFileExists(): Boolean = lock.withLock {
        logFile.exists() && logFile.length() > 0L
    }

    fun hasContent(): Boolean = logFileExists()

    fun clear() {
        lock.withLock {
            logFile.writeText("")
            if (enabled) {
                writeHeaderUnlocked("log cleared")
            }
        }
    }

    /** Permanently removes the log file from app storage. */
    fun deleteLogFile() {
        lock.withLock {
            if (logFile.exists()) {
                if (!logFile.delete()) {
                    logFile.writeText("")
                }
            }
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
     * Streams via a temp file — never loads the whole log into heap (important at 1 GiB).
     */
    private fun rotateIfNeededUnlocked() {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return
        val temp = File(logDir, "$LOG_FILE_NAME.tmp")
        try {
            val total = logFile.length()
            val skipTarget = total / 2
            BufferedInputStream(FileInputStream(logFile), STREAM_BUF).use { input ->
                var skipped = 0L
                while (skipped < skipTarget) {
                    val n = input.skip(skipTarget - skipped)
                    if (n <= 0L) {
                        if (input.read() < 0) break
                        skipped++
                    } else {
                        skipped += n
                    }
                }
                // Align to next newline so retained content starts at a full line
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                }
                FileOutputStream(temp).buffered(STREAM_BUF).use { out ->
                    val header =
                        "${timeFormat.format(Date())} INFO [SESSION] log rotated " +
                            "(kept recent half, max_bytes=$MAX_BYTES)\n"
                    out.write(header.toByteArray(Charsets.UTF_8))
                    input.copyTo(out, STREAM_BUF)
                }
            }
            if (!temp.renameTo(logFile)) {
                temp.copyTo(logFile, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed", e)
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    companion object {
        private const val TAG = "DiagnosticLog"
        private const val LOG_DIR_NAME = "logs"
        private const val LOG_FILE_NAME = "wifi-vpn-diagnostic.log"
        /** Soft cap before rotation (3 MiB). */
        private const val MAX_BYTES = 3L * 1024L * 1024L
        private const val STREAM_BUF = 64 * 1024
    }
}
