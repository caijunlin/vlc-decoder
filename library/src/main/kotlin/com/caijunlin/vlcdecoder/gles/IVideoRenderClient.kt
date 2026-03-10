package com.caijunlin.vlcdecoder.gles

import android.view.Surface

/**
 * @author : caijunlin
 * @date   : 2026/3/10
 * @description   : 视频渲染客户端接口用于取代繁琐的参数传递，并接收底层引擎异步反馈的真实业务状态。
 */
interface IVideoRenderClient {
    // 获取当前渲染节点的标识符
    fun getUUId(): String
    // 获取目标物理画布
    fun getTargetSurface(): Surface?

    // 获取画布当前物理宽度
    fun getTargetWidth(): Int

    // 获取画布当前物理高度
    fun getTargetHeight(): Int

    // 底层真正解码出第一帧并渲染上屏时的回调
    fun onFirstFrameRendered(url: String)

    // 底层重试耗尽，彻底宣告流死亡时的回调
    fun onPlaybackFailed(url: String)

}