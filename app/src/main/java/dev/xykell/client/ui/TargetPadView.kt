package dev.xykell.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import dev.xykell.client.R

/**
 * Tap-to-place target pad: normalized (0..1) point shown as a crosshair.
 * Coordinates are scaled to the real screen at dispatch time by the service,
 * so no knowledge of Minecraft control layout is needed.
 */
class TargetPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onPoint: ((Float, Float) -> Unit)? = null

    private var nx = 0.5f
    private var ny = 0.75f

    private val density = resources.displayMetrics.density
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.xykell_border)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.xykell_accent)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.xykell_muted)
    }

    fun setPoint(x: Float, y: Float) {
        nx = x.coerceIn(0f, 1f)
        ny = y.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = borderPaint.strokeWidth
        canvas.drawRect(inset, inset, width - inset, height - inset, borderPaint)
        val cx = nx * width
        val cy = ny * height
        canvas.drawLine(cx, inset, cx, height - inset, linePaint)
        canvas.drawLine(inset, cy, width - inset, cy, linePaint)
        canvas.drawCircle(cx, cy, 8f * density, dotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event)
            }
            MotionEvent.ACTION_MOVE -> updateFromTouch(event)
            MotionEvent.ACTION_UP -> {
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(event: MotionEvent) {
        nx = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        ny = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        invalidate()
        onPoint?.invoke(nx, ny)
    }
}
