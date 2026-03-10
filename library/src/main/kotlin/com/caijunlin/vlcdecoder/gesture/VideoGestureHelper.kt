package com.caijunlin.vlcdecoder.gesture

import android.graphics.Bitmap
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.scale
import com.caijunlin.vlcdecoder.core.StreamWebView
import com.caijunlin.vlcdecoder.gles.IVideoRenderClient
import com.caijunlin.vlcdecoder.gles.VLCRenderPool
import com.caijunlin.vlcdecoder.widget.WidgetManager
import kotlin.math.abs

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   系统级拖拽手势处理（使用长按延时触发机制）
 */
class VideoGestureHelper(
    private val client: IVideoRenderClient,
    private val dragHostView: View,
    private val onDropAction: (centerX: Float, centerY: Float, width: Int, height: Int) -> Unit
) {

    var isDragInProgress: Boolean = false

    // 记录手指按下的初始位置
    private var downX = 0f
    private var downY = 0f

    // 临时保存当前触发手势的上下文信息，供延时任务使用
    private var currentWebView: StreamWebView? = null
    private var currentElementId: String? = null

    // 获取 Android 系统默认的防抖滑动距离（通常是 8dp）
    // 允许用户在长按时手指有极其轻微的颤动，但不应超过这个阈值
    private val touchSlop = ViewConfiguration.get(dragHostView.context).scaledTouchSlop

    // 触发拖拽的延时时间，400ms 是一个兼顾响应速度和防误触的黄金时间
    private val longPressTimeout = 400L

    private class DragSessionState(
        val width: Int,
        val height: Int,
        val touchOffsetX: Float,
        val touchOffsetY: Float,
        var lastVisualX: Float,
        var lastVisualY: Float
    )

    // 长按时间到达后，真正触发拖拽的任务
    private val longPressRunnable = Runnable {
        if (isDragInProgress || currentWebView == null || currentElementId == null) return@Runnable
        isDragInProgress = true

        val webView = currentWebView!!
        val elementId = currentElementId!!

        WidgetManager.getBoundingClientRect(
            webView,
            elementId
        ) { physicalW, physicalH, scaleX, scaleY ->
            if (physicalW == 0 && physicalH == 0) {
                isDragInProgress = false
                return@getBoundingClientRect
            }

            // 计算出手指相对于视图的比例坐标
            val touchX = downX / scaleX
            val touchY = downY / scaleY

            VLCRenderPool.captureClientFrame(client) { bitmap ->
                if (bitmap != null) {
                    startNativeDrag(bitmap, touchX, touchY, physicalW, physicalH)
                } else {
                    isDragInProgress = false
                }
            }
        }
    }

    fun onTouchEvent(
        event: MotionEvent,
        webView: StreamWebView,
        elementId: String
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isDragInProgress) return

                // 记录按下时的环境与坐标
                downX = event.x
                downY = event.y
                currentWebView = webView
                currentElementId = elementId

                // 清除之前的残留任务，重新开始计时
                dragHostView.removeCallbacks(longPressRunnable)
                dragHostView.postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragInProgress) return

                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)

                // 如果在时间到达前，手指滑动的距离超过了容错阈值
                // 说明用户是在滑动页面或者拖动滚动条，立刻取消长按拖拽任务！
                if (dx > touchSlop || dy > touchSlop) {
                    dragHostView.removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 如果用户在规定时间内松开了手指（普通点击）
                // 立即取消长按拖拽任务，不拦截前端原生的点击事件
                dragHostView.removeCallbacks(longPressRunnable)
            }
        }
    }

    private fun startNativeDrag(
        bitmap: Bitmap,
        touchX: Float,
        touchY: Float,
        width: Int,
        height: Int
    ) {
        val scaledBitmap = bitmap.scale(width, height)
        bitmap.recycle()
        val shadowBuilder = BitmapDragShadowBuilder(scaledBitmap, touchX.toInt(), touchY.toInt())
        val sessionState = DragSessionState(
            width = width,
            height = height,
            touchOffsetX = touchX,
            touchOffsetY = touchY,
            lastVisualX = touchX,
            lastVisualY = touchY
        )
        dragHostView.post {
            setupDragListener()
            // 启动系统级拖拽，接管后续所有的触摸事件流
            val started = dragHostView.startDragAndDrop(
                null,
                shadowBuilder,
                sessionState,
                View.DRAG_FLAG_GLOBAL
            )
            if (!started) {
                isDragInProgress = false
            }
        }
    }

    private fun setupDragListener() {
        dragHostView.setOnDragListener { view, event ->
            val state = event.localState as? DragSessionState ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    state.lastVisualX = event.x
                    state.lastVisualY = event.y
                    true
                }

                DragEvent.ACTION_DROP -> {
                    val finalX = state.lastVisualX
                    val finalY = state.lastVisualY
                    val imgTopLeftX = finalX - state.touchOffsetX
                    val imgTopLeftY = finalY - state.touchOffsetY
                    val centerX = imgTopLeftX + (state.width / 2f)
                    val centerY = imgTopLeftY + (state.height / 2f)
                    onDropAction(centerX, centerY, state.width, state.height)
                    true
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    view.setOnDragListener(null)
                    isDragInProgress = false
                    true
                }

                else -> true
            }
        }
    }
}