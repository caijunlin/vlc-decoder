package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.os.Handler
import android.view.Surface

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description 渲染节点桥接接口，用于统一调度不同硬件平台下的渲染管线，定义了节点的生命周期和调度能力
 */
interface IRenderNode {
    /** 当前节点绑定的专用渲染通讯管道 */
    val handler: Handler

    /**
     * 获取当前渲染节点正在处理的活跃流数量
     * @return 活跃解码流的数量
     */
    fun getActiveStreamCount(): Int

    /**
     * 处理外部的画布绑定请求，建立流与画布的联系
     * @param url 视频流地址
     * @param x5Surface 目标画布
     * @param client 渲染客户端
     * @param opts 媒体配置参数
     * @param limit 节点最大负载限制
     */
    fun handleBind(
        url: String,
        x5Surface: Surface,
        client: IVideoRenderClient,
        opts: ArrayList<String>,
        limit: Int
    )

    /**
     * 解绑指定的画布，如果流空闲将触发销毁
     * @param url 视频流地址
     * @param x5Surface 目标画布
     */
    fun handleUnbind(url: String, x5Surface: Surface)

    /**
     * 处理画布的物理尺寸形变
     * @param x5Surface 目标画布
     * @param width 新宽度
     * @param height 新高度
     */
    fun handleResize(x5Surface: Surface, width: Int, height: Int)

    /**
     * 截取指定画布当前的清晰画面
     * @param x5Surface 目标画布
     * @param callback 异步截图回调
     */
    fun handleCapture(x5Surface: Surface, callback: (Bitmap?) -> Unit)

    /**
     * 清空画布并将其置为透明黑底
     * @param x5Surface 目标画布
     */
    fun handleClearSurface(x5Surface: Surface)

    /**
     * 打印当前节点的负载状态和内存引用用于排查异常
     * @param nodeIndex 节点索引
     */
    fun printNodeDiagnostics(nodeIndex: Int)

    /**
     * 清空当前节点的工作区，释放所有的流与画布（软清理）
     */
    fun clearWorkspace()

    /**
     * 彻底销毁节点，释放 EGL 环境并结束线程（硬清理）
     */
    fun destroyNode()
}