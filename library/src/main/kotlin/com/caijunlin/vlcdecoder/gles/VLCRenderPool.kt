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
 * @date   2026/3/4
 * @description   极速多线程核心调度池作为全局对外唯一的应用接口门面。
 * 通过内部路由算法将高强度的渲染压力均匀分发至底层的多个私有渲染节点
 */
object VLCRenderPool {

    // 单条视频流缺省的媒体控制参数，默认开启循环播放及基础网络缓存
    private val defaultMediaArgs = arrayListOf(
        ":network-caching=300",
        ":input-repeat=65535"
    )

    // 设定允许并发解析的系统全局最大流数量上限防范榨干算力
    @Volatile
    private var maxStreamLimit = 16

    // 预先建立独立的底层渲染节点负责承接拆分后的并发重压
    private val NODE_COUNT =
        kotlin.math.min(1, kotlin.math.min(Runtime.getRuntime().availableProcessors() / 2, 4))

    // 初始化节点数组并在节点内部流意外死亡时接收回调清理总路由表
    private val renderNodes = Array(NODE_COUNT) { index ->
        RenderNode("VlcRenderNode-$index") { _, deadSurfaces ->
            deadSurfaces.forEach { surfaceRouteMap.remove(it) }
        }
    }

    // 全局中枢路由字典记录物理画布被分配到了哪个具体视频流以实现跨线程追踪
    private val surfaceRouteMap = ConcurrentHashMap<Surface, String>()
    private val clientRouteMap =
        synchronizedMap(java.util.WeakHashMap<IVideoRenderClient, String>())

    // 动态下达修改并发阈值的指令更新安全限制
    fun setMaxStreamCount(maxCount: Int) {
        this.maxStreamLimit = maxCount
    }

    // 核心路由算法执行体根据资源地址的哈希数值取模均匀散列分配至对应节点
    private fun getNodeByUrl(url: String): RenderNode {
        return renderNodes[abs(url.hashCode()) % NODE_COUNT]
    }

    // 接收外部绑定请求通过路由表匹配节点后投递渲染任务至目标线程
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
            // 参数直接拆包传给底层
            node.handleBind(url, x5Surface, client, mediaOptions, maxStreamLimit)
        }
    }

    // 接收外部解绑请求循迹找出归属节点投递物理剥离指令
    fun unbindClient(url: String, client: IVideoRenderClient) {
        clientRouteMap.remove(client)
        val x5Surface = client.getTargetSurface() ?: return
        val node = getNodeByUrl(url)
        node.handler.post {
            node.handleUnbind(url, x5Surface)
        }
    }

    // 安全执行跨节点的底层视频源切换仪式防范图形表面挂载崩溃
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
        } else { // 同节点或无旧节点情况合并处理
            newNode.handler.post {
                if (oldNode != null) {
                    newNode.handleUnbind(oldUrl, x5Surface)
                }
                newNode.handleBind(newUrl, x5Surface, client, mediaOptions, maxStreamLimit)
            }
        }
    }

    // 透传前端画布的尺寸异动数据至对应掌控的渲染节点
    fun resizeClient(client: IVideoRenderClient) {
        val url = clientRouteMap[client] ?: return
        val x5Surface = client.getTargetSurface() ?: return
        val node = getNodeByUrl(url)
        node.handler.post {
            node.handleResize(x5Surface, client.getTargetWidth(), client.getTargetHeight())
        }
    }

    // 提供非阻塞提取净显像数据服务供外部处理
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

    // 接收指令强行清空残留画面恢复底层原本形态
    fun clearClient(client: IVideoRenderClient) {
        val x5Surface = client.getTargetSurface() ?: return
        val url = clientRouteMap[client]
        // 鉴于清屏操作可能在完全剥离后触发若无路由记录则交由首位节点代劳提供临时环境
        val node = if (url != null) getNodeByUrl(url) else renderNodes[0]
        node.handler.post { node.handleClearSurface(x5Surface) }
    }

    // 统筹下达命令要求所有节点上报诊断树结构
    fun printDiagnostics() {
        Log.w("VLCDecoder", "ENGINE DIAGNOSTICS")
        var totalStreams = 0
        renderNodes.forEach { totalStreams += it.streams.size }
        Log.w("VLCDecoder", "Total Active Decoders $totalStreams Limit $maxStreamLimit")

        // 委派给各个独立分支去打印自己名下管控的流及画面数据阵列
        renderNodes.forEachIndexed { index, node ->
            node.printNodeDiagnostics(index)
        }
    }

    /**
     * 软释放：关闭当前工程/网页时调用。
     * 仅清空路由表和播放资源，保留 4 个节点的 EGL 线程底座，随时准备秒开下一个工程。
     */
    fun releaseWorkspace() {
        surfaceRouteMap.clear()
        renderNodes.forEach { node ->
            node.handler.post { node.clearWorkspace() }
        }
    }

    /**
     * 硬释放：彻底退出 App 时调用。
     * 发送核平指令，让所有节点销毁 EGL 环境并结束线程。
     */
    fun release() {
        surfaceRouteMap.clear()
        renderNodes.forEach { node ->
            node.destroyNode()
        }
    }

}