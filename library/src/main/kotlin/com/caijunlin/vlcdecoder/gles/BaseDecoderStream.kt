package com.caijunlin.vlcdecoder.gles

import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import androidx.core.net.toUri
import com.caijunlin.vlcdecoder.core.VLCEngineManager
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author caijunlin
 * @date   2026/3/10
 * @description 单个视频流的解码核心抽象基类。负责维护单路 VLC 播放器实例与共享的生命周期监控逻辑。
 */
abstract class BaseDecoderStream(
    val url: String,
    protected val eglCore: EGLCore,
    protected val renderHandler: Handler,
    protected val mediaOptions: ArrayList<String>,
    protected val onStreamDead: (String) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    /** 接收 VLC 硬件解码吐出图形数据的底层 OES 纹理标识符 */
    var oesTextureId = -1
        protected set
    /** 包装 OES 纹理的表面对象，负责接收硬件推流 */
    var surfaceTexture: SurfaceTexture? = null
        protected set
    /** 私有的帧缓冲对象(FBO)标识符 */
    var fboId = -1
        protected set
    /** 挂载在 FBO 上的标准二维图形纹理标识符 */
    var tex2DId = -1
        protected set

    protected var decodeSurface: Surface? = null
    protected var mediaPlayer: MediaPlayer? = null

    /** OES 纹理坐标变换矩阵 */
    val transformMatrix = FloatArray(16)
    var hasFirstFrame = false

    val maxWidth = 1280
    val maxHeight = 720
    var videoWidth = maxWidth
    var videoHeight = maxHeight

    @Volatile protected var retryCount = 0
    protected val maxRetryLimit = 5

    @Volatile var isDecoding = false
        protected set

    /** 生命周期羁绊池：订阅了当前流画面的外部显示窗口集合 */
    val displayWindows = CopyOnWriteArrayList<DisplayWindow>()

    @Volatile protected var startPlayTimeMs: Long = 0L

    /** 抽象的看门狗任务，交给子类实现不同维度的卡死判定 */
    protected abstract val watchdogRunnable: Runnable

    /**
     * 计算并限制视频的安全渲染分辨率，防止单边拉伸
     * @param originalWidth 原始宽
     * @param originalHeight 原始高
     * @return 等比缩放后的宽高
     */
    protected fun getSafeResolution(originalWidth: Int, originalHeight: Int): Pair<Int, Int> {
        if (originalWidth <= 0 || originalHeight <= 0) return Pair(maxWidth, maxHeight)
        var safeW = originalWidth
        var safeH = originalHeight
        if (safeW > maxWidth || safeH > maxHeight) {
            val scale = minOf(maxWidth.toFloat() / safeW, maxHeight.toFloat() / safeH)
            safeW = (safeW * scale).toInt()
            safeH = (safeH * scale).toInt()
        }
        return Pair(safeW, safeH)
    }

    /**
     * 统一构建携带外围透传参数的媒体数据源
     * @param vlc 引擎实例
     * @return 完整配置的 Media
     */
    protected fun createMedia(vlc: LibVLC): Media {
        val media = Media(vlc, url.toUri())
        mediaOptions.forEach { media.addOption(it) }
        return media
    }

    /**
     * 提取的公共拉流逻辑，包含共享的回调与重连机制
     */
    fun start() {
        val fboData = eglCore.createFBO(videoWidth, videoHeight)
        fboId = fboData[0]
        tex2DId = fboData[1]

        oesTextureId = eglCore.generateOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener(this@BaseDecoderStream, renderHandler)
        }
        decodeSurface = Surface(surfaceTexture)

        VLCEngineManager.libVLC?.let { vlc ->
            mediaPlayer = MediaPlayer(vlc)
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            media.release()

            mediaPlayer?.scale = 0f
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"
            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> {
                        isDecoding = false
                        retryPlay()
                    }
                    MediaPlayer.Event.Playing -> {
                        isDecoding = true
                        retryCount = 0
                        startPlayTimeMs = System.currentTimeMillis()
                        onPlayStarted()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isDecoding = false
                        retryCount++
                        if (retryCount <= maxRetryLimit) {
                            renderHandler.postDelayed({ retryPlay() }, 2000L)
                        } else {
                            displayWindows.forEach { window ->
                                window.clientRef.get()?.onPlaybackFailed(url)
                            }
                            renderHandler.post { onStreamDead(url) }
                        }
                    }
                    MediaPlayer.Event.Stopped -> {
                        isDecoding = false
                    }
                }
            }
            mediaPlayer?.vlcVout?.attachViews()
            mediaPlayer?.play()

            startPlayTimeMs = System.currentTimeMillis()
            renderHandler.postDelayed(watchdogRunnable, 3000L)
        }
    }

    /**
     * 执行统一内部重启媒体源逻辑
     */
    protected fun retryPlay() {
        isDecoding = false
        mediaPlayer?.stop()
        VLCEngineManager.libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            media.release()
            mediaPlayer?.play()
            startPlayTimeMs = System.currentTimeMillis()
            retryCount++
            onPlayRetried()
        }
    }

    /**
     * 精准获取视频轨并执行内部画布换膜
     */
    fun checkAndUpdateResolution() {
        val track = mediaPlayer?.currentVideoTrack ?: return
        val (realW, realH) = getSafeResolution(track.width, track.height)

        if (realW > 0 && realH > 0 && (realW != videoWidth || realH != videoHeight)) {
            videoWidth = realW
            videoHeight = realH
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"

            eglCore.deleteFBO(fboId, tex2DId)
            val newFboData = eglCore.createFBO(videoWidth, videoHeight)
            fboId = newFboData[0]
            tex2DId = newFboData[1]
            surfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)

            displayWindows.forEach { it.isDirty = true }
        }
    }

    /**
     * 彻底释放当前流占用的全部内存
     */
    open fun release() {
        renderHandler.removeCallbacks(watchdogRunnable)
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        decodeSurface?.release()
        surfaceTexture?.release()
        eglCore.deleteTexture(oesTextureId)
        eglCore.deleteFBO(fboId, tex2DId)
    }

    /** 子类补充播放开始时的变量状态重置 */
    protected abstract fun onPlayStarted()
    /** 子类补充重试时的变量状态重置 */
    protected abstract fun onPlayRetried()
}