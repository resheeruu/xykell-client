package dev.xykell.client.runtime

import android.content.Context
import android.os.Build
import dev.xykell.client.runtime.XykellInfo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xykell Crash Guard: captures uncaught exceptions, persists a minimal
 * crash report, and avoids recursive crash loops. Does not intercept
 * native crashes (SIGSEGV/SIGABRT) — only Java/Kotlin uncaught
 * exceptions. Never stores credentials, tokens, or WebSocket payloads.
 */
object CrashGuard {

    private const val CRASH_DIR = "crash"
    private const val MAX_REPORTS = 10
    const val MAX_REPORT_SIZE = 64 * 1024 // 64 KB

    private val handlerInstalled = AtomicBoolean(false)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Initialize the crash guard. Call once on application start. */
    fun initialize(context: Context) {
        if (handlerInstalled.compareAndSet(false, true)) {
            val crashDir = File(context.filesDir, CRASH_DIR)
            crashDir.mkdirs()
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                handleException(context, thread, throwable)
            }
        }
    }

    /** Check if a crash occurred on the previous run. */
    fun hasPreviousCrash(context: Context): Boolean {
        val crashDir = File(context.filesDir, CRASH_DIR)
        return crashDir.exists() && crashDir.listFiles()?.isNotEmpty() == true
    }

    /** Read the most recent crash report. Returns null if none. */
    fun getLastCrashReport(context: Context): String? {
        val crashDir = File(context.filesDir, CRASH_DIR)
        val files = crashDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
        return files?.firstOrNull()?.readText()
    }

    /** One stored report, newest first. Metadata only; no content. */
    data class ReportInfo(val name: String, val sizeBytes: Long, val modifiedAtMs: Long)

    /** All stored reports, newest first. Bounded by MAX_REPORTS. */
    fun listReports(context: Context): List<ReportInfo> {
        val crashDir = File(context.filesDir, CRASH_DIR)
        return crashDir.listFiles()
            ?.filter { it.isFile && isReportFileName(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { ReportInfo(it.name, it.length(), it.lastModified()) }
            ?: emptyList()
    }

    /** Read one report by exact stored name. Null when the name is not a
     *  stored report (rejects separators and traversal outright). */
    fun readReport(context: Context, name: String): String? {
        if (!isReportFileName(name)) return null
        val file = File(File(context.filesDir, CRASH_DIR), name)
        return if (file.isFile) file.readText() else null
    }

    /** Delete one report by exact stored name. */
    fun deleteReport(context: Context, name: String): Boolean {
        if (!isReportFileName(name)) return false
        val file = File(File(context.filesDir, CRASH_DIR), name)
        return file.isFile && file.delete()
    }

    /** Exact on-disk name shape from generateFileName(): no path separators,
     *  so a crafted name cannot escape the crash directory. */
    internal fun isReportFileName(name: String): Boolean =
        NAME_PATTERN.matches(name)

    private val NAME_PATTERN = Regex("""crash_\d{8}_\d{6}_\d{3}\.txt""")

    /** Clear all crash reports. */
    fun clearCrashReports(context: Context) {
        val crashDir = File(context.filesDir, CRASH_DIR)
        crashDir.listFiles()?.forEach { it.delete() }
    }

    /** Called by the system when an uncaught exception occurs. */
    private fun handleException(context: Context, thread: Thread, throwable: Throwable) {
        // Prevent recursive crash loops
        if (CrashReportWriter.isWriting.get()) {
            previousHandler?.uncaughtException(thread, throwable)
            return
        }

        // Write crash report
        CrashReportWriter.write(context, thread, throwable)

        // Delegate to previous handler (likely system default)
        previousHandler?.uncaughtException(thread, throwable)
    }

    /** Utility for generating crash report content. */
    private object CrashReportWriter {
        val isWriting = AtomicBoolean(false)
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        fun write(context: Context, thread: Thread, throwable: Throwable) {
            if (!isWriting.compareAndSet(false, true)) return
            try {
                val crashDir = File(context.filesDir, CRASH_DIR)
                crashDir.mkdirs()

                // Enforce max reports limit
                enforceMaxReports(context)

val file = File(crashDir, generateFileName())
            val content = generateReport(context, thread, throwable)
            file.writeText(truncateToMaxLength(content))
            } catch (e: Exception) {
                // If crash reporting itself fails, do not recurse
            } finally {
                isWriting.set(false)
            }
        }

        private fun generateFileName(): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            return "crash_$timestamp.txt"
        }

        private fun generateReport(context: Context, thread: Thread, throwable: Throwable): String {
            val sb = StringBuilder()
            sb.append("=== XYKELL CRASH REPORT ===\n")
            sb.append("Timestamp: ${dateFormat.format(Date())}\n")
            sb.append("App Version: ${dev.xykell.client.runtime.XykellInfo.XYKELL_VERSION}\n")
            sb.append("Native Version: ${dev.xykell.client.runtime.XykellInfo.NATIVE_VERSION}\n")
            sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            sb.append("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}\n")
            sb.append("Thread: ${thread.name} (id=${thread.id})\n")
            sb.append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
            sb.append("\n--- Stack Trace ---\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sw)
            // Redact sensitive fields
            return redactSensitive(sb.toString())
        }

        private fun enforceMaxReports(context: Context) {
            val crashDir = File(context.filesDir, CRASH_DIR)
            val files = crashDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.lastModified() }
                ?.toMutableList()
            files?.let { list ->
                while (list.size >= MAX_REPORTS) {
                    list.first().delete()
                    list.removeAt(0)
                }
            }
        }

        fun truncateToMaxLength(text: String): String {
            return if (text.length > MAX_REPORT_SIZE) {
                text.substring(0, MAX_REPORT_SIZE) + "\n[TRUNCATED]"
            } else text
        }

        fun redactSensitive(text: String): String {
            // Redact common sensitive patterns
            // Order matters: Bearer tokens must be checked before generic authorization
            return text
                .replace(Regex("Bearer\\s+\\S+"), "Bearer ***REDACTED***")
                .replace(Regex("(?i)(access[_-]?token|refresh[_-]?token|auth[_-]?token|secret|password|credential|authorization)\\s*[:=]\\s*\\S+"), "$1=***REDACTED***")
                .replace(Regex("(?i)(cookie|session)[\\s:=]+\\S+"), "$1=***REDACTED***")
        }
    }

    /** Redact sensitive information from text. Pure function for testability. */
    internal fun redactSensitive(text: String): String = CrashReportWriter.redactSensitive(text)

    /** Truncate string to max report size. Pure function for testability. */
    internal fun truncateToMaxLength(text: String): String = CrashReportWriter.truncateToMaxLength(text)
}