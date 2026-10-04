package dev.xykell.client.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

/**
 * ViewPager that can disable swipe gestures for reduced-motion mode.
 */
class NonSwipeableViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : androidx.viewpager.widget.ViewPager(context, attrs) {

    private var swipeEnabled = true

    fun setSwipeEnabled(enabled: Boolean) {
        swipeEnabled = enabled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return swipeEnabled && super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        return swipeEnabled && super.onInterceptTouchEvent(event)
    }
}