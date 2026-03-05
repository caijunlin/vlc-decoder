package com.caijunlin.vlcdecoder.widget

import android.util.Log
import com.tencent.smtt.sdk.WebView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * VLCVideoWidget 统一管理工具类
 * 负责 Widget 的缓存、移除以及基于坐标的查询
 */
object WidgetManager {

    private val widgetCache = ConcurrentHashMap<String, VLCVideoSurface>()

    /**
     * 缓存 Widget
     * 在 X5 回调创建 Widget (onWidgetCreate) 时调用
     * @param id HTML 中标签的唯一标识
     * @param widget 创建出来的 VLCVideoWidget 实例
     */
    fun cacheWidget(id: String?, widget: VLCVideoSurface) {
        id.let {
            if (it == null || it.isEmpty()) {
                Log.e("VLCDecoder", "Cannot cache widget: id is empty!")
                return
            }
            widgetCache[it] = widget
            Log.d("VLCDecoder", "Cached widget with id: $id. Total widgets: ${widgetCache.size}")
        }
    }

    /**
     * 移除销毁的 Widget
     * 在 X5 回调销毁 Widget (onWidgetDestroy) 时调用，防止内存泄漏
     * @param id 需要移除的标签的唯一标识
     */
    fun removeWidget(id: String?) {
        id?.let {
            val removed = widgetCache.remove(it)
            if (removed != null) {
                Log.d(
                    "VLCDecoder",
                    "Removed widget with id: $it. Remaining widgets: ${widgetCache.size}"
                )
            }
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
     * @param touchX Android 触摸事件的物理 X 坐标
     * @param touchY Android 触摸事件的物理 Y 坐标
     * @param callback 异步回调，返回命中的 VLCVideoWidget，若未命中或被遮挡则返回 null
     */
    fun getWidgetAt(
        webView: WebView,
        tagName: String,
        touchX: Float,
        touchY: Float,
        callback: (VLCVideoSurface?) -> Unit
    ) {
        // 将 Android 物理像素转换为 Web 的 CSS 像素
        val density = webView.context.resources.displayMetrics.density
        val cssX = touchX / density
        val cssY = touchY / density
        // 构建 JS 脚本：寻找指定坐标最顶层的标签并返回其 id
        val jsCode = """
            (function(x, y) {
                var el = document.elementFromPoint(x, y);
                while(el && el !== document.body) {
                    if (el.tagName.toLowerCase() === '$tagName') {
                        return el.id; 
                    }
                    el = el.parentElement;
                }
                return null;
            })($cssX, $cssY);
        """.trimIndent()
        Log.d("VLCDecoder", "JS Code: $jsCode")
        // 在 WebView 中执行 JS 探针
        webView.evaluateJavascript(jsCode) { result ->
            if (!result.isNullOrEmpty() && result != "null") {
                // 去除 evaluateJavascript 返回值可能自带的双引号
                val videId = result.replace("\"", "")
                // 从缓存中匹配并回调
                val targetWidget = widgetCache[videId]
                callback(targetWidget)
            } else {
                callback(null)
            }
        }
    }

    /**
     * 基础函数：向指定 ID 的 DOM 元素派发 CustomEvent 自定义事件
     * @param webView 承载的 X5 WebView
     * @param elementId HTML 标签的 id
     * @param eventName 自定义事件的名称 (如 "remove-source", "set-video-source")
     * @param detailData 传递给 CustomEvent.detail 的数据对象 (JS 字符串形式，如 "{}" 或 "{ videoData: '...' }")
     */
    private fun dispatchCustomEvent(
        webView: WebView,
        elementId: String,
        eventName: String,
        detailData: String = "null",
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        // 构建注入的 JS 代码，创建并触发 CustomEvent
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
        // 在 UI 线程执行 JS
        webView.post {
            webView.evaluateJavascript(jsCode) { result ->
                val isSuccess = result?.replace("\"", "")?.replace("'", "") == "true"
                onComplete?.invoke(isSuccess)
            }
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
     * 根据图3的前端逻辑，前端通过 const data = e.detail; const videoData = JSON.parse(data.videoData) 解析
     * 所以这里传递的 detail 必须包含 videoData 字段，并且它的值是一个 JSON 字符串
     */
    fun triggerSetVideoSource(webView: WebView, elementId: String, videoData: String) {
        val safeVideoData = JSONObject.quote(videoData)
        val detailObj = "{ videoData: $safeVideoData }"
        dispatchCustomEvent(webView, elementId, "set-video-source", detailObj)
        Log.i("VLCDecoder", "Triggered set-video-source on $elementId with data")
    }

}