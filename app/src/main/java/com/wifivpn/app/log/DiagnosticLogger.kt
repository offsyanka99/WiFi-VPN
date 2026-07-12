package com.wifivpn.app.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Append-only diagnostic log for multi-device troubleshooting.
 *
 * Routine writes are buffered and flushed on a single IO thread. Crashes always
 * write synchronously with fsync. Uncaught JVM crashes are recorded even when
 * routine logging is off.
 *
 * **Not covered:** native / WireGuard-Go crashes, OOM kills, ANRs, force-stop.
 */
class DiagnosticLogger(context: Context) {

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val logDir: File = File(appContext.filesDir, LOG_DIR_NAME).also { it.mkdirs() }
    private val logFile: File = File(logDir, LOG_FILE_NAME)

    private val writeScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @Volatile
    private var enabled: Boolean = false

    private val lineBuffer = StringBuilder(BUFFER_CAPACITY)
    private var lastNetworkMessage: String? = null
    private var lastNetworkAtMs: Long = 0L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            // Flush any pending lines when turning off
            flushAsync()
        }
    }

    fun logLoggingEnabled() {
        if (!enabled) return
        writeScope.launch {
            lock.withLock {
                val empty = (!logFile.exists() || logFile.length() == 0L) && lineBuffer.isEmpty()
                val msg = if (empty) {
                    "logging enabled | WiFi VPN diagnostic log " +
                        "(timezone=${TimeZone.getDefault().id})"
                } else {
                    "logging enabled"
                }
                appendLineUnlocked("INFO", "SESSION", msg)
                flushBufferToFileUnlocked()
            }
        }
    }

    fun logSessionStart(appVersion: String) {
        if (!enabled) return
        enqueue(
            "INFO",
            "SESSION",
            "app_start version=$appVersion " +
                "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) " +
                "device=${Build.MANUFACTURER} ${Build.MODEL}",
            forceSync = false
        )
    }

    fun i(category: String, message: String) = log("INFO", category, message)

    fun w(category: String, message: String) = log("WARN", category, message)

    fun e(category: String, message: String) = log("ERROR", category, message)

    fun logException(
        category: String,
        message: String,
        throwable: Throwable?,
        forceWrite: Boolean = false
    ) {
        if (!enabled && !forceWrite) return
        if (forceWrite) {
            lock.withLock {
                appendLineUnlocked("ERROR", category, message, skipRotate = true)
                if (throwable != null) {
                    writeStackUnlocked(category, throwable, skipRotate = true)
                }
                flushBufferToFileUnlocked()
                fsyncUnlocked()
            }
            mirrorLogcat("ERROR", category, message)
            return
        }
        enqueue("ERROR", category, message, forceSync = false)
        if (throwable != null) {
            val stackLines = throwable.stackTraceToString().lineSequence()
                .take(MAX_STACK_LINES)
                .toList()
            for (raw in stackLines) {
                val line = raw.trimEnd()
                if (line.isNotEmpty()) {
                    enqueue("ERROR", category, "| $line", forceSync = false)
                }
            }
        }
    }

    fun logUncaughtCrash(thread: Thread, throwable: Throwable) {
        try {
            lock.withLock {
                appendLineUnlocked(
                    "ERROR",
                    CAT_CRASH,
                    "uncaught on thread=${thread.name} " +
                        "(id=${thread.id}): ${throwable.javaClass.name}: ${throwable.message}",
                    skipRotate = true
                )
                writeStackUnlocked(CAT_CRASH, throwable, skipRotate = true)
                flushBufferToFileUnlocked()
                fsyncUnlocked()
            }
            mirrorLogcat(
                "ERROR",
                CAT_CRASH,
                "uncaught ${throwable.javaClass.simpleName}: ${throwable.message}"
            )
        } catch (t: Throwable) {
            try {
                Log.e(TAG, "Failed to write crash to diagnostic log", t)
            } catch (_: Throwable) {
            }
        }
    }

    fun log(level: String, category: String, message: String) {
        if (!enabled) return
        if (category == CAT_NETWORK) {
            val now = System.currentTimeMillis()
            if (message == lastNetworkMessage && now - lastNetworkAtMs < NETWORK_DEBOUNCE_MS) {
                return
            }
            lastNetworkMessage = message
            lastNetworkAtMs = now
        }
        enqueue(level, category, message, forceSync = false)
    }

    fun logFile(): File {
        flushSync()
        return logFile
    }

    fun logFileExists(): Boolean {
        flushSync()
        return lock.withLock {
            logFile.exists() && logFile.length() > 0L
        }
    }

    fun hasContent(): Boolean = logFileExists()

    fun clear() {
        lock.withLock {
            lineBuffer.clear()
            lastNetworkMessage = null
            logFile.writeText("")
            if (enabled) {
                appendLineUnlocked("INFO", "SESSION", "log cleared", skipRotate = true)
                flushBufferToFileUnlocked()
            }
        }
    }

    fun deleteLogFile() {
        lock.withLock {
            lineBuffer.clear()
            lastNetworkMessage = null
            if (logFile.exists()) {
                if (!logFile.delete()) {
                    logFile.writeText("")
                }
            }
        }
    }

    /** Block until buffered lines are on disk (call before email share). */
    fun flushSync() {
        lock.withLock {
            flushBufferToFileUnlocked()
            fsyncUnlocked()
        }
    }

    fun createEmailShareIntent(
        subject: String,
        body: String,
        toAddress: String? = null,
        chooserTitle: String? = null
    ): Intent? {
        flushSync()
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
            clipData = android.content.ClipData.newUri(
                appContext.contentResolver,
                "diagnostic log",
                uri
            )
        }
        return Intent.createChooser(send, chooserTitle)
    }

    private fun enqueue(
        level: String,
        category: String,
        message: String,
        forceSync: Boolean
    ) {
        mirrorLogcat(level, category, message)
        if (forceSync) {
            lock.withLock {
                appendLineUnlocked(level, category, message)
                flushBufferToFileUnlocked()
            }
            return
        }
        writeScope.launch {
            lock.withLock {
                appendLineUnlocked(level, category, message)
                if (lineBuffer.length >= BUFFER_FLUSH_CHARS ||
                    level == "ERROR" ||
                    category == "SESSION" ||
                    category == "CRASH" ||
                    category == "SUPPORT"
                ) {
                    flushBufferToFileUnlocked()
                }
            }
        }
    }

    private fun flushAsync() {
        writeScope.launch {
            lock.withLock {
                flushBufferToFileUnlocked()
            }
        }
    }

    private fun appendLineUnlocked(
        level: String,
        category: String,
        message: String,
        skipRotate: Boolean = false
    ) {
        if (!skipRotate && logFile.length() + lineBuffer.length >= MAX_BYTES) {
            flushBufferToFileUnlocked()
            rotateIfNeededUnlocked()
        }
        val line = "${timeFormat.format(Date())} $level [$category] $message\n"
        lineBuffer.append(line)
    }

    private fun writeStackUnlocked(
        category: String,
        throwable: Throwable,
        skipRotate: Boolean
    ) {
        val stack = throwable.stackTraceToString().trimEnd()
        val lines = stack.lineSequence().take(MAX_STACK_LINES).toList()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            appendLineUnlocked("ERROR", category, "| $line", skipRotate = skipRotate)
        }
        if (stack.lineSequence().count() > MAX_STACK_LINES) {
            appendLineUnlocked(
                "ERROR",
                category,
                "| … stack truncated after $MAX_STACK_LINES lines",
                skipRotate = skipRotate
            )
        }
    }

    private fun flushBufferToFileUnlocked() {
        if (lineBuffer.isEmpty()) return
        try {
            if (!logFile.exists() || logFile.length() == 0L) {
                // optional: nothing
            }
            rotateIfNeededUnlocked()
            FileOutputStream(logFile, /* append = */ true).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), STREAM_BUF).use { writer ->
                    writer.append(lineBuffer)
                }
            }
            lineBuffer.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostic log", e)
        }
    }

    private fun fsyncUnlocked() {
        try {
            if (!logFile.exists()) return
            FileOutputStream(logFile, /* append = */ true).use { fos ->
                fos.fd.sync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fsync diagnostic log failed", e)
        }
    }

    private fun mirrorLogcat(level: String, category: String, message: String) {
        // Keep logcat quieter: only WARN/ERROR (and CRASH always)
        when (level) {
            "ERROR" -> Log.e(TAG, "[$category] $message")
            "WARN" -> Log.w(TAG, "[$category] $message")
            else -> {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "[$category] $message")
                }
            }
        }
    }

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
        private const val CAT_CRASH = "CRASH"
        private const val CAT_NETWORK = "NETWORK"
        private const val LOG_DIR_NAME = "logs"
        private const val LOG_FILE_NAME = "wifi-vpn-diagnostic.log"
        private const val MAX_BYTES = 3L * 1024L * 1024L
        private const val STREAM_BUF = 64 * 1024
        private const val MAX_STACK_LINES = 80
        private const val BUFFER_CAPACITY = 8 * 1024
        private const val BUFFER_FLUSH_CHARS = 4 * 1024
        private const val NETWORK_DEBOUNCE_MS = 1_500L
    }
}
