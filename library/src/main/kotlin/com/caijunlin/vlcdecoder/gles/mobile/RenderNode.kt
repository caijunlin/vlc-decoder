package com.caijunlin.vlcdecoder.gles.mobile

import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.caijunlin.vlcdecoder.gles.BaseRenderNode
import com.caijunlin.vlcdecoder.gles.DisplayWindow
import com.caijunlin.vlcdecoder.gles.EGLCore
import com.caijunlin.vlcdecoder.gles.IVideoRenderClient

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description 手机端专属渲染管线：挂载系统 Choreographer 进行锁 30fps 的等分分发，让位系统 UI。
 */
class RenderNode(
    nodeName: String,
    onStreamDeadCleanup: (String, List<Surface>) -> Unit
) : BaseRenderNode<DecoderStream>(nodeName, onStreamDeadCleanup) {

    private var choreographer: Choreographer? = null
    private var isTicking = false
    private var frameCounter = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            doThrottledRender()
            if (isTicking && streams.isNotEmpty()) {
                choreographer?.postFrameCallback(this)
            } else {
                isTicking = false
            }
        }
    }

    init {
        handler.post {
            eglCore = EGLCore().apply { initEGL() }
            choreographer = Choreographer.getInstance()
        }
    }

    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            handler.post { choreographer?.postFrameCallback(frameCallback) }
        }
    }

    override fun handleBind(
        url: String,
        x5Surface: Surface,
        client: IVideoRenderClient,
        opts: ArrayList<String>,
        limit: Int
    ) {
        if (displayMap.containsKey(x5Surface)) return
        pendingReleaseTasks.remove(url)?.let { handler.removeCallbacks(it) }

        var stream = streams[url]
        if (stream == null) {
            if (streams.size >= limit) return
            stream = DecoderStream(url, eglCore, handler, opts) { deadUrl -> handleStreamDead(deadUrl) }
            stream.start()
            streams[url] = stream
        }

        val window = DisplayWindow(x5Surface, client)
        window.initEGLSurface(eglCore)
        window.physicalW = client.getTargetWidth()
        window.physicalH = client.getTargetHeight()
        window.isDirty = true

        stream.displayWindows.add(window)
        displayMap[x5Surface] = window

        startTicking()
    }

    private fun doThrottledRender() {
        frameCounter++
        if (frameCounter % 2 != 0) return

        var hasActiveDraws = false
        for (stream in streams.values) {
            val needsRender = stream.hasNewFboData
            stream.hasNewFboData = false

            for (window in stream.displayWindows) {
                if (!window.x5Surface.isValid) continue

                if (needsRender || window.isDirty) {
                    try {
                        if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                            eglCore.setSwapInterval(0)
                            Matrix.setIdentityM(window.mvpMatrix, 0)
                            eglCore.drawTex2DScreen(stream.tex2DId, window.mvpMatrix, window.physicalW, window.physicalH)
                            eglCore.swapBuffers(window.eglSurface)

                            window.isDirty = false
                            hasActiveDraws = true
                        }
                    } catch (e: Exception) {
                        Log.e("VLCDecoder", "Throttled Swap failed: ${e.message}")
                    }
                }
            }
        }

        if (hasActiveDraws) {
            eglCore.makeCurrentMain()
        }
    }

    override fun handleResize(x5Surface: Surface, width: Int, height: Int) {
        displayMap[x5Surface]?.let { window ->
            if (window.physicalW != width || window.physicalH != height) {
                window.physicalW = width
                window.physicalH = height

                val targetStream = streams.values.find { it.displayWindows.contains(window) }
                if (targetStream != null && targetStream.hasFirstFrame) {
                    if (!window.x5Surface.isValid) return
                    try {
                        if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                            Matrix.setIdentityM(window.mvpMatrix, 0)
                            eglCore.drawTex2DScreen(targetStream.tex2DId, window.mvpMatrix, width, height)
                            eglCore.swapBuffers(window.eglSurface)
                        }
                    } catch (e: Exception) {
                        Log.e("VLCDecoder", "Resize swap failed: ${e.message}")
                    } finally {
                        eglCore.makeCurrentMain()
                    }
                }
            }
        }
    }

    override fun clearWorkspace() {
        isTicking = false
        choreographer?.removeFrameCallback(frameCallback)
        super.clearWorkspace()
    }
}