package com.example.mangabuffauto

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/**
 * Detects an intentional horizontal swipe that starts very close to either
 * physical edge of the screen and delegates it to WebView history.
 *
 * Important: ordinary WebView scrolling/tapping is untouched. We only consume
 * the gesture after the horizontal threshold has been crossed.
 */
class EdgeBackGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    var onEdgeBackGesture: (() -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeSizePx = (32f * resources.displayMetrics.density).toInt()
    private val triggerDistancePx = (72f * resources.displayMetrics.density).toInt()

    private var trackingEdge = false
    private var triggered = false
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                trackingEdge = ev.x <= edgeSizePx || ev.x >= width - edgeSizePx
                triggered = false
                // Let WebView receive the normal DOWN. We only intercept after
                // an intentional horizontal edge swipe is recognized.
                return super.onInterceptTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (trackingEdge && !triggered) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val horizontal = abs(dx) > abs(dy) * 1.25f
                    if (horizontal && abs(dx) >= triggerDistancePx) {
                        triggered = true
                        trackingEdge = false
                        // Do not allow the gesture to become Activity finish/back.
                        // Consume it here and perform WebView history navigation.
                        onEdgeBackGesture?.invoke()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trackingEdge = false
                triggered = false
            }
        }

        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once recognized, keep consuming the remainder of this gesture.
        if (triggered) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                triggered = false
                trackingEdge = false
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
