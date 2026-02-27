package com.caijunlin.vlcdecoder.gles

import android.content.res.Resources
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import java.util.concurrent.ConcurrentHashMap

object VlcRenderPool {
    private const val MSG_INIT = 1
    private const val MSG_BIND_SURFACE = 2
    private const val MSG_UNBIND_SURFACE = 3
    private const val MSG_TICK = 4
    private const val MSG_UPDATE_VIDEO_SIZE = 5
    private const val MSG_UPDATE_SURFACE_SIZE = 6
    private const val MSG_RELEASE_ALL = 7

    private val thread = HandlerThread("VlcRenderPool").apply { start() }
    val handler = Handler(thread.looper) { msg -> handleMessage(msg) }

    private val streams = ConcurrentHashMap<String, DecoderStream>()
    private val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()

    private lateinit var eglCore: EglCore
    private var libVLC: LibVLC? = null
    private var isTicking = false

    init {
        handler.sendEmptyMessage(MSG_INIT)
    }

    fun setLibVLC(vlc: LibVLC) {
        this.libVLC = vlc
    }

    fun bindSurface(url: String, x5Surface: Surface) {
        handler.sendMessage(handler.obtainMessage(MSG_BIND_SURFACE, arrayOf(url, x5Surface)))
    }

    fun unbindSurface(url: String, x5Surface: Surface) {
        handler.sendMessage(handler.obtainMessage(MSG_UNBIND_SURFACE, arrayOf(url, x5Surface)))
    }

    fun updateSurfaceSize(x5Surface: Surface) {
        handler.sendMessage(handler.obtainMessage(MSG_UPDATE_SURFACE_SIZE, x5Surface))
    }

    fun releaseAll() {
        handler.sendEmptyMessage(MSG_RELEASE_ALL)
    }

    private fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_INIT -> {
                eglCore = EglCore()
                eglCore.initEGL()
            }

            MSG_BIND_SURFACE -> {
                val args = msg.obj as Array<*>
                handleBind(args[0] as String, args[1] as Surface)
                startTicking()
            }

            MSG_UNBIND_SURFACE -> {
                val args = msg.obj as Array<*>
                handleUnbind(args[0] as String, args[1] as Surface)
            }

            MSG_UPDATE_VIDEO_SIZE -> {
                val args = msg.obj as Array<*>
                val url = args[0] as String
                streams[url]?.let {
                    it.videoWidth = args[1] as Int
                    it.videoHeight = args[2] as Int
                    it.surfaceTexture?.setDefaultBufferSize(it.videoWidth, it.videoHeight)
                }
            }

            MSG_UPDATE_SURFACE_SIZE -> {
                val s = msg.obj as Surface
                // 收到网页排版变化，仅仅标记需要重新获取，下一次渲染循环会自动查 EGL
                displayMap[s]?.needsUpdateSize = true
            }

            MSG_RELEASE_ALL -> {
                streams.values.forEach { it.release() }
                displayMap.values.forEach { it.release(eglCore) }
                streams.clear()
                displayMap.clear()
            }

            MSG_TICK -> doTick()
        }
        return true
    }

    private fun handleBind(url: String, x5Surface: Surface) {
        var stream = streams[url]
        if (stream == null) {
            stream = DecoderStream(url, eglCore, libVLC, handler)
            stream.start()
            streams[url] = stream
        }

        val window = DisplayWindow(x5Surface)
        window.initEGLSurface(eglCore)
        stream.displayWindows.add(window)
        displayMap[x5Surface] = window
    }

    private fun handleUnbind(url: String, x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        window?.release(eglCore)

        val stream = streams[url]
        if (stream != null) {
            stream.displayWindows.remove(window)
            if (stream.displayWindows.isEmpty()) {
                stream.release()
                streams.remove(url)
            }
        }
    }

    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            handler.sendEmptyMessage(MSG_TICK)
        }
    }

    private fun doTick() {
        var hasActiveDraws = false

        for (stream in streams.values) {
            if (stream.displayWindows.isEmpty()) continue
            hasActiveDraws = true

            if (stream.frameAvailable) {
                try {
                    eglCore.makeCurrent(eglCore.dummySurface)
                    stream.surfaceTexture?.updateTexImage()
                    stream.surfaceTexture?.getTransformMatrix(stream.transformMatrix)
                    stream.frameAvailable = false
                    stream.hasFirstFrame = true
                } catch (e: Exception) {
                }
            }

            if (!stream.hasFirstFrame) continue

            for (window in stream.displayWindows) {
                if (!eglCore.makeCurrent(window.eglSurface)) continue

                // 核心优化：按需获取，避免每帧调用 eglQuerySurface 损耗性能！
                if (window.needsUpdateSize) {
                    val cssW = eglCore.querySurfaceWidth(window.eglSurface)
                    val cssH = eglCore.querySurfaceHeight(window.eglSurface)
                    val density = Resources.getSystem().displayMetrics.density
                    Log.d("VLC" , "cssW: $cssW, cssH: $cssH, density: $density")
                    window.physicalW = (cssW * 2.0f).toInt()
                    window.physicalH = (cssH * 2.0f).toInt()
                    window.needsUpdateSize = false // 拿到后清除标记
                }

                val pw = window.physicalW
                val ph = window.physicalH

                if (pw <= 0 || ph <= 0) continue

                val mvpMatrix = calculateMVPMatrix(pw, ph, stream.videoWidth, stream.videoHeight)

                eglCore.drawOES(stream.oesTextureId, stream.transformMatrix, mvpMatrix, pw, ph)
                eglCore.swapBuffers(window.eglSurface)
            }
        }

        if (hasActiveDraws) {
            handler.sendEmptyMessageDelayed(MSG_TICK, 33)
        } else {
            isTicking = false
            eglCore.makeCurrent(eglCore.dummySurface)
        }
    }

    private fun calculateMVPMatrix(viewW: Int, viewH: Int, videoW: Int, videoH: Int): FloatArray {
        val mvpMatrix = FloatArray(16)
        Matrix.setIdentityM(mvpMatrix, 0)
        // 强制拉伸铺满
        val scaleX = 1f
        val scaleY = 1f
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
        return mvpMatrix
    }
}