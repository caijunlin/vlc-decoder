package com.caijunlin.vlcdecoder.gles

import androidx.annotation.Keep

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description 声明当前渲染池所采用的并发工作模式
 */
@Keep
enum class EGLRenderMode {
    /** RK 板卡专用的狂暴轮询锁帧模式 */
    RK,

    /** 手机端专用的高兼容事件驱动模式 */
    MOBILE
}