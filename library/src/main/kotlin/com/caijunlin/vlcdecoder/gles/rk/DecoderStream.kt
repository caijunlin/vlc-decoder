package com.caijunlin.vlcdecoder.gles.rk

import android.graphics.SurfaceTexture
import android.os.Handler
import android.util.Log
import com.caijunlin.vlcdecoder.gles.BaseDecoderStream
import com.caijunlin.vlcdecoder.gles.EGLCore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description RK 专属解码流。采用异步立标结合主循环 Polling 模式消费图像数据。
 */
class DecoderStream(
    url: String,
    eglCore: EGLCore,
    renderHandler: Handler,
    mediaOptions: ArrayList<String>,
    onStreamDead: (String) -> Unit
) : BaseDecoderStream(url, eglCore, renderHandler, mediaOptions, onStreamDead) {

    val frameAvailable = AtomicBoolean(false)

    @Volatile var lastPts: Long = 0L
    @Volatile private var lastWatchdogPts: Long = 0L

    override val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isDecoding) {
                if (!hasFirstFrame) {
                    val waitFirstFrameTime = System.currentTimeMillis() - startPlayTimeMs
                    if (waitFirstFrameTime > 15000L) {
                        Log.e("VLCDecoder", "Watchdog Bite! 15s timeout waiting for FIRST frame: $url")
                        retryPlay()
                        return
                    }
                } else {
                    if (lastPts != 0L && lastPts == lastWatchdogPts) {
                        Log.e("VLCDecoder", "Watchdog Bite! Video PTS completely frozen at $lastPts: $url")
                        retryPlay()
                        return
                    }
                    lastWatchdogPts = lastPts
                }
            }
            renderHandler.postDelayed(this, 3000L)
        }
    }

    override fun onPlayStarted() {
        lastWatchdogPts = 0L
    }

    override fun onPlayRetried() {
        lastWatchdogPts = 0L
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        frameAvailable.set(true)
    }
}