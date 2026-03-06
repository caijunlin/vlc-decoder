package com.caijunlin.vlcdecoder.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import com.caijunlin.vlcdecoder.debug.JavaScriptBridge
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
 * 特性：无滚动条、支持视频自动播放、开启硬件加速
 */
class StreamWebView @JvmOverloads constructor(
    context: Context,
    var tag: String,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr), IEmbeddedWidgetClientFactory {

    init {
        // 设置透明背景，适合做悬浮面板
        setBackgroundColor(0)

        // 开启硬件加速（监控播流任务必须开启，否则视频流会极其卡顿）
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // 注入 JS 通信桥接 (需确保外部已有 VLCJsBridge 实现)
        // 在外部浏览器使用window.VLCBridge.printVLCDiagnostics();
        addJavascriptInterface(JavaScriptBridge(), "VLCBridge")

        // 初始化浏览器内核设置
        initWebSettings()

        // 初始化 Cookie 配置
        initCookieManager()

        // 注册接管同层渲染
        initEmbeddedWidget()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebSettings() {
        settings.apply {
            // 允许执行 JavaScript 脚本，H5 页面核心交互依赖
            javaScriptEnabled = true
            // 允许通过 File 协议访问本地文件系统
            allowFileAccess = true
            // 允许 JS 代码自动打开新窗口（如 window.open）
            javaScriptCanOpenWindowsAutomatically = true
            // 支持 <meta> 标签中的 viewport 属性，使页面排版适配屏幕宽
            useWideViewPort = true
            // 允许 File URL 环境下的 JS 代码访问同目录下的其他文件
            setAllowFileAccessFromFileURLs(true)
            // 允许 File URL 环境下的 JS 代码进行跨域访问资源
            setAllowUniversalAccessFromFileURLs(true)
            // 允许 WebView 访问 ContentProvider 提供的 Android 组件内容
            allowContentAccess = true
            // 将内容缩放至匹配屏幕宽度，避免页面内容溢出
            loadWithOverviewMode = true
            // 开启 DOM 存储 API（Web Storage），提升页面加载速度和缓存能力
            domStorageEnabled = true
            // 开启多窗口支持
            setSupportMultipleWindows(true)
            // 设定页面默认的文本编码格式为 UTF-8
            defaultTextEncodingName = "utf-8"
            // 开启 Web SQL 数据库存储功能
            databaseEnabled = true
            // 允许音视频在不需要用户手动点击的情况下自动播放（播流面板的最核心属性）
            mediaPlaybackRequiresUserGesture = false
        }
    }

    private fun initCookieManager() {
        val cookieManager = CookieManager.getInstance()
        // 允许 WebView 接收与保存 Cookie
        cookieManager.setAcceptCookie(true)
        // 允许当前 WebView 接收跨域或第三方请求携带的 Cookie (视频流鉴权通常需要)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        // 立即将内存中的 Cookie 同步持久化
        cookieManager.flush()
    }

    private fun initEmbeddedWidget() {
        // 开启同层渲染并且接管指定标签
        val tags = arrayOf(tag)

        // 安全调用：防止未成功加载 X5 内核降级为系统内核时 extension 为 null 导致崩溃
        val success = x5WebViewExtension?.registerEmbeddedWidget(tags, this) ?: false
        // 打印内核诊断信息
        Log.i(
            "VLCDecoder",
            "TBS:${QbSdk.getTbsVersion(context)} X5:${isX5Core} Init:${QbSdk.isTbsCoreInited()} FrcSys:${QbSdk.getIsSysWebViewForcedByOuter()} Reg:$success"
        )
    }

    /**
     * 实现同层渲染
     */
    override fun createWidgetClient(
        tagName: String,
        attributes: Map<String, String>,
        widget: IEmbeddedWidget
    ): IEmbeddedWidgetClient? {
        return when (tagName.lowercase()) {
            tag -> VLCVideoSurface(
                this, // 直接将当前 WebView 实例传给 Widget
                tag,
                attributes,
                context.resources.displayMetrics
            )

            else -> null
        }
    }

    // 控制“不可滚动”的面板特性
    var isScroll: Boolean = false
        set(value) {
            field = value
            isVerticalScrollBarEnabled = value
            isHorizontalScrollBarEnabled = value
            // 控制底层真实 View 的越界滚动回弹效果
            view.overScrollMode = if (value) OVER_SCROLL_ALWAYS else OVER_SCROLL_NEVER
        }

    // 拦截滚动调用
    override fun scrollTo(x: Int, y: Int) {
        if (isScroll) {
            super.scrollTo(x, y)
        } else {
            super.scrollTo(0, 0) // 锁死坐标在顶部
        }
    }

    // 拦截触摸滑动事件，防止面板在操作时意外滑动
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isScroll && event.action == MotionEvent.ACTION_MOVE) {
            return false // 拦截 Move 事件
        }
        return super.onTouchEvent(event)
    }

}