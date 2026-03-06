package com.caijunlin.vlcdecoder.widget

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.Surface
import com.caijunlin.vlcdecoder.gesture.VideoGestureHelper
import com.caijunlin.vlcdecoder.gles.VlcRenderPool
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClient
import com.tencent.smtt.sdk.WebView
import kotlin.math.ceil

/**
 * @author caijunlin
 * @date   2026/3/2
 * @description   腾讯X5内核同层渲染组件运用即时绑定的策略实现可见性与硬件解码器生命周期的强绑定
 */
class VLCVideoSurface(
    var webView: WebView,
    private val tagName: String,
    attributes: Map<String, String>,
    private val displayMetrics: DisplayMetrics
) : IEmbeddedWidgetClient {

    private var _attributes = attributes.toMutableMap()
    val id: String
        get() = _attributes["id"] ?: ""
    private val videoSrc: String
        get() = _attributes["src"] ?: ""
    private val videoType: String
        get() = _attributes["videoType".lowercase()] ?: ""
    private val videoData: String
        get() = _attributes["videoData".lowercase()] ?: ""

    private var rect: Rect? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var x5Surface: Surface? = null
    private var gestureHelper: VideoGestureHelper? = null

    private fun bind(surface: Surface?) {
        if (surface != null && surface.isValid) {
            if (videoSrc.isNotEmpty()) {
                VlcRenderPool.bindSurface(videoSrc, surface, surfaceWidth, surfaceHeight)
            } else {
                VlcRenderPool.clearSurface(surface)
            }
        }
    }

    override fun onSurfaceCreated(surface: Surface?) {
        Log.i("VLCDecoder", "onSurfaceCreated $id $videoSrc $videoType $videoData")
        if (surface == null) return
        x5Surface = surface
        WidgetManager.cacheWidget(id, this)
        if (gestureHelper == null) {
            gestureHelper = VideoGestureHelper(
                surfaceProvider = { x5Surface },
                dragHostView = webView,
                onDropAction = { centerX, centerY, width, height ->
                    // webViewRect浏览器的物理宽高、x,y设备的物理坐标
                    val webViewRect = Rect(0, 0, webView.width, webView.height)
                    if (webViewRect.contains(centerX.toInt(), centerY.toInt())) {
                        when (videoType) {
                            "source" -> {
                                WidgetManager.getWidgetAt(
                                    webView,
                                    tagName,
                                    centerX,
                                    centerY
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
                                    rect?.left ?: 0,
                                    rect?.top ?: 0,
                                    (rect?.left ?: 0) + width,
                                    (rect?.top ?: 0) + height
                                )
                                if (!jsRect.contains(centerX.toInt(), centerY.toInt())) {
                                    // 移除当前源数据
                                    WidgetManager.triggerRemoveSource(
                                        webView,
                                        this@VLCVideoSurface.id
                                    ) {
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
        bind(surface)
    }

    override fun onSurfaceDestroyed(surface: Surface?) {
        Log.i("VLCDecoder", "onSurfaceDestroyed $id")
        if (surface == null) return
        VlcRenderPool.unbindSurface(videoSrc, surface)
        x5Surface = null
        WidgetManager.removeWidget(this.id)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && videoSrc != "") {
            webView.post {
                gestureHelper?.onTouchEvent(event, webView, id)
            }
        }
        return true
    }

    private fun dip2px(dpValue: Float): Int {
        if (dpValue <= 0f) return 0
        val pxValue = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpValue,
            displayMetrics
        )
        return ceil(pxValue.toDouble()).toInt()
    }

    override fun onRectChanged(rect: Rect?) {
        if (rect == null) return
        // 浏览器回调的位置这个位置的left和right值是相对于设备的绝对定位，宽高则有相应的缩放
        this.rect = rect
        val physicalW = dip2px(rect.width().toFloat())
        val physicalH = dip2px(rect.height().toFloat())
        if (physicalW != surfaceWidth || physicalH != surfaceHeight) {
            surfaceWidth = physicalW
            surfaceHeight = physicalH
            x5Surface?.let { surface ->
                if (surface.isValid) {
                    VlcRenderPool.resizeSurface(surface, surfaceWidth, surfaceHeight)
                }
            }
        }
    }

    override fun onActive() {
        Log.i("VLCDecoder", "onActive $id")
        x5Surface?.let { bind(it) }
    }

    override fun onDeactive() {
        Log.i("VLCDecoder", "onDeactivate $id")
        x5Surface?.let { VlcRenderPool.unbindSurface(videoSrc, it) }
    }

    override fun onDestroy() {
        Log.i("VLCDecoder", "onDestroy $id")
        onSurfaceDestroyed(x5Surface)
    }

    override fun onRequestRedraw() {}

    override fun onSetAttribute(p0: String?, p1: String?): Boolean {
        Log.i("VLCDecoder", "onSetAttribute $p0 $p1 $id")
        val oldSrc = videoSrc
        _attributes[p0!!] = p1!!
        if (p0 == "src" && p1 != oldSrc) {
            x5Surface?.let { surface ->
                if (surface.isValid) {
                    if (oldSrc.isNotEmpty() && p1.isNotEmpty()) {
                        VlcRenderPool.switchUrl(oldSrc, p1, surface, surfaceWidth, surfaceHeight)
                    } else if (p1.isNotEmpty()) {
                        bind(surface)
                    } else if (oldSrc.isNotEmpty()) {
                        VlcRenderPool.unbindSurface(oldSrc, surface)
                    }
                }
            }
        }
        return true
    }

    override fun onVisibilityChanged(v: Boolean) {
        Log.i("VLCDecoder", "onVisibilityChanged $v $id")
        x5Surface?.let {
            if (v) {
                bind(it)
            } else {
                VlcRenderPool.unbindSurface(videoSrc, it)
            }
        }
    }
}