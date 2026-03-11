package com.caijunlin.vlcdecoder.gles.rk

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.caijunlin.vlcdecoder.gles.BaseRenderNode
import com.caijunlin.vlcdecoder.gles.DisplayWindow
import com.caijunlin.vlcdecoder.gles.EGLCore
import com.caijunlin.vlcdecoder.gles.IVideoRenderClient
import java.util.concurrent.ConcurrentHashMap

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description RK 专属渲染管线：脱离系统编排机制，内部轮询严格控制 25FPS 与动态拥堵检测惩罚机制。
 */
class RenderNode(
    nodeName: String,
    onStreamDeadCleanup: (String, List<Surface>) -> Unit
) : BaseRenderNode<DecoderStream>(nodeName, onStreamDeadCleanup) {

    private val congestedWindows = ConcurrentHashMap<Surface, Boolean>()
    private var isTicking = false
    private val tickRunnable = Runnable { doTick() }
    private val streamsToRender = ArrayList<DecoderStream>(8)
    private val farmerMs = 40L

    init {
        handler.post {
            eglCore = EGLCore().apply { initEGL() }
        }
    }

    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            handler.post(tickRunnable)
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

        if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
            eglCore.setSwapInterval(0)
            eglCore.makeCurrentMain()
        }

        stream.displayWindows.add(window)
        displayMap[x5Surface] = window

        startTicking()
    }

    private fun doTick() {
        val tickStartNs = System.nanoTime()
        var hasActiveDraws = false
        var isDummyCurrent = false
        streamsToRender.clear()

        for (stream in streams.values) {
            if (stream.displayWindows.isEmpty()) continue
            hasActiveDraws = true

            if (stream.frameAvailable.getAndSet(false)) {
                if (!isDummyCurrent) {
                    eglCore.makeCurrentMain()
                    isDummyCurrent = true
                }
                try {
                    stream.surfaceTexture?.updateTexImage()
                    stream.lastPts = stream.surfaceTexture?.timestamp ?: 0L
                    stream.surfaceTexture?.getTransformMatrix(stream.transformMatrix)

                    if (!stream.hasFirstFrame) {
                        stream.checkAndUpdateResolution()
                        stream.hasFirstFrame = true
                        stream.displayWindows.forEach { window ->
                            window.clientRef.get()?.onFirstFrameRendered(stream.url)
                        }
                    }

                    eglCore.drawOESToFBO(stream.fboId, stream.oesTextureId, stream.transformMatrix, stream.videoWidth, stream.videoHeight)
                    streamsToRender.add(stream)
                } catch (e: Exception) {
                    Log.e("VLCDecoder", "OES mapping failed: ${e.message}")
                }
            }
        }

        if (hasActiveDraws && isDummyCurrent) {
            GLES30.glFlush()
        }

        for (stream in streams.values) {
            val windows = stream.displayWindows
            if (windows.isEmpty()) continue

            val hasNewFrame = streamsToRender.contains(stream)
            for (j in 0 until windows.size) {
                val window = windows[j]
                val pw = window.physicalW
                val ph = window.physicalH
                if (pw == 0 || ph == 0) continue

                if (hasNewFrame || window.isDirty) {
                    val isCongested = congestedWindows[window.x5Surface] ?: false
                    if (isCongested) {
                        congestedWindows[window.x5Surface] = false
                        window.isDirty = false
                        continue
                    }

                    try {
                        if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                            eglCore.setSwapInterval(0)
                            if (stream.lastPts > 0L) {
                                eglCore.setPresentationTime(window.eglSurface, stream.lastPts)
                            }
                            Matrix.setIdentityM(window.mvpMatrix, 0)
                            eglCore.drawTex2DScreen(stream.tex2DId, window.mvpMatrix, pw, ph)

                            val swapStartNs = System.nanoTime()
                            eglCore.swapBuffers(window.eglSurface)
                            val swapCostMs = (System.nanoTime() - swapStartNs) / 1_000_000f

                            if (swapCostMs > 25f) {
                                congestedWindows[window.x5Surface] = true
                            } else {
                                congestedWindows[window.x5Surface] = false
                            }
                            window.isDirty = false
                        }
                    } catch (e: Exception) {
                        Log.e("VLCDecoder", "Swap failed: ${e.message}")
                    }
                }
            }
        }

        if (hasActiveDraws) {
            val costMs = (System.nanoTime() - tickStartNs) / 1_000_000L
            val delayMs = if (costMs < farmerMs) farmerMs - costMs else 5L
            handler.postDelayed(tickRunnable, delayMs)
        } else {
            isTicking = false
            eglCore.makeCurrentMain()
        }
    }

    override fun handleResize(x5Surface: Surface, width: Int, height: Int) {
        displayMap[x5Surface]?.let {
            if (it.physicalW != width || it.physicalH != height) {
                it.physicalW = width
                it.physicalH = height
                it.isDirty = true
                startTicking()
            }
        }
    }

    override fun clearWorkspace() {
        handler.removeCallbacks(tickRunnable)
        isTicking = false
        super.clearWorkspace()
    }
}