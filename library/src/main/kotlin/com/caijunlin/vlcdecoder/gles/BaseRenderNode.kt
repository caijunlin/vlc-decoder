package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.opengl.EGL14
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.CallSuper
import java.util.concurrent.ConcurrentHashMap

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description 渲染管线节点的纯逻辑抽象层，提纯了线程管理、字典维护与通用的画布销毁/清空算法。
 */
abstract class BaseRenderNode<T : BaseDecoderStream>(
    val nodeName: String,
    protected val onStreamDeadCleanup: (String, List<Surface>) -> Unit
) : IRenderNode {

    val thread = object : HandlerThread(nodeName) {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            super.run()
        }
    }.apply { start() }

    override val handler = Handler(thread.looper)

    val streams = ConcurrentHashMap<String, T>()
    val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()
    val pendingReleaseTasks = ConcurrentHashMap<String, Runnable>()

    lateinit var eglCore: EGLCore

    override fun getActiveStreamCount(): Int = streams.size

    override fun handleUnbind(url: String, x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        if (window != null) {
            if (window.x5Surface.isValid && eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                eglCore.clearCurrentSurface()
                eglCore.swapBuffers(window.eglSurface)
            }
            window.release(eglCore)
        }

        val stream = streams[url]
        if (stream != null && window != null) {
            stream.displayWindows.remove(window)
            if (stream.displayWindows.isEmpty() && !pendingReleaseTasks.containsKey(url)) {
                val task = Runnable {
                    val s = streams[url]
                    if (s != null && s.displayWindows.isEmpty()) {
                        s.release()
                        streams.remove(url)
                    }
                    pendingReleaseTasks.remove(url)
                }
                pendingReleaseTasks[url] = task
                handler.postDelayed(task, 500L)
            }
        }
    }

    override fun handleCapture(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        val window = displayMap[x5Surface]
        val mainHandler = Handler(Looper.getMainLooper())
        if (window == null) {
            mainHandler.post { callback(null) }
            return
        }
        val targetStream = streams.values.find { it.displayWindows.contains(window) }
        if (targetStream == null || targetStream.fboId == -1) {
            mainHandler.post { callback(null) }
            return
        }
        eglCore.makeCurrentMain()
        eglCore.readPixelsFromFBOAsync(targetStream.fboId, targetStream.videoWidth, targetStream.videoHeight, handler, callback)
    }

    override fun handleCaptureSync(x5Surface: Surface): Bitmap? {
        val window = displayMap[x5Surface] ?: return null
        val targetStream = streams.values.find { it.displayWindows.contains(window) }
        if (targetStream == null || targetStream.fboId == -1) return null

        eglCore.makeCurrentMain()
        return eglCore.readPixelsFromFBOSync(targetStream.fboId, targetStream.videoWidth, targetStream.videoHeight)
    }

    override fun handleClearSurface(x5Surface: Surface) {
        val window = displayMap[x5Surface]
        if (window != null && window.x5Surface.isValid) {
            if (eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) {
                eglCore.clearCurrentSurface()
                eglCore.swapBuffers(window.eglSurface)
            }
        } else if (x5Surface.isValid) {
            val tempEgl = eglCore.createWindowSurface(x5Surface)
            if (tempEgl != EGL14.EGL_NO_SURFACE) {
                if (eglCore.makeCurrent(tempEgl, eglCore.eglContext)) {
                    eglCore.clearCurrentSurface()
                    eglCore.swapBuffers(tempEgl)
                }
                eglCore.destroySurface(tempEgl)
            }
        }
    }

    protected fun handleStreamDead(url: String) {
        val dead = streams.remove(url) ?: return
        pendingReleaseTasks.remove(url)?.let { handler.removeCallbacks(it) }

        val deadSurfaces = mutableListOf<Surface>()
        dead.displayWindows.forEach { window ->
            val key = displayMap.entries.find { it.value == window }?.key
            if (key != null) {
                displayMap.remove(key)
                deadSurfaces.add(key)
            }
            window.release(eglCore)
        }
        dead.release()
        onStreamDeadCleanup(url, deadSurfaces)
    }

    override fun printNodeDiagnostics(nodeIndex: Int) {
        handler.post {
            if (streams.isEmpty()) return@post
            Log.w("VLCDecoder", "------ Node-$nodeIndex ($nodeName) ------")
            var index = 1
            streams.forEach { (url, stream) ->
                Log.i("VLCDecoder", "[$index] Stream URL: $url")
                Log.i("VLCDecoder", "    |- Is Decoding : ${stream.isDecoding}")
                Log.i("VLCDecoder", "    |- Active Surfaces: ${stream.displayWindows.size}")
                val isPending = pendingReleaseTasks.containsKey(url)
                Log.i("VLCDecoder", "    |- Is Pending Release: $isPending")
                stream.displayWindows.forEachIndexed { winIndex, window ->
                    val surfaceHex = Integer.toHexString(window.x5Surface.hashCode())
                    Log.i("VLCDecoder", "       |- Surface_$winIndex @$surfaceHex -> Size: ${window.physicalW}x${window.physicalH}")
                }
                index++
            }
        }
    }

    @CallSuper
    override fun clearWorkspace() {
        pendingReleaseTasks.values.forEach { handler.removeCallbacks(it) }
        pendingReleaseTasks.clear()

        eglCore.makeCurrentMain()
        streams.values.forEach { it.release() }
        streams.clear()
        displayMap.values.forEach { it.release(eglCore) }
        displayMap.clear()
    }

    override fun destroyNode() {
        clearWorkspace()
        handler.post {
            eglCore.release()
            thread.quitSafely()
        }
    }
}