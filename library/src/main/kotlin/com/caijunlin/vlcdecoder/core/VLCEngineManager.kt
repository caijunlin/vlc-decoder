package com.caijunlin.vlcdecoder.core

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * @author caijunlin
 * @date   2026/3/4
 * @description VLC 底层引擎全局管理类。
 * 专门负责维护 LibVLC 单例的初始化、硬解参数配置与底层资源销毁。
 */
object VLCEngineManager {

    // VLC 引擎的底层缺省初始化参数列表
    val defaultVlcArgs = arrayListOf(
        "--no-audio",
        "--aout=dummy",
        "--rtsp-tcp",
        "--http-reconnect",           // 允许 HTTP 内部断开重连
        "--ipv4-timeout=5000",        // TCP 握手超时时间 5 秒
        "--network-caching=300",      // 减少内存在多路并发下的堆积
        "--drop-late-frames",         // 降低丢帧阈值，如果 VLC 内部判断晚了，直接丢弃
        "--skip-frames",
        "--avcodec-skiploopfilter=4", // 彻底关闭 H.264/HEVC 的环路滤波！画质仅会损失肉眼难以察觉的 1%，但解码性能直接暴增 30%~50%！
        "--avcodec-hw=any",           // 强行允许所有形式的硬解加速
        "--codec=mediacodec,all",
        "--avcodec-threads=1",        // 限制软解时的并发线程数，防止多路软解互相抢夺CPU导致系统雪崩
        "--no-stats",                 // 关闭内部的数据统计模块，苍蝇腿也是肉
        "--no-sub-autodetect-file",
        "--no-osd",
        "--no-spu"
    )

    // 维持底层解码的全局唯一工厂引擎入口
    @Volatile
    var libVLC: LibVLC? = null
        private set

    /**
     * 初始化全局解析引擎实体并装载缺省底层优化参数
     */
    fun init(context: Context, args: ArrayList<String> = defaultVlcArgs) {
        if (libVLC == null) {
            synchronized(this) {
                if (libVLC == null) {
                    libVLC = LibVLC(context.applicationContext, args)
                }
            }
        }
    }

    /**
     * 销毁 VLC 引擎实例，释放底层 C++ 内存
     */
    fun release() {
        libVLC?.release()
        libVLC = null
    }
}