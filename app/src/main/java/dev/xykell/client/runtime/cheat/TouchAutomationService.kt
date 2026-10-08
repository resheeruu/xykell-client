package dev.xykell.client.runtime.cheat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.xykell.client.R
import kotlin.math.abs
import kotlin.random.Random

/**
 * Real input cheat: injects taps through the Android accessibility gesture
 * API. Works over the foreground app (including Minecraft) on any server —
 * no game injection, no packet access, no memory access. The user must enable
 * this service in system settings (canPerformGestures).
 *
 * Three exclusive modes, driven from AutoclickerFragment or the overlay FAB:
 *  - click:  tap the saved target at CPS ± jitter until stopped
 *  - record: full-screen overlay captures taps into a macro (prefs)
 *  - replay: tap the saved macro steps in a loop until stopped
 */
class TouchAutomationService : AccessibilityService() {

    companion object {
        const val PREFS = "autoclicker"

        @Volatile
        var instance: TouchAutomationService? = null
            private set

        fun settings(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private var fabView: TextView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var recorder: FrameLayout? = null
    private var recorderCount: TextView? = null

    private var clicking = false
    private var replaying = false
    private var recording = false
    private var tapFailures = 0
    private var steps: List<MacroStep> = emptyList()
    private var recordSteps: List<MacroStep> = emptyList()
    private var lastTapAtMs = 0L

    fun isClicking() = clicking
    fun isReplaying() = replaying
    fun isRecording() = recording

    fun toggleClick() {
        if (clicking) stopClick() else startClick()
    }

    fun toggleReplay() {
        if (replaying) stopReplay() else startReplay()
    }

    fun toggleRecord() {
        if (recording) stopRecording() else startRecording()
    }

    fun stopEverything() {
        stopClick()
        stopReplay()
        stopRecording()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        addFab()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = stopEverything()

    override fun onDestroy() {
        stopEverything()
        removeFab()
        instance = null
        super.onDestroy()
    }

    // --- click ------------------------------------------------------------

    private fun startClick() {
        if (clicking || replaying || recording) return
        clicking = true
        tapFailures = 0
        updateFab()
        handler.post(clickTick)
    }

    private fun stopClick() {
        if (!clicking) return
        clicking = false
        handler.removeCallbacks(clickTick)
        updateFab()
    }

    private val clickTick = object : Runnable {
        override fun run() {
            if (!clicking) return
            val sp = settings(this@TouchAutomationService)
            val (x, y) = MacroStore.scaleToScreen(
                sp.getFloat("nx", 0.5f).toDouble(),
                sp.getFloat("ny", 0.75f).toDouble(),
                widthPx(),
                heightPx(),
            )
            val delay = ClickSchedule.nextDelayMs(
                sp.getInt("cps", 10),
                sp.getInt("jitterPct", 10),
                Random.Default,
            )
            val accepted = tapAt(x, y) {
                tapFailures = 0
                if (clicking) handler.postDelayed(this, delay)
            }
            if (!accepted) {
                tapFailures++
                if (tapFailures >= 5) {
                    stopClick()
                } else {
                    handler.postDelayed(this, delay)
                }
            }
        }
    }

    // --- replay -----------------------------------------------------------

    private fun startReplay() {
        if (clicking || replaying || recording) return
        val loaded = MacroStore.decode(settings(this).getString("macro", ""))
        if (loaded.isEmpty()) return
        steps = loaded
        replaying = true
        tapFailures = 0
        playStep(0, loaded[0].delayMs)
    }

    private fun stopReplay() {
        if (!replaying) return
        replaying = false
        handler.removeCallbacksAndMessages(null)
        steps = emptyList()
    }

    private fun playStep(index: Int, delayMs: Long) {
        if (!replaying) return
        val s = steps[index]
        val (x, y) = MacroStore.scaleToScreen(s.nx, s.ny, widthPx(), heightPx())
        handler.postDelayed({
            if (!replaying) return@postDelayed
            val advance = {
                if (replaying) {
                    val next = (index + 1) % steps.size
                    playStep(next, steps[next].delayMs)
                }
            }
            val accepted = tapAt(x, y, advance)
            if (accepted) {
                tapFailures = 0
            } else {
                tapFailures++
                if (tapFailures >= 5) stopReplay() else playStep(index, delayMs)
            }
        }, delayMs)
    }

    // --- record -----------------------------------------------------------

    private fun startRecording() {
        if (clicking || replaying || recording) return
        recording = true
        recordSteps = emptyList()
        lastTapAtMs = SystemClock.uptimeMillis()

        val base = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val now = SystemClock.uptimeMillis()
                    val gap = if (recordSteps.isEmpty()) 0L else now - lastTapAtMs
                    lastTapAtMs = now
                    val w = width.coerceAtLeast(1)
                    val h = height.coerceAtLeast(1)
                    recordSteps = MacroStore.append(
                        recordSteps,
                        MacroStep(gap, (event.x / w).toDouble(), (event.y / h).toDouble()),
                    )
                    recorderCount?.text = getString(
                        R.string.ac_recording_count,
                        recordSteps.size,
                        MacroStore.MAX_STEPS,
                    )
                }
                return true
            }
        }

        val container = FrameLayout(this).apply {
            addView(base, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        val density = resources.displayMetrics.density
        val hint = TextView(this).apply {
            text = getString(R.string.ac_recording_count, 0, MacroStore.MAX_STEPS)
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        recorderCount = hint
        val stop = Button(this).apply {
            text = getString(R.string.ac_stop_record)
            minHeight = (48 * density).toInt()
            minimumHeight = (48 * density).toInt()
            setOnClickListener { stopRecording() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC0D1526.toInt())
            setPadding((12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (4 * density).toInt())
            addView(hint, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stop)
        }
        container.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(container, params)
        recorder = container
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        settings(this).edit()
            .putString("macro", MacroStore.encode(recordSteps))
            .apply()
        recordSteps = emptyList()
        recorder?.let { runCatching { wm.removeView(it) } }
        recorder = null
        recorderCount = null
    }

    // --- module plan ------------------------------------------------------

    /**
     * Play a relay module's tap plan for this tick.
     *
     * Distinct from replay mode: a plan is one-shot and its delays are already
     * resolved by the plan itself, so this fires the steps in order and stops.
     * It refuses to start while click/record/replay owns the service — those
     * modes own the gesture queue and two writers would interleave taps into
     * the same in-flight gesture. The plan is abandoned mid-flight if one of
     * those modes starts, so a plan never outlives the mode that began it.
     */
    fun playPlan(steps: List<MacroStep>) {
        if (steps.isEmpty()) return
        if (clicking || replaying || recording) return
        var index = 0

        fun play() {
            val s = steps.getOrNull(index) ?: return
            val (x, y) = MacroStore.scaleToScreen(s.nx, s.ny, widthPx(), heightPx())
            tapAt(x, y) {
                index++
                if (index < steps.size && !clicking && !replaying && !recording) play()
            }
        }
        handler.postDelayed({ play() }, steps.first().delayMs)
    }

    // --- tap --------------------------------------------------------------

    private fun tapAt(x: Float, y: Float, done: () -> Unit): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post(done)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post(done)
            }
        }, null)
    }

