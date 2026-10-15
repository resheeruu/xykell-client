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

        /**
         * First visible row.
         *
         * The catalog yields 124 rows and at most [MAX_ROWS] fit, so without this
         * the rows past the fold were unreachable -- absent, with nothing on
         * screen to say they existed.
         */
        private var scroll = 0

        /**
         * Last computed state.
         *
         * Held rather than recomputed because [onDraw] runs per frame and
         * [ToolboxModel.build] parses the whole catalog document every call. It
         * is rebuilt only when the documents actually change, via [rebuild].
         */
        private var cached: ToolboxModel.State? = null

        /** The params this view was added with; there is no public getter. */
        var params: WindowManager.LayoutParams? = null

        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        /** Set on the drag that exceeded the touch slop, so a tap is not also a drag. */
        private var moved = false
        /** Whether this gesture began in the body, where it scrolls instead of moving. */
        private var scrolling = false

        private fun state(): ToolboxModel.State =
            cached ?: ToolboxModel.State(emptyList(), "not loaded").also { cached = it }

        /** Recomputes [cached]. Called only when catalog or profile changed. */
        fun rebuild() {
            cached = ToolboxModel.build(catalogJson, profileJson)
            scroll = scroll.coerceIn(0, ToolboxModel.maxScroll(state(), MAX_ROWS))
        }

        private fun collapsedH(): Float = (32 * density)

        private fun expandedH(s: ToolboxModel.State): Float =
            collapsedH() + HEADER_H_DP * density +
                rowH * minOf(s.rows.size, MAX_ROWS)

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
                    // In the body a vertical drag scrolls rows; on the header, and
                    // while collapsed, it moves the window. Without this split the
                    // list could not be scrolled at all, because dragging always
                    // moved the window away first.
                    scrolling = expanded && event.y > bodyTop()
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
                    if (scrolling) {
                        val max = ToolboxModel.maxScroll(state(), MAX_ROWS)
                        val before = scroll
                        // Dragging down reveals earlier rows, the way every list
                        // on the platform behaves.
                        scroll = (scroll - (dy / rowH).toInt()).coerceIn(0, max)
                        if (scroll != before) invalidate()
                        return true
                    }
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
            if (!expanded) {
                // Collapsed the window is exactly the pill, so the whole thing is
                // the control. It used to test the far edge of the full-width
                // window instead, which is the opposite side from the pill the
                // user can see.
                setExpanded(true)
                return
            }
            if (y <= bodyTop()) {
                setExpanded(false)
                return
            }
            val index = ((y - bodyTop()) / rowH).toInt()
            val target = ToolboxModel.visibleRows(s, scroll, MAX_ROWS).getOrNull(index)
            if (target != null) toggle(target.id)
        }

        /**
         * Expands or collapses, resizing the window to match.
         *
         * The window is touchable, so its size is also its touch-blocking size. A
         * collapsed pill inside a full-width window silently swallowed taps meant
         * for the game underneath across the difference.
         */
        private fun setExpanded(next: Boolean) {
            if (expanded == next) return
            expanded = next
            val p = params ?: return
            p.width = ((if (expanded) EXPANDED_W_DP else COLLAPSED_W_DP) * density).toInt()
            try {
                windowManager?.updateViewLayout(this, p)
            } catch (e: Exception) {
                // Nothing to recover; the service will be restarted if needed.
            }
            invalidate()
        }

        private fun collapsedW(): Float = (COLLAPSED_W_DP * density)

        private fun bodyTop(): Float = collapsedH() + HEADER_H_DP * density

        private fun toggle(id: String) {
            val updated = ToolboxModel.withToggled(profileJson, id)
            // A null here means the profile could not be parsed. Refusing is the
            // whole point: writing back a profile we failed to read would discard
            // whatever else it held.
            if (updated == null) return
            // The flip above is pure JSON and cheap. The bridge call is not, and
            // it goes on the HandlerThread for the same reason the refresh does.
            handler?.post {
                val ok = try {
                    NativeProfiles.importProfileJson(storeRoot(), activeProfile(), updated)
                } catch (e: Exception) {
                    false
                }
                view?.post {
                    if (ok) {
                        profileJson = updated
                        rebuild()
                        invalidate()
                    }
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val s = state()
            val h = if (expanded) expandedH(s) else collapsedH()
            val w = if (expanded) (EXPANDED_W_DP * density) else collapsedW()
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

            val rows = ToolboxModel.visibleRows(s, scroll, MAX_ROWS)
            rows.forEachIndexed { i, r ->
                val top = bodyTop() + rowH * i
                canvas.drawCircle(pad + 4 * density, top + rowH / 2f, 5 * density, if (r.enabled) dotOn else dotOff)
                canvas.drawText(r.name, pad + 16 * density, top + 18 * density, row)
                canvas.drawText(r.category, pad + 16 * density, top + 33 * density, meta)
            }

            // Without this the list looks complete while 112 rows sit past the
            // fold, which reads as "that is all of them".
            val max = ToolboxModel.maxScroll(s, MAX_ROWS)
            if (max > 0) {
                val label = "${scroll + 1}-${scroll + rows.size} of ${s.rows.size}"
                canvas.drawText(label, w - pad - meta.measureText(label), 22 * density, meta)
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

    private fun loadCatalog() {
        // The catalog is a packaged asset and cannot change while the app runs,
        // so it is read once. Re-reading and re-parsing it on every refresh tick
        // is pure waste.
        if (catalogJson != null) return
        catalogJson = try {
            assets.open("features.json").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadDocuments() {
        // The profile comes from the same native store the Modules screen reads,
        // so both refresh paths are the ones already in use.
        val previous = profileJson
        profileJson = try {
            NativeProfiles.getProfileJson(storeRoot(), activeProfile())
        } catch (e: Exception) {
            null
        }
        // Rebuilding parses the whole catalog; only do it when something changed.
        if (previous != profileJson) view?.rebuild()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        thread = HandlerThread("toolbox-overlay").also { it.start() }
        handler = Handler(thread!!.looper)
        loadCatalog()
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
            (EXPANDED_W_DP * resources.displayMetrics.density).toInt(),
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
            // The documents were loaded in onCreate, before this view existed,
            // so the first draw would otherwise render the "not loaded"
            // placeholder until the profile happened to change.
            v.rebuild()
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
        private const val COLLAPSED_W_DP = 64
        private const val EXPANDED_W_DP = 280
        private const val HEADER_H_DP = 24

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