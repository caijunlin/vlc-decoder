package com.caijunlin.vlcdecoder.widget

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.Surface
import com.caijunlin.vlcdecoder.core.StreamWebView
import com.caijunlin.vlcdecoder.gesture.VideoGestureHelper
import com.caijunlin.vlcdecoder.gles.IVideoRenderClient
import com.caijunlin.vlcdecoder.gles.VLCRenderPool
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClient
import kotlin.math.ceil

class VLCVideoSurface(
    var webView: StreamWebView,
    private val tagName: String,
    attributes: Map<String, String>,
    private val displayMetrics: DisplayMetrics
) : IEmbeddedWidgetClient, IVideoRenderClient {

    private var _attributes = attributes.toMutableMap()
    val id: String
        get() = _attributes["id"] ?: ""
    private val videoSrc: String
        get() = _attributes["src"] ?: ""
    private val videoType: String
        get() = _attributes["videoType".lowercase()] ?: ""
    private val videoData: String
        get() = _attributes["videoData".lowercase()] ?: ""
    private val draggable: Int
        get() = _attributes["_draggable".lowercase()]?.toIntOrNull() ?: 0

    private var rect: Rect? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var x5Surface: Surface? = null

    // 优化多次绑定和解绑的性能开销
    private var pendingBoundUrl: String? = null

    // 真实的业务状态确认
    private var isActuallyPlaying = false
    override fun getElementId(): String = id
    override fun getTargetSurface(): Surface? = x5Surface
    override fun getTargetWidth(): Int = surfaceWidth
    override fun getTargetHeight(): Int = surfaceHeight
    private var gestureHelper: VideoGestureHelper = VideoGestureHelper(
        client = this,
        webView = webView,
        onDropAction = { centerX, centerY, width, height ->
            val webViewRect = Rect(0, 0, webView.width, webView.height)
            Log.i("VLCDecoder", "onDropAction $centerX $centerY $width $height")
            if (webViewRect.contains(centerX.toInt(), centerY.toInt())) {
                when (videoType) {
                    "source" -> {
                        WidgetManager.getWidgetAt(
                            webView, tagName, centerX, centerY
                        ) { hitWidget ->
                            if (hitWidget != null && hitWidget.videoType == "player") {
                                WidgetManager.triggerSetVideoSource(
                                    webView = webView,
                                    elementId = hitWidget.id,
                                    videoData = this@VLCVideoSurface.videoData
                                )
                            }
                        }
                    }

                    "player" -> {
                        val jsRect = Rect(
                            rect?.left ?: 0, rect?.top ?: 0,
                            (rect?.left ?: 0) + width, (rect?.top ?: 0) + height
                        )
                        if (!jsRect.contains(centerX.toInt(), centerY.toInt())) {
                            WidgetManager.triggerRemoveSource(
                                webView,
                                this@VLCVideoSurface.id
                            ) {}
                        }
                    }
                }
            }
        }
    )

    private fun bind() {
        if (x5Surface != null && x5Surface!!.isValid) {
            if (videoSrc.isNotEmpty()) {
                if (pendingBoundUrl != videoSrc) {
                    // 物理防抖
                    pendingBoundUrl = videoSrc
                    isActuallyPlaying = false
                    Log.i("VLCDecoder", "bindClient $id $videoSrc")
                    VLCRenderPool.bindClient(videoSrc, this)
                }
            } else {
                if (pendingBoundUrl != null) {
                    Log.i("VLCDecoder", "clearClient $id $videoSrc")
                    VLCRenderPool.clearClient(this)
                    pendingBoundUrl = null
                }
            }
        }
    }

    private fun unbind() {
        if (pendingBoundUrl != null) {
            val unbindUrl = pendingBoundUrl!!
            pendingBoundUrl = null
            isActuallyPlaying = false
            Log.i("VLCDecoder", "unbindClient $id $videoSrc")
            VLCRenderPool.unbindClient(unbindUrl, this)
        }
    }

    override fun onSurfaceCreated(surface: Surface?) {
//        Log.i("VLCDecoder", "onSurfaceCreated $id $videoSrc $videoType $videoData")
        if (surface == null) return
        x5Surface = surface
        WidgetManager.cacheWidget(id, this)
        bind()
    }

    override fun onSurfaceDestroyed(surface: Surface?) {
//        Log.i("VLCDecoder", "onSurfaceDestroyed $id")
        onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && videoSrc != "" && draggable == 1) {
            val e = MotionEvent.obtain(event)
            webView.post {
                gestureHelper.onTouchEvent(e)
                // 异步用完后，必须手动回收这个克隆的事件防泄漏
                e.recycle()
            }
        }
        return true
    }

    private fun dip2px(dpValue: Float): Int {
        if (dpValue <= 0f) return 0
        val pxValue = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dpValue, displayMetrics
        )
        return ceil(pxValue.toDouble()).toInt()
    }

    override fun onRectChanged(rect: Rect?) {
        if (rect == null) return
        this.rect = rect
        val physicalW = dip2px(rect.width().toFloat())
        val physicalH = dip2px(rect.height().toFloat())
        if (physicalW != surfaceWidth || physicalH != surfaceHeight) {
            surfaceWidth = physicalW
            surfaceHeight = physicalH
            if (x5Surface?.isValid == true && pendingBoundUrl != null) {
                VLCRenderPool.resizeClient(this)
            }
        }
    }

    override fun onActive() {
//        Log.i("VLCDecoder", "onActive $id")
        bind()
    }

    override fun onDeactive() {
//        Log.i("VLCDecoder", "onDeactivate $id")
        unbind()
    }

    override fun onDestroy() {
//        Log.i("VLCDecoder", "onDestroy $id")
        gestureHelper.destroy()
        unbind()
        x5Surface = null
        WidgetManager.removeWidget(this.id)
    }

    override fun onRequestRedraw() {}

    override fun onSetAttribute(p0: String?, p1: String?): Boolean {
//        Log.i("VLCDecoder", "onSetAttribute $p0 $p1 $id")
        _attributes[p0!!] = p1!!
        if (p0 == "src") {
            if (pendingBoundUrl != null && p1.isNotEmpty() && pendingBoundUrl != p1) {
                val oldUrl = pendingBoundUrl!!
                pendingBoundUrl = p1
                isActuallyPlaying = false
                VLCRenderPool.switchClientUrl(oldUrl, p1, this)
            } else if (p1.isNotEmpty() && pendingBoundUrl != p1) {
                bind()
            } else if (p1.isEmpty() && pendingBoundUrl != null) {
                unbind()
            }
        }
        return true
    }

    override fun onVisibilityChanged(v: Boolean) {
//        Log.i("VLCDecoder", "onVisibilityChanged $v $id")
        if (v) bind() else unbind()
    }

    override fun onFirstFrameRendered(url: String) {
        // 切回 UI 线程处理前端逻辑
        Handler(Looper.getMainLooper()).post {
            if (url == pendingBoundUrl) {
                isActuallyPlaying = true
            }
        }
    }

    override fun onPlaybackFailed(url: String) {
        Handler(Looper.getMainLooper()).post {
            if (url == pendingBoundUrl) {
                isActuallyPlaying = false
            }
        }
    }

}