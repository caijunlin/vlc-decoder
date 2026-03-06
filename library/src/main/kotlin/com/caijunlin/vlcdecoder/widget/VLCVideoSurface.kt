package com.caijunlin.vlcdecoder.widget

import android.app.Activity
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
    var context: Context,
    var webView: WebView,
    private val tagName: String,
    attributes: Map<String, String>,
    private val displayMetrics: DisplayMetrics
) : IEmbeddedWidgetClient {

    private var _attributes = attributes.toMutableMap()
    private val id: String
        get() = _attributes["id"] ?: ""
    private val videoSrc: String
        get() = _attributes["src"] ?: ""
    private val videoType: String
        get() = _attributes["videoType".lowercase()] ?: ""
    private val videoData: String
        get() = _attributes["videoData".lowercase()] ?: ""

    private var x: Int = 0
    private var y: Int = 0
    private var width: Int = 0
    private var height: Int = 0
    private var x5Surface: Surface? = null
    private var gestureHelper: VideoGestureHelper? = null

    private fun bind(surface: Surface?) {
        if (surface != null && surface.isValid) {
            if (videoSrc.isNotEmpty()) {
                VlcRenderPool.bindSurface(videoSrc, surface, width, height)
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
                dragHostView = (context as Activity).window.decorView,
                onDropAction = { x, y ->
                    val webViewRect = Rect(
                        0,
                        0,
                        webView.width,
                        webView.height
                    )
                    Log.i("VLCDecoder", "onDropAction $x $y $webViewRect $id $videoType")
                    if (webViewRect.contains(x.toInt(), y.toInt())) {
                        when (videoType) {
                            "source" -> {
                                // 当前是一个源将url传递出去
                                WidgetManager.getWidgetAt(
                                    webView,
                                    tagName,
                                    x,
                                    y
                                ) { hitWidget ->
                                    if (hitWidget != null && hitWidget.videoType == "player") {
                                        Log.i("VLCDecoder", "onDropAction hit ${hitWidget.id}")
                                        WidgetManager.triggerSetVideoSource(
                                            webView = webView,
                                            elementId = hitWidget.id,
                                            videoData = this@VLCVideoSurface.videoData
                                        )
                                    }
                                }
                            }

                            "player" -> {
                                // 查看是否可以移除当前模块
                                val rect = Rect(
                                    this.x,
                                    this.y,
                                    this.x + this.width,
                                    this.y + this.height
                                )
                                if (!rect.contains(x.toInt(), y.toInt())) {
                                    // 移除当前源数据
                                    WidgetManager.triggerRemoveSource(
                                        webView,
                                        this@VLCVideoSurface.id
                                    ) {
                                        // 完全清空画布
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
        Log.i("VLCDecoder", "onTouchEvent $id")
        if (event != null) {
            // 有url的模块才能拖拽
            if (videoSrc != "") {
                return gestureHelper.let {
                    it?.onTouchEvent(
                        event,
                        width,
                        height
                    ) ?: true
                }
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
        this.x = dip2px(rect.left.toFloat())
        this.y = dip2px(rect.top.toFloat())
        val physicalW = dip2px(rect.width().toFloat())
        val physicalH = dip2px(rect.height().toFloat())
        if (physicalW != width || physicalH != height) {
            width = physicalW
            height = physicalH
            x5Surface?.let { surface ->
                if (surface.isValid) {
                    VlcRenderPool.resizeSurface(surface, width, height)
                }
            }
        }
    }

    override fun onActive() {
        Log.i("VLCDecoder", "onActive $id")
        // 重新进入活跃区直接要求绑定即可如无解码器底层会自动拉起
        x5Surface?.let { bind(it) }
    }

    override fun onDeactive() {
        Log.i("VLCDecoder", "onDeactivate $id")
        // 退出活跃区立刻解绑释放资源交由底层裁决销毁
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
                        // 使用全新的安全交接 API 切换视频流
                        VlcRenderPool.switchUrl(oldSrc, p1, surface, width, height)
                    } else if (p1.isNotEmpty()) {
                        // 从无到有
                        bind(surface)
                    } else if (oldSrc.isNotEmpty()) {
                        // 从有到无
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
                // 重新显示时执行绑定
                bind(it)
            } else {
                // 隐藏时执行彻解绑销毁
                VlcRenderPool.unbindSurface(videoSrc, it)
            }
        }
    }
}