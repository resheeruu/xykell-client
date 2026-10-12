package dev.xykell.client.ui

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.xykell.client.NativeProfiles
import dev.xykell.client.runtime.toolbox.ToolboxModel

/**
 * A draggable toolbox over the game: quick module toggles without leaving it.
 *
 * **Why a window.** Same reasoning as [HudOverlayService] -- the game's frame is
 * not ours to draw in, but a window above it is, and no root or game-process
 * access is needed. What the toolbox *does* need is the same "display over other
 * apps" permission the HUD already asks for, so enabling the toolbox prompts
 * for nothing new.
 *
 * **What it deliberately does not do.** It writes module flags and nothing else.
 * It cannot change rendering, hold wallet authority, or act on the game's
 * process; a toggle here is the same toggle the Modules screen flips, from a
 * screen that is already open. The relay picks the change up from the profile
 * on its next read, which is the existing mechanism rather than a new one.
 *
 * **Collapsed by default.** A toolbox covering the game is worse than no
 * toolbox, so it starts as a small pill showing how many modules are on, and
 * expands only on a tap.
 */
class ToolboxOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var view: ToolboxView? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var catalogJson: String? = null
    private var profileJson: String? = null

    @SuppressLint("ViewConstructor")
    private inner class ToolboxView(context: Context) : View(context) {

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC101418.toInt() }
        private val row = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        private val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = 0xFF9AA5B1.toInt()
        }
        private val dotOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CAF50.toInt() }
        private val dotOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55606B.toInt() }

        private val density = resources.displayMetrics.density
        private val pad = 12 * density
        private val rowH = 44 * density
        private val corner = 14 * density

        /** Whether the panel is expanded. Owned here: [state] rebuilds each draw. */
        private var expanded = false

        /** The params this view was added with; there is no public getter. */
        var params: WindowManager.LayoutParams? = null

        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        /** Set on the drag that exceeded the touch slop, so a tap is not also a drag. */
        private var moved = false

        private fun state(): ToolboxModel.State = ToolboxModel.build(catalogJson, profileJson)

        private fun collapsedH(): Float = (32 * density)

        private fun expandedH(s: ToolboxModel.State): Float =
            collapsedH() + 24 * density + rowH * minOf(s.rows.size, MAX_ROWS)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = (params?.x ?: 0).toFloat()
                    startY = (params?.y ?: 0).toFloat()
                    dragging = true
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // Only commit to a drag past the slop, so a tap that jitters
                    // still registers as a tap.
                    if (!moved && (dx * dx + dy * dy) < (8 * density) * (8 * density)) return true
                    moved = true
                    val p = params ?: return true
                    p.x = (startX + dx).toInt()
                    p.y = (startY + dy).toInt()
                    try {
                        windowManager?.updateViewLayout(this, p)
                    } catch (e: Exception) {
                        // The window can go away under us; nothing to recover.
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (!moved) handleTap(event.x, event.y)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun handleTap(x: Float, y: Float) {
            val s = state()
            if (x >= width - collapsedW() && y < collapsedH()) {
                // The pill toggles expansion; anywhere else in the header drags.
                expanded = !expanded
                invalidate()
                return
            }
            if (y > collapsedH() + 24 * density) {
                val index = ((y - collapsedH() - 24 * density) / rowH).toInt()
                val target = s.rows.getOrNull(index)
                if (target != null) toggle(target.id)
            }
        }

        private fun collapsedW(): Float = (64 * density)

        private fun toggle(id: String) {
            val updated = ToolboxModel.withToggled(profileJson, id)
            // A null here means the profile could not be parsed. Refusing is the
            // whole point: writing back a profile we failed to read would discard
            // whatever else it held.
            if (updated == null) return
            val name = activeProfile()
            val ok = try {
                NativeProfiles.importProfileJson(storeRoot(), name, updated)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                profileJson = updated
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val s = state()
            val h = if (expanded) expandedH(s) else collapsedH()
            val w = if (expanded) width.toFloat() else collapsedW()
            canvas.drawRoundRect(RectF(0f, 0f, w, h), corner, corner, bg)

            row.textSize = 15 * density
            meta.textSize = 11 * density

            if (!expanded) {
                val label = "${s.enabledCount}"
                canvas.drawText(
                    label,
                    pad,
                    h / 2f - (row.descent() + row.ascent()) / 2f,
                    row,
                )
                return
            }

            val header = "Toolbox  ${s.enabledCount}/${s.rows.size}"
            canvas.drawText(header, pad, 22 * density, row)

            s.rows.take(MAX_ROWS).forEachIndexed { i, r ->
                val top = collapsedH() + 24 * density + rowH * i
                canvas.drawCircle(pad + 4 * density, top + rowH / 2f, 5 * density, if (r.enabled) dotOn else dotOff)
                canvas.drawText(r.name, pad + 16 * density, top + 18 * density, row)
                canvas.drawText(r.category, pad + 16 * density, top + 33 * density, meta)
            }

            s.problem?.let {
                canvas.drawText(it, pad, h - 6 * density, meta)
            }
        }
    }

    private fun storeRoot(): String = NativeProfiles.root(this)

    private fun activeProfile(): String = try {
        NativeProfiles.getActive(storeRoot())
    } catch (e: Exception) {
        "default"
    }

    private fun loadDocuments() {
        // The catalog comes from the same asset the Modules screen reads, and
        // the profile from the same native store, so both refresh paths are the
        // ones already in use.
        catalogJson = try {
            assets.open("features.json").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        profileJson = try {
            NativeProfiles.getProfileJson(storeRoot(), activeProfile())
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        thread = HandlerThread("toolbox-overlay").also { it.start() }
        handler = Handler(thread!!.looper)
        loadDocuments()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDraw(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (view == null) attach()
        handler?.postDelayed(refresh, REFRESH_MS)
        return START_STICKY
    }

    /** Re-reads the profile so a change made in the app shows up here too. */
    private val refresh = object : Runnable {
        override fun run() {
            // Reading the profile touches the bridge, so it stays here on the
            // HandlerThread rather than the UI thread.
            loadDocuments()
            // postInvalidate() for the same reason HudOverlayService uses it:
            // View.invalidate() is documented as not thread-safe.
            view?.postInvalidate()
            handler?.postDelayed(this, REFRESH_MS)
        }
    }

    private fun attach() {
        val wm = windowManager ?: return
        val v = ToolboxView(this)
        val params = WindowManager.LayoutParams(
            // A fixed width rather than WRAP_CONTENT: the panel changes shape
            // when it expands, and a wrap-content window resizes under a finger
            // mid-drag.
            (280 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // No FLAG_NOT_TOUCHABLE: the toolbox has to receive taps. The HUD
            // deliberately sets it and therefore cannot be touched at all.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * resources.displayMetrics.density).toInt()
            y = (200 * resources.displayMetrics.density).toInt()
        }
        try {
            wm.addView(v, params)
            v.params = params
            view = v
        } catch (e: Exception) {
            view = null
        }
    }

    override fun onDestroy() {
        handler?.removeCallbacks(refresh)
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        thread?.quitSafely()
        thread = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val MAX_ROWS = 12

        @Volatile
        private var running = false

        /** From the service lifecycle, not a preference that could claim a
         *  toolbox that is not there. */
        fun isRunning(): Boolean = running

        /** Same permission the HUD uses; the toolbox needs nothing new. */
        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }

        fun start(ctx: Context) {
            if (!canDraw(ctx)) return
            ctx.startService(Intent(ctx, ToolboxOverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ToolboxOverlayService::class.java))
        }

        /** Start or stop, whichever is the opposite of the current state. */
        fun toggle(ctx: Context) {
            if (isRunning()) stop(ctx) else start(ctx)
        }
    }
}