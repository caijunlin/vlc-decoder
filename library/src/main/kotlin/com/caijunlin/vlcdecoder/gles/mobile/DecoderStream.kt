package com.caijunlin.vlcdecoder.gles.mobile

import android.graphics.SurfaceTexture
import android.os.Handler
import android.util.Log
import com.caijunlin.vlcdecoder.gles.BaseDecoderStream
import com.caijunlin.vlcdecoder.gles.EGLCore

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description Mobile 专属解码流。彻底事件驱动，在 onFrameAvailable 时同步消费，杜绝缓冲死锁。
 */
class DecoderStream(
    url: String,
    eglCore: EGLCore,
    renderHandler: Handler,
    mediaOptions: ArrayList<String>,
    onStreamDead: (String) -> Unit
) : BaseDecoderStream(url, eglCore, renderHandler, mediaOptions, onStreamDead) {

    @Volatile var hasNewFboData = false
    @Volatile private var lastWatchdogTimeMs: Long = 0L

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
                    val idleTime = System.currentTimeMillis() - lastWatchdogTimeMs
                    if (idleTime > 5000L) {
                        Log.e("VLCDecoder", "Watchdog Bite! Video completely frozen for 5s: $url")
                        retryPlay()
                        return
                    }
                }
            }
            renderHandler.postDelayed(this, 3000L)
        }
    }

    override fun onPlayStarted() {
        lastWatchdogTimeMs = System.currentTimeMillis()
    }

    override fun onPlayRetried() {
        lastWatchdogTimeMs = System.currentTimeMillis()
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        lastWatchdogTimeMs = System.currentTimeMillis()
        try {
            eglCore.makeCurrentMain()
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)

            if (!hasFirstFrame) {
                checkAndUpdateResolution()
                hasFirstFrame = true
                displayWindows.forEach { window ->
                    window.clientRef.get()?.onFirstFrameRendered(url)
                }
            }

            eglCore.drawOESToFBO(fboId, oesTextureId, transformMatrix, videoWidth, videoHeight)
            hasNewFboData = true
        } catch (e: Exception) {
            Log.e("VLCDecoder", "OES Fast Consume failed: ${e.message}")
        }
    }
}