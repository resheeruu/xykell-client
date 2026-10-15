package dev.xykell.client

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Appends a startup breadcrumb trail to a file a human can retrieve.
 *
 * **Why this exists separately from [CrashReporter].** A crash reporter only
 * leaves evidence when a Java exception reaches
 * `Thread.setDefaultUncaughtExceptionHandler`. Three common startup failures do
 * not go through it:
 *
 * - a **native** crash (SIGSEGV/SIGABRT in `libxykellcore.so`) never becomes a
 *   Java throwable at all;
 * - a death **before** `MainActivity.onCreate` — in an auto-initialised
 *   ContentProvider, or while the Application itself is starting;
 * - a **system kill** (ANR, low-memory) which is not a crash.
 *
 * In all three cases the process simply stops. A marker written immediately
 * *before* the risky call and immediately *after* it localises the failure
 * without needing to catch anything, which is the only mechanism that works
 * when the process dies by means other than a Java exception.
 *
 * **Write target.** `/storage/emulated/0/Android/data/<pkg>` cannot be read by
 * other apps on modern Android, and a direct write to Downloads is blocked by
 * scoped storage, so this goes through the MediaStore Downloads collection,
 * which needs no permission for an app's own insert.
 */
object StartupTrace {

    private const val TAG = "XykellTrace"
    private const val FILE_NAME = "xykell-startup-trace.txt"

    private val lock = Any()
    private val lines = StringBuilder()

    /** Resolved once per process so repeated markers append instead of forking files. */
    private var uri: Uri? = null
    private var started = false

    /** Records [label] and immediately flushes, so a death after it still shows it. */
    fun mark(label: String) {
        val line = "[%d ms] %s".format(
            System.currentTimeMillis() - START_MS,
            label,
        )
        synchronized(lock) {
            lines.appendLine(line)
            flushLocked(label)
        }
        Log.i(TAG, line)
    }

    private val START_MS: Long = System.currentTimeMillis()

    /**
     * Best-effort flush. Never throws: a trace that replaces the crash it was
     * meant to record is worse than no trace.
     */
    private fun flushLocked(label: String) {
        try {
            val context = appContext ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val target = uri ?: resolveOrInsert(context).also { uri = it } ?: run {
                Log.w(TAG, "no Downloads uri for '$label'")
                return
            }
            val body = buildString {
                appendLine("Xykell startup trace (last marker last)")
                append("android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), ")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("----")
                append(lines)
            }
            context.contentResolver.openOutputStream(target, "wa")?.use {
                it.write(body.toByteArray())
            }
        } catch (e: Exception) {
            Log.w(TAG, "trace flush failed at '$label': $e")
        }
    }

    /** Finds the existing entry so appends do not create one file per marker. */
    private fun resolveOrInsert(context: Context): Uri? {
        val resolver = context.contentResolver
        val existing = try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString(),
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
        if (existing != null) return existing

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    /** Set as early as possible; [mark] before this is a no-op rather than a crash. */
    @Volatile
    var appContext: Context? = null
        private set

    fun attach(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        mark("application.attach")
    }
}