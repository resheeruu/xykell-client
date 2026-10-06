package dev.xykell.client.runtime.capture

import android.app.Notification
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import dev.xykell.client.R
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.HandlerThread
import android.provider.MediaStore
import android.hardware.display.DisplayManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot screen capture owner (mediaProjection foreground-service type).
 *
 * Started only by ScreenshotFragment after the user approves the system
 * consent dialog; never exported; never auto-started. Each run consumes one
 * consent token: exactly one getMediaProjection and exactly one
 * createVirtualDisplay, which is the rule Android 14+ enforces (a second
 * VirtualDisplay on the same projection throws SecurityException).
 *
 * No capture path ever writes observation data anywhere: pixels go to the
 * gallery (API 29+) or to the app cache, then the service stops itself.
 */
class CaptureService : Service() {

    private val finished = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first: startForegroundService gives ~5s to get here.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                statusNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0 // FGS types do not exist before API 29
                },
            )
        } catch (e: Exception) {
            finish(null, "foreground-start-denied")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val width = intent?.getIntExtra(EXTRA_WIDTH, 0) ?: 0
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 0) ?: 0
        val dpi = intent?.getIntExtra(EXTRA_DPI, 0) ?: 0
        val share = intent?.getBooleanExtra(EXTRA_SHARE, false) ?: false

        if (data == null || width <= 0 || height <= 0 || dpi <= 0) {
            finish(null, "bad-capture-parameters")
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val media = manager.getMediaProjection(resultCode, data)
        if (media == null) {
            finish(null, "projection-unavailable")
            return START_NOT_STICKY
        }
        projection = media
        // Android 14+ requires the callback before createVirtualDisplay, and
        // onStop is where the system reports the user hitting Stop/locking.
        media.registerCallback(CaptureCallback(), Handler(Looper.getMainLooper()))

        val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = images
        val worker = HandlerThread("xykell-capture").also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)

        try {
            display = media.createVirtualDisplay(
                "Xykell Capture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                images.surface,
                null,
                null,
            )
        } catch (e: Exception) {
            tearDown()
            finish(null, "virtual-display-failed")
            return START_NOT_STICKY
        }
        if (display == null) {
            tearDown()
            finish(null, "virtual-display-failed")
            return START_NOT_STICKY
        }

        images.setOnImageAvailableListener({ source ->
            if (finished.get()) return@setOnImageAvailableListener
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val packed = PixelPacker.repack(bytes, image.width, image.height, plane.rowStride)
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(packed))
                deliver(bitmap, share)
            } catch (e: Exception) {
                finish(null, "capture-failed")
            } finally {
                image.close()
            }
        }, handler)

        return START_NOT_STICKY
    }

    /** Turn the captured pixels into the requested destination, then stop. */
    private fun deliver(bitmap: Bitmap, share: Boolean) {
        try {
            if (share) {
                val uri = writeCache(bitmap)
                if (uri == null) {
                    finish(null, "share-write-failed")
                } else {
                    finish(uri, null)
                }
            } else {
                val (uri, note) = saveToGallery(bitmap)
                if (uri == null) finish(null, "save-failed") else finish(uri, note)
            }
        } finally {
            bitmap.recycle()
            tearDown()
        }
    }

    /**
     * Save to the shared gallery. API 29+ needs no permission (scoped
     * storage); API 28 cannot write shared media without
     * WRITE_EXTERNAL_STORAGE, so it lands in the app's own pictures folder
     * and the note says exactly that instead of requesting a new permission.
     */
    private fun saveToGallery(bitmap: Bitmap): Pair<Uri?, String?> {
        val name = "xykell-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Xykell",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null to null
            contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: run {
                contentResolver.delete(uri, null, null)
                return null to null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri to null
        }

        val dir = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir,
            "Xykell",
        )
        if (!dir.isDirectory && !dir.mkdirs()) return null to null
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file) to "api28-app-folder"
    }

    /** Cache copy handed out through the FileProvider (no permission needed). */
    private fun writeCache(bitmap: Bitmap): Uri? {
        val dir = File(cacheDir, "screenshots")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "xykell-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private inner class CaptureCallback : Callback() {
        override fun onStop() {
            // User pressed Stop in the status-bar chip, or locked the screen.
            finish(null, "projection-stopped")
            tearDown()
        }
    }

    private fun tearDown() {
        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        thread?.quitSafely()
        thread = null
    }

    private fun finish(uri: Uri?, error: String?) {
        if (!finished.compareAndSet(false, true)) return
        val callback = CaptureResult.onFinished
        CaptureResult.onFinished = null
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(uri, error)
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private fun statusNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.capture_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xykell-capture"
        private const val NOTIFICATION_ID = 2

        const val EXTRA_RESULT_CODE = "xykell.capture.resultCode"
        const val EXTRA_RESULT_DATA = "xykell.capture.resultData"
        const val EXTRA_WIDTH = "xykell.capture.width"
        const val EXTRA_HEIGHT = "xykell.capture.height"
        const val EXTRA_DPI = "xykell.capture.dpi"
        const val EXTRA_SHARE = "xykell.capture.share"
    }
}

/** Result handoff from [CaptureService] back to the screen that asked. */
object CaptureResult {
    @Volatile
    var onFinished: ((Uri?, String?) -> Unit)? = null
}
