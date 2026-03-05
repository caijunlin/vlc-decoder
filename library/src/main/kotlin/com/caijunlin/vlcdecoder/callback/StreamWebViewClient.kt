package com.caijunlin.vlcdecoder.callback

import android.graphics.Bitmap
import android.util.Log
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient

/**
 * @author : caijunlin
 * @date   : 2026/2/25
 * @description   :
 */
open class StreamWebViewClient : WebViewClient() {

    /**
     * 统一的失败回调方法，供外部重写
     */
    open fun onLoadFailed() {
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        Log.d("VLCDecoder", "Intercept url: $url")
        return false // 默认由 WebView 自身加载目标链接
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        Log.i("VLCDecoder", "Page start: $url")
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        Log.i("VLCDecoder", "Page finish: $url")
    }

    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.e("VLCDecoder", "Err $errorCode: $description")
        onLoadFailed()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Log.e("VLCDecoder", "SSL Err: ${error.primaryError}")
        onLoadFailed()
        // 默认行为是取消加载。如果外部想要忽略证书错误，可以重写此方法并调用 handler.proceed()
        super.onReceivedSslError(view, handler, error)
    }

}