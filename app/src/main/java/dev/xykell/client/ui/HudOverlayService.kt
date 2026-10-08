package dev.xykell.client.ui

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.xykell.client.NativeHud
import dev.xykell.client.NativeProfiles
import java.io.File

/**
 * Draws the HUD over the game using an ordinary Android overlay window.
 *
 * Why an overlay rather than hooking the renderer: the game's frame is not ours
 * to change, but a window above it is. This paints the *same* lines the tested
 * native renderer produces (see `NativeHud.lines`) with no game-process access
 * and no root, which keeps it honest about what is observed -- a value the
 * relay never saw renders as "--" by the renderer, not as a guess here.
 *
 * It is explicitly NOT a route to in-game rendering changes (xray, shader,
 * gui_scale). Those need a hook inside the game process.
 */
class HudOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var view: HudCanvasView? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private inner class HudCanvasView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val lines = try {
                NativeHud.lines(storeRoot(), activeProfile())
            } catch (e: Exception) {
                // A bridge failure shows nothing rather than a stale frame.
                emptyList()
            }
            val density = resources.displayMetrics.density
            for (line in lines) {
                // Clamp exactly as the native renderer does, so a viewport
                // change can never push text off-screen or invert a coordinate.
                paint.textSize = line.size * density
                paint.color = if (line.color == 0) Color.WHITE else line.color
                canvas.drawText(
                    line.text,
                    line.x.coerceIn(0f, w.toFloat()),
                    line.y.coerceIn(0f, h.toFloat()),
                    paint,
                )
            }
        }
    }

    private fun storeRoot(): String = File(filesDir, "profiles").absolutePath

    private fun activeProfile(): String = try {
        NativeProfiles.getActive(storeRoot())
    } catch (e: Exception) {
        "default"
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        thread = HandlerThread("hud-overlay").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDraw(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (view == null) attach()
        schedule()
        return START_STICKY
    }

    private fun attach() {
        val wm = windowManager ?: return
        val v = HudCanvasView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(v, params)
            view = v
        } catch (e: Exception) {
            // Overlay refused (permission revoked, or window budget spent): no
            // crash and no view left half-added.
            view = null
        }
    }

    private fun schedule() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            val v = view ?: return@postDelayed
            try {
                v.invalidate()
            } catch (e: Exception) {
                // The view is going away; the next tick stops rescheduling.
                return@postDelayed
            }
            schedule()
        }, REFRESH_MS)
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        val v = view
        view = null
        if (v != null) {
            try {
                windowManager?.removeView(v)
            } catch (e: Exception) {
                // Already detached by the window manager.
            }
        }
        thread?.quitSafely()
        thread = null
        handler = null
        running = false
        super.onDestroy()
    }

    companion object {
        /** 4 Hz: fast enough to read, slow enough not to wake the CPU. */
        private const val REFRESH_MS = 250L

        @Volatile
        private var running = false

        /**
         * Whether the overlay service is actually alive. Reported from the
         * service's own lifecycle, not from a stored preference that could
         * claim an overlay that is not there.
         */
        fun isRunning(): Boolean = running

        fun canDraw(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!canDraw(ctx)) return
            ctx.startService(Intent(ctx, HudOverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HudOverlayService::class.java))
        }
    }
}