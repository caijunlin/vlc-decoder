package com.caijunlin.vlcdecoder.core

import android.content.Context
import android.view.Surface
import com.caijunlin.vlcdecoder.gles.VlcRenderPool
import org.videolan.libvlc.LibVLC
import kotlin.text.isEmpty

object VlcStreamManager {

    private var libVLC: LibVLC? = null

    private val vlcArgs = arrayListOf(
        "--no-audio",
        "--aout=dummy",
        "--rtsp-tcp",
        "--network-caching=600",
        "--drop-late-frames",
        "--skip-frames",
        "--codec=mediacodec,all"
    )

    @JvmStatic
    fun init(context: Context) {
        if (libVLC == null) {
            libVLC = LibVLC(context.applicationContext, vlcArgs)
            // 将 LibVLC 实例传给渲染池，剩下的全交给底层 DecoderStream 去做
            VlcRenderPool.setLibVLC(libVLC!!)
        }
    }

    @JvmStatic
    @Synchronized
    fun bind(url: String, x5Surface: Surface) {
        if (url.isEmpty() || libVLC == null) return
        // 只转发指令，不亲自创建 MediaPlayer
        VlcRenderPool.bindSurface(url, x5Surface)
    }

    @JvmStatic
    fun updateSurfaceSize(x5Surface: Surface) {
        VlcRenderPool.updateSurfaceSize(x5Surface)
    }

    @JvmStatic
    @Synchronized
    fun unbind(url: String, x5Surface: Surface) {
        VlcRenderPool.unbindSurface(url, x5Surface)
    }

    @JvmStatic
    fun releaseAll() {
        VlcRenderPool.releaseAll()
        libVLC?.release()
        libVLC = null
    }
}