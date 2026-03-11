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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * @author caijunlin
 * @date   2026/3/11
 * @description 拖拽手势处理（双重并发预加载：同时预取 Rect 和 Bitmap，实现零延迟拖拽）
 */
class VideoGestureHelper(
    private val client: IVideoRenderClient,
    private val webView: StreamWebView,
    private val onDropAction: (centerX: Float, centerY: Float, width: Int, height: Int) -> Unit
) {

    private var dragJob: Job? = null
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(webView.context).scaledTouchSlop

    private data class RectData(
        val physicalW: Int,
        val physicalH: Int,
        val scaleX: Float,
        val scaleY: Float
    )

    private class DragSessionState(
        val width: Int,
        val height: Int,
        val touchOffsetX: Float,
        val touchOffsetY: Float,
        var lastVisualX: Float,
        var lastVisualY: Float
    )

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y

                // 取消上一次可能未完成的拖拽任务
                dragJob?.cancel()

                dragJob = GlobalUIScope.scope.launch {
                    // 向 WebView 发起异步请求获取位置矩阵
                    val deferredRect = CompletableDeferred<RectData>()
                    val elementId = client.getElementId()
                    WidgetManager.getBoundingClientRect(webView, elementId) { w, h, sx, sy ->
                        if (w == 0 && h == 0) {
                            deferredRect.cancel()
                        } else {
                            deferredRect.complete(RectData(w, h, sx, sy))
                        }
                    }

                    // 使用 async 将阻塞式的同步截帧丢到 IO 线程池并发执行！
                    // 这样即使 CountDownLatch 阻塞了，也只是阻塞后台的 IO 线程，绝对不卡主线程 UI
                    val deferredBitmap = async(Dispatchers.IO) {
                        VLCRenderPool.captureClientFrameSync(client)
                    }

                    delay(400)

                    // 时间到！开始收网拿数据：
                    // 如果 A 和 B 早就执行完了，这里瞬间拿到结果。如果没有，就原地挂起等它们。
                    val rect = try {
                        deferredRect.await()
                    } catch (_: Exception) {
                        return@launch
                    }

                    val bitmap = try {
                        deferredBitmap.await()
                    } catch (_: Exception) {
                        null
                    }

                    // 如果截图失败（比如流刚刚断开），终止拖拽
                    if (bitmap == null) return@launch

                    // 执行终极拖拽
                    executeDrag(rect, bitmap)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    dragJob?.cancel()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragJob?.cancel()
            }
        }
    }

    /**
     * 真正执行拖拽的方法（此时数据已经 100% 准备就绪）
     */
    private fun executeDrag(rect: RectData, bitmap: Bitmap) {
        val touchX = downX / rect.scaleX
        val touchY = downY / rect.scaleY
        startNativeDrag(bitmap, touchX, touchY, rect.physicalW, rect.physicalH)
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
        webView.setOnDragListener { view, event ->
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
                    true
                }

                else -> true
            }
        }
        webView.startDragAndDrop(
            null,
            shadowBuilder,
            sessionState,
            View.DRAG_FLAG_GLOBAL
        )
    }

    fun destroy() {
        dragJob?.cancel()
    }
}