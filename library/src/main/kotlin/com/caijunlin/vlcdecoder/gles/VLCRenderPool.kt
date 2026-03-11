package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.caijunlin.vlcdecoder.core.VLCEngineManager
import java.util.Collections.synchronizedMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description 视频渲染全局调度池。根据传入模式隔离调度具体的底层实现管线，实现业务与硬解的透明解耦。
 */
object VLCRenderPool {

    @Volatile
    var model: RenderMode = RenderMode.RK

    private val defaultMediaArgs = arrayListOf(
        ":network-caching=300",
        ":input-repeat=65535"
    )

    @Volatile
    private var maxStreamLimit = 16

    private val NODE_COUNT: Int by lazy {
        if (model == RenderMode.MOBILE) {
            kotlin.math.max(1, kotlin.math.min(Runtime.getRuntime().availableProcessors() / 2, 6))
        } else {
            4
        }
    }

    private val renderNodes: Array<IRenderNode> by lazy {
        Array(NODE_COUNT) { index ->
            if (model == RenderMode.MOBILE) {
                com.caijunlin.vlcdecoder.gles.mobile.RenderNode("VlcNode-Mobile-$index") { _, deadSurfaces ->
                    deadSurfaces.forEach { surfaceRouteMap.remove(it) }
                }
            } else {
                com.caijunlin.vlcdecoder.gles.rk.RenderNode("VlcNode-RK-$index") { _, deadSurfaces ->
                    deadSurfaces.forEach { surfaceRouteMap.remove(it) }
                }
            }
        }
    }

    private val surfaceRouteMap = ConcurrentHashMap<Surface, String>()
    private val clientRouteMap =
        synchronizedMap(java.util.WeakHashMap<IVideoRenderClient, String>())

    fun setMaxStreamCount(maxCount: Int) {
        this.maxStreamLimit = maxCount
    }

    private fun getNodeByUrl(url: String): IRenderNode {
        return renderNodes[abs(url.hashCode()) % NODE_COUNT]
    }

    fun bindClient(
        url: String,
        client: IVideoRenderClient,
        mediaOptions: ArrayList<String> = defaultMediaArgs
    ) {
        if (url.isEmpty() || VLCEngineManager.libVLC == null) return
        val x5Surface = client.getTargetSurface() ?: return
        clientRouteMap[client] = url
        val node = getNodeByUrl(url)
        node.handler.post {
            node.handleBind(url, x5Surface, client, mediaOptions, maxStreamLimit)
        }
    }

    fun unbindClient(url: String, client: IVideoRenderClient) {
        clientRouteMap.remove(client)
        val x5Surface = client.getTargetSurface() ?: return
        val node = getNodeByUrl(url)
        node.handler.post {
            node.handleUnbind(url, x5Surface)
        }
    }

    fun switchClientUrl(
        oldUrl: String,
        newUrl: String,
        client: IVideoRenderClient,
        mediaOptions: ArrayList<String> = defaultMediaArgs
    ) {
        if (newUrl.isEmpty() || VLCEngineManager.libVLC == null) return
        val x5Surface = client.getTargetSurface() ?: return

        clientRouteMap[client] = newUrl
        val oldNode = if (oldUrl.isNotEmpty()) getNodeByUrl(oldUrl) else null
        val newNode = getNodeByUrl(newUrl)
        if (oldNode != null && oldNode !== newNode) {
            oldNode.handler.post {
                oldNode.handleUnbind(oldUrl, x5Surface)
                newNode.handler.post {
                    newNode.handleBind(newUrl, x5Surface, client, mediaOptions, maxStreamLimit)
                }
            }
        } else {
            newNode.handler.post {
                if (oldNode != null) {
                    newNode.handleUnbind(oldUrl, x5Surface)
                }
                newNode.handleBind(newUrl, x5Surface, client, mediaOptions, maxStreamLimit)
            }
        }
    }

    fun resizeClient(client: IVideoRenderClient) {
        val url = clientRouteMap[client] ?: return
        val x5Surface = client.getTargetSurface() ?: return
        val node = getNodeByUrl(url)
        node.handler.post {
            node.handleResize(x5Surface, client.getTargetWidth(), client.getTargetHeight())
        }
    }

    fun captureClientFrame(client: IVideoRenderClient, callback: (Bitmap?) -> Unit) {
        val url = clientRouteMap[client]
        if (url == null) {
            Handler(Looper.getMainLooper()).post { callback(null) }
            return
        }
        val x5Surface = client.getTargetSurface() ?: return
        val node = getNodeByUrl(url)
        node.handler.post { node.handleCapture(x5Surface, callback) }
    }

    fun clearClient(client: IVideoRenderClient) {
        val x5Surface = client.getTargetSurface() ?: return
        val url = clientRouteMap[client]
        val node = if (url != null) getNodeByUrl(url) else renderNodes[0]
        node.handler.post { node.handleClearSurface(x5Surface) }
    }

    fun printDiagnostics() {
        Log.w("VLCDecoder", "ENGINE DIAGNOSTICS (Mode: $model)")
        var totalStreams = 0
        renderNodes.forEach { totalStreams += it.getActiveStreamCount() }
        Log.w("VLCDecoder", "Total Active Decoders $totalStreams Limit $maxStreamLimit")

        renderNodes.forEachIndexed { index, node ->
            node.printNodeDiagnostics(index)
        }
    }

    fun releaseWorkspace() {
        surfaceRouteMap.clear()
        renderNodes.forEach { node ->
            node.handler.post { node.clearWorkspace() }
        }
    }

    fun release() {
        surfaceRouteMap.clear()
        renderNodes.forEach { node ->
            node.destroyNode()
        }
    }
}