    private fun widthPx() = resources.displayMetrics.widthPixels
    private fun heightPx() = resources.displayMetrics.heightPixels

    // --- overlay FAB ------------------------------------------------------

    private fun addFab() {
        if (fabView != null) return
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val sp = settings(this)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt("fabX", widthPx() - size - (12 * density).toInt())
            y = sp.getInt("fabY", (140 * density).toInt())
        }
        val view = TextView(this).apply {
            text = "▶"
            textSize = 20f
            setTextColor(0xFF0D1526.toInt())
            setBackgroundColor(0xE64FD8C7.toInt())
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.ac_fab_desc)
            setOnTouchListener(makeFabDrag(params))
        }
        wm.addView(view, params)
        fabView = view
        fabParams = params
        updateFab()
    }

    private fun makeFabDrag(params: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0
        var downY = 0
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        fabView?.let { wm.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleClick()
                    } else {
                        settings(this).edit()
                            .putInt("fabX", params.x)
                            .putInt("fabY", params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateFab() {
        val view = fabView ?: return
        view.text = if (clicking) "⏸" else "▶"
        view.setBackgroundColor(if (clicking) 0xE6E05D5D.toInt() else 0xE64FD8C7.toInt())
        fabParams?.let { params -> runCatching { wm.updateViewLayout(view, params) } }
    }

    private fun removeFab() {
        fabView?.let { runCatching { wm.removeView(it) } }
        fabView = null
        fabParams = null
    }
}
