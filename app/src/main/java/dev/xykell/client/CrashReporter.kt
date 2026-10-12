package dev.xykell.client

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures an uncaught exception and writes the trace somewhere a human can
 * retrieve it without a cable.
 *
 * **Why this exists.** A launch crash was reported and no stack trace was
 * obtainable: `logcat` from Termux shows only its own and system entries, the
 * crash buffer is not readable, and there is no root. The app is the only
 * process that can see the exception, so the app records it.
 *
 * **Why Downloads specifically.** `/storage/emulated/0/Android/data/<pkg>` is not
 * readable by other apps on modern Android, so a crash file written there would
 * be invisible. MediaStore's Downloads collection needs no permission for the
 * app's own insert, and the file can simply be shared or opened.
 *
 * **What is written.** Only this app's own stack trace, capped, plus the
 * thread and timestamp. No other app's data, no packet contents, no profile.
 * The file is truncated rather than appended so a repeat crash does not grow it.
 *
 * **Failures here are swallowed deliberately.** A crash reporter that throws
 * replaces one crash with a different, less useful one.
 */
object CrashReporter {

    private const val TAG = "XykellCrash"
    private const val FILE_NAME = "xykell-crash.txt"
    private const val MAX_CHARS = 24_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(app, thread, error)
            } catch (ignored: Throwable) {
                // Reporting must never be the cause of the crash it reports.
            }
            // Hand back to the platform handler so the crash dialog still appears.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val text = buildTrace(context, thread, error)
        Log.e(TAG, "uncaught exception captured:\n$text")
        writeToDownloads(context, text)
    }

    /** Read from PackageManager so this needs no BuildConfig codegen. */
    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (e: Exception) {
        "unknown"
    }

    private fun buildTrace(context: Context, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        val when0 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = buildString {
            appendLine("Xykell uncaught exception")
            appendLine("when:  $when0")
            appendLine("thread: ${thread.name}")
            appendLine("app version: ${appVersion(context)}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, android ${Build.VERSION.RELEASE}")
            appendLine("---- stack ----")
        }
        return (header + sw.toString()).take(MAX_CHARS)
    }

    private fun writeToDownloads(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, text)
        } else {
            // API 28: no Downloads collection, so use the app's external files
            // dir. Less reachable, but it is all this API level offers.
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, FILE_NAME).writeText(text)
        }
    }

    private fun writeViaMediaStore(context: Context, text: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "could not write crash file: $e")
        }
    }
}