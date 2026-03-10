package com.caijunlin.vlcdecoder.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import com.caijunlin.vlcdecoder.gles.VLCRenderPool
import com.caijunlin.vlcdecoder.widget.VLCVideoSurface
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidget
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClient
import com.tencent.smtt.export.external.embeddedwidget.interfaces.IEmbeddedWidgetClientFactory
import com.tencent.smtt.sdk.CookieManager
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.WebView

/**
 * @author : caijunlin
 * @date   : 2026/2/25
 * @description : 专为监控与播流面板定制的 X5 WebView
 */
class StreamWebView : WebView, IEmbeddedWidgetClientFactory {

    // 默认 tag，仅对外暴露 get，防止外部中途乱改导致同层渲染引擎状态不一致
    var widgetTag: String = "avideo"
        private set

    constructor(context: Context, tag: String) : super(context) {
        this.widgetTag = tag
        setupView()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        setupView()
    }

    // 控制“不可滚动”的面板特性
    var isScroll: Boolean = false
        set(value) {
            field = value
            isVerticalScrollBarEnabled = value
            isHorizontalScrollBarEnabled = value
            view.overScrollMode = if (value) OVER_SCROLL_ALWAYS else OVER_SCROLL_NEVER
        }

    /**
     * 统一的初始化入口，确保在 widgetTag 被赋值之后才执行
     */
    private fun setupView() {
        setBackgroundColor(0)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // 使用匿名内部类注入 JS 通信桥接
        addJavascriptInterface(object {
            @JavascriptInterface
            fun printVLCDiagnostics() {
                VLCRenderPool.printDiagnostics()
            }
        }, "VLCBridge")

        initWebSettings()
        initCookieManager()

        // 此时注册同层渲染，拿到的必定是最准确的 widgetTag
        initEmbeddedWidget()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebSettings() {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            setAllowFileAccessFromFileURLs(true)
            setAllowUniversalAccessFromFileURLs(true)
            allowContentAccess = true
            loadWithOverviewMode = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            defaultTextEncodingName = "utf-8"
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
    }

    private fun initCookieManager() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.flush()
    }

    private fun initEmbeddedWidget() {
        val tags = arrayOf(widgetTag)
        val success = x5WebViewExtension?.registerEmbeddedWidget(tags, this) ?: false
        Log.i(
            "VLCDecoder",
            "TBS:${QbSdk.getTbsVersion(context)} X5:${isX5Core} Init:${QbSdk.isTbsCoreInited()} FrcSys:${QbSdk.getIsSysWebViewForcedByOuter()} Reg:$success Tag:$widgetTag"
        )
    }

    override fun createWidgetClient(
        tagName: String,
        attributes: Map<String, String>,
        widget: IEmbeddedWidget
    ): IEmbeddedWidgetClient? {
        return when (tagName.lowercase()) {
            widgetTag.lowercase() -> VLCVideoSurface(
                this,
                widgetTag,
                attributes,
                context.resources.displayMetrics
            )

            else -> null
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        if (!isScroll && event.action == MotionEvent.ACTION_MOVE) {
            return false
        }
        return super.onTouchEvent(event)
    }

    override fun scrollTo(x: Int, y: Int) {
        if (isScroll) {
            super.scrollTo(x, y)
        } else {
            super.scrollTo(0, 0)
        }
    }

    override fun destroy() {
        this.clearAnimation()
        this.webViewClient = null
        this.webChromeClient = null
        this.stopLoading()
        this.clearHistory()
        this.clearCache(true)
        this.clearFormData()
        this.clearMatches()
        this.removeAllViews()
        this.clearSslPreferences()
        this.clearDisappearingChildren()
        super.destroy()
    }
}