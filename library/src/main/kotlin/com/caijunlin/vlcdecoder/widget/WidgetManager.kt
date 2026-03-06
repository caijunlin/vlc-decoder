package com.caijunlin.vlcdecoder.widget

import android.util.Log
import com.tencent.smtt.sdk.WebView
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * VLCVideoWidget 统一管理工具类
 * 负责 Widget 的缓存、移除以及基于坐标的查询
 */
object WidgetManager {

    private val widgetCache = CopyOnWriteArrayList<VLCVideoSurface>()

    /**
     * 缓存 Widget
     * 在 X5 回调创建 Widget (onWidgetCreate) 时调用
     * @param id HTML 中标签的唯一标识
     * @param widget 创建出来的 VLCVideoWidget 实例
     */
    fun cacheWidget(id: String?, widget: VLCVideoSurface) {
        if (id.isNullOrEmpty()) {
            Log.e("VLCDecoder", "Cannot cache widget: id is empty!")
            return
        }
        widgetCache.removeAll { it.id == id }
        widgetCache.add(widget)
        Log.d("VLCDecoder", "Cached widget with id: $id. Total widgets: ${widgetCache.size}")
    }

    /**
     * 移除销毁的 Widget
     * 在 X5 回调销毁 Widget (onWidgetDestroy) 时调用，防止内存泄漏
     * @param id 需要移除的标签的唯一标识
     */
    fun removeWidget(id: String?) {
        if (id.isNullOrEmpty()) return

        val removed = widgetCache.removeAll { it.id == id }
        if (removed) {
            Log.d(
                "VLCDecoder",
                "Removed widget with id: $id. Remaining widgets: ${widgetCache.size}"
            )
        }
    }

    /**
     * 清空所有缓存 (在 WebView 销毁或页面刷新时按需调用)
     */
    fun clearAll() {
        widgetCache.clear()
        Log.d("VLCDecoder", "Cleared all widget caches.")
    }

    /**
     * 通过 x, y 获取最顶层的 VLCVideoWidget
     * @param webView 承载的 X5 WebView
     * @param x Android 触摸事件的物理 X 坐标
     * @param y Android 触摸事件的物理 Y 坐标
     * @param callback 异步回调，返回命中的 VLCVideoWidget，若未命中或被遮挡则返回 null
     */
    fun getWidgetAt(
        webView: WebView,
        tagName: String,
        x: Float,
        y: Float,
        callback: (VLCVideoSurface?) -> Unit
    ) {
        val jsCode = """
            (function(x, y) {
                var element = document.elementFromPoint(x, y);
                while(element && element !== document.body) {
                    if (element.tagName.toLowerCase() === '$tagName') {
                        return element.id; 
                    }
                    element = element.parentElement;
                }
                return null;
            })($x, $y);
        """.trimIndent()
        Log.d("VLCDecoder", "JS Code: $jsCode")
        webView.evaluateJavascript(jsCode) { result ->
            Log.d("VLCDecoder", "JS Result: $result")
            if (!result.isNullOrEmpty() && result != "null") {
                val videId = result.replace("\"", "")
                val targetWidget = widgetCache.find { it.id == videId }
                callback(targetWidget)
            } else {
                callback(null)
            }
        }
    }

    /**
     * 基础函数：向指定 ID 的 DOM 元素派发 CustomEvent 自定义事件
     */
    private fun dispatchCustomEvent(
        webView: WebView,
        elementId: String,
        eventName: String,
        detailData: String = "null",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val jsCode = """
            (function() {
                var element = document.getElementById('$elementId');
                if (element) {
                    var customEvent = new CustomEvent('$eventName', {
                        detail: $detailData,
                        bubbles: true,
                        cancelable: true
                    });
                    element.dispatchEvent(customEvent);
                }
            })();
        """.trimIndent()
        Log.d("VLCDecoder", "JS Code: $jsCode")
        webView.evaluateJavascript(jsCode) { result ->
            Log.d("VLCDecoder", "JS Result: $result")
            val isSuccess = result?.replace("\"", "")?.replace("'", "") == "true"
            onComplete?.invoke(isSuccess)
        }
    }

    /**
     * 触发无参数的 remove-source 事件
     */
    fun triggerRemoveSource(webView: WebView, elementId: String, onSuccess: (() -> Unit)? = null) {
        dispatchCustomEvent(webView, elementId, "remove-source") { success ->
            if (success) {
                Log.i("VLCDecoder", "Triggered remove-source successfully on $elementId")
                onSuccess?.invoke()
            } else {
                Log.w("VLCDecoder", "Failed to trigger remove-source: node $elementId not found")
            }
        }
    }

    /**
     * 触发带参数的 set-video-source 事件
     */
    fun triggerSetVideoSource(webView: WebView, elementId: String, videoData: String) {
        val safeVideoData = JSONObject.quote(videoData)
        val detailObj = "{ videoData: $safeVideoData }"
        dispatchCustomEvent(webView, elementId, "set-video-source", detailObj)
        Log.i("VLCDecoder", "Triggered set-video-source on $elementId with data")
    }

    /**
     * 通过 id 获取标签的真实宽高，并计算拖拽滑动的缩放比
     */
    fun getBoundingClientRect(
        webView: WebView,
        elementId: String,
        callback: (Int, Int, Float, Float) -> Unit
    ) {
        val jsCode = """
        (function() {
            var element = document.getElementById('$elementId');
            if (element) {
                var rect = element.getBoundingClientRect();
                return JSON.stringify({
                    width: rect.width,
                    height: rect.height,
                    offsetW: element.offsetWidth || 1,
                    offsetH: element.offsetHeight || 1
                });
            }
            return null;
        })();
        """.trimIndent()
        webView.evaluateJavascript(jsCode) { result ->
            try {
                val rectStr = result.removeSurrounding("\"").replace("\\\"", "\"")
                val rect = JSONObject(rectStr)

                val physicalWidth = rect.optDouble("width", 0.0).toInt()
                val physicalHeight = rect.optDouble("height", 0.0).toInt()
                val offsetW = rect.optDouble("offsetW", 1.0).toFloat()
                val offsetH = rect.optDouble("offsetH", 1.0).toFloat()

                val webScaleX = physicalWidth / offsetW
                val webScaleY = physicalHeight / offsetH

                val density = webView.context.resources.displayMetrics.density
                val touchScaleX = if (webScaleX > 0) density / webScaleX else 1f
                val touchScaleY = if (webScaleY > 0) density / webScaleY else 1f

                callback(physicalWidth, physicalHeight, touchScaleX, touchScaleY)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(0, 0, 1f, 1f)
            }
        }
    }
}