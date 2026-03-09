package com.caijunlin.vlcdecoder.callback

import android.graphics.Bitmap
import android.util.Log
import com.caijunlin.vlcdecoder.core.StreamWebView
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient

/**

@author : caijunlin

@date   : 2026/2/25

@description   : 定制的 WebViewClient，对外暴露强类型的 StreamWebView 回调
 */
open class StreamWebViewClient : WebViewClient() {

    /**

    统一的失败回调方法，供外部重写
     */
    open fun onLoadFailed(view: StreamWebView, errorMsg: String) {
    }

    /**

    页面开始加载，供外部重写
     */
    open fun onPageStart(view: StreamWebView, url: String, favicon: Bitmap?) {
    }

    /**

    页面加载完成，供外部重写
     */
    open fun onPageFinish(view: StreamWebView, url: String) {
    }

    final override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        Log.d("VLCDecoder", "Intercept url: $url")
        return false // 默认由 WebView 自身加载目标链接
    }

    final override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.i("VLCDecoder", "Page start: $url")
        (view as? StreamWebView)?.let { onPageStart(it, url, favicon) }
    }

    final override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        Log.i("VLCDecoder", "Page finish: $url")
        (view as? StreamWebView)?.let { onPageFinish(it, url) }
    }

    final override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.e("VLCDecoder", "Err $errorCode: $description")
        (view as? StreamWebView)?.let { onLoadFailed(it, "Err $errorCode: $description") }
    }

    final override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        Log.e("VLCDecoder", "SSL Err: ${error.primaryError}")
        (view as? StreamWebView)?.let { onLoadFailed(it, "SSL Err: ${error.primaryError}") }
        // 默认行为是取消加载。如果外部想要忽略证书错误，可以重写此方法并调用 handler.proceed()
        super.onReceivedSslError(view, handler, error)
    }
}