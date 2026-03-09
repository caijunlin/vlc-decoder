package com.caijunlin.vlcdecoder.gesture

import android.graphics.Bitmap
import android.view.DragEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.core.graphics.scale
import com.caijunlin.vlcdecoder.core.StreamWebView
import com.caijunlin.vlcdecoder.gles.VLCRenderPool
import com.caijunlin.vlcdecoder.widget.WidgetManager

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   系统级拖拽手势处理
 */
class VideoGestureHelper(
    private val surfaceProvider: () -> Surface?,
    private val dragHostView: View,
    private val onDropAction: (centerX: Float, centerY: Float, width: Int, height: Int) -> Unit
) {

    var isDragInProgress: Boolean = false

    private class DragSessionState(
        val width: Int,
        val height: Int,
        val touchOffsetX: Float,
        val touchOffsetY: Float,
        var lastVisualX: Float,
        var lastVisualY: Float
    )

    fun onTouchEvent(
        event: MotionEvent,
        webView: StreamWebView,
        elementId: String
    ) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (isDragInProgress) return
            isDragInProgress = true
            val downX = event.x
            val downY = event.y
            WidgetManager.getBoundingClientRect(
                webView,
                elementId
            ) { physicalW, physicalH, scaleX, scaleY ->
                if (physicalW == 0 && physicalH == 0) {
                    isDragInProgress = false
                    return@getBoundingClientRect
                }

                val touchX = downX / scaleX
                val touchY = downY / scaleY

                val surface = surfaceProvider()
                if (surface != null && surface.isValid) {
                    VLCRenderPool.captureFrame(surface) { bitmap ->
                        if (bitmap != null) {
                            startNativeDrag(
                                bitmap,
                                touchX,
                                touchY,
                                physicalW,
                                physicalH
                            )
                        } else {
                            isDragInProgress = false
                        }
                    }
                } else {
                    isDragInProgress = false
                }
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