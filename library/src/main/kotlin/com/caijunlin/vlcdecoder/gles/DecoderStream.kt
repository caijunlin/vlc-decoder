package com.caijunlin.vlcdecoder.gles

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author caijunlin
 * @date   2026/2/28
 * @description   单个视频流的解码核心封装类负责维护 VLC 播放器实例管理内部 OES 纹理和 FBO 缓冲以及提供自动重连熔断能力
 */
class DecoderStream(
    val url: String,
    private val eglCore: EglCore,
    private val libVLC: LibVLC?,
    private val renderHandler: Handler
) : SurfaceTexture.OnFrameAvailableListener {

    // 接收 VLC 硬件解码数据的底层 OES 纹理标识符
    var oesTextureId = -1
        private set

    // 包装 OES 纹理用于接收图形缓冲流的纹理表面对象
    var surfaceTexture: SurfaceTexture? = null
        private set

    // 当前视频流私有的帧缓冲对象标识符用于离屏渲染
    var fboId = -1
        private set

    // 挂载在 FBO 上的标准二维图形纹理标识符
    var tex2DId = -1
        private set

    // 供给 VLC 引擎用于视频输出的安卓原生表面对象
    private var decodeSurface: Surface? = null

    // 负责解码与播放当前网络流的媒体播放器引擎
    private var mediaPlayer: MediaPlayer? = null

    // 存储从 SurfaceTexture 获取的 OES 纹理坐标变换矩阵
    val transformMatrix = FloatArray(16)

    // 标记底层是否已经有新的视频帧解码完毕等待更新
    @Volatile
    var frameAvailable = false

    // 标记当前视频流是否已经成功渲染出了第一帧画面
    var hasFirstFrame = false

    // 底层 OES 纹理的内部渲染宽度设置
    var videoWidth = 640

    // 底层 OES 纹理的内部渲染高度设置
    var videoHeight = 360

    // 记录当前视频流在遇到播放异常时已经尝试重新连接的累计次数
    @Volatile
    private var retryCount = 0

    // 定义系统允许该视频流进行连续异常重连的最大容忍次数
    private val maxRetryLimit = 5

    // 订阅了当前视频流画面更新的所有外部显示窗口集合使用并发集合保障遍历安全
    val displayWindows = CopyOnWriteArrayList<DisplayWindow>()

    /**
     * 启动视频解码流申请内部 FBO 和 OES 纹理内存并驱动 VLC 引擎开始解码播放
     */
    fun start() {
        val fboData = eglCore.createFBO(videoWidth, videoHeight)
        fboId = fboData[0]
        tex2DId = fboData[1]

        oesTextureId = eglCore.generateOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener(this@DecoderStream, renderHandler)
        }
        decodeSurface = Surface(surfaceTexture)

        libVLC?.let { vlc ->
            mediaPlayer = MediaPlayer(vlc)
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            mediaPlayer?.scale = 0f
            mediaPlayer?.vlcVout?.setWindowSize(videoWidth, videoHeight)
            mediaPlayer?.aspectRatio = "$videoWidth:$videoHeight"
            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EndReached -> Log.i("VLCDecoder", "Playback reached end")
                    MediaPlayer.Event.Playing -> {
                        Log.i("VLCDecoder", "Playback started")
                        // 播放成功时将重试计数器归零恢复健康状态
                        retryCount = 0
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e("VLCDecoder", "Playback encountered error")
                        retryCount++
                        if (retryCount <= maxRetryLimit) {
                            Log.w("VLCDecoder", "Preparing to retry connection")
                            // 必须通过系统消息队列延迟抛出重连任务绝对禁止在当前回调线程内操作播放器防范死锁崩溃
                            renderHandler.postDelayed({ retryPlay() }, 2000L)
                        } else {
                            Log.e("VLCDecoder", "Max retries reached stream declared dead")
                            // 发送代号为十的阵亡指令将自身地址附带传递给中央调度池请求执行销毁裁决
                            renderHandler.sendMessage(Message.obtain(renderHandler, 10, url))
                        }
                    }
                    MediaPlayer.Event.Stopped -> Log.i("VLCDecoder", "Playback stopped")
                }
            }
            mediaPlayer?.vlcVout?.attachViews()
            mediaPlayer?.play()
        }
    }

    /**
     * 执行内部重启媒体源并播放的独立封装逻辑供延迟重连机制调用
     */
    private fun retryPlay() {
        mediaPlayer?.stop()
        libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            mediaPlayer?.play()
        }
    }

    /**
     * 冻结当前视频流的解码进度切断连接清空底层解码缓冲
     */
    fun pause() {
        mediaPlayer?.stop()
    }

    /**
     * 唤醒被冻结的视频流重新构建媒体对象强制与服务端重新握手拉取实时画面
     */
    fun resume() {
        libVLC?.let { vlc ->
            val media = createMedia(vlc)
            mediaPlayer?.media = media
            mediaPlayer?.play()
        }
    }

    /**
     * 构建统一配置的媒体数据源对象封装网络缓存及重试等共用参数
     * @param vlc 驱动底层解析的 LibVLC 核心引擎实例
     * @return 携带完整播放配置选项属性的媒体资源对象
     */
    private fun createMedia(vlc: LibVLC): Media {
        val media = Media(vlc, Uri.parse(url))
        media.addOption(":network-caching=300")
        media.addOption(":input-repeat=65535")
        return media
    }

    /**
     * 底层图形缓冲队列接收到新帧数据时的异步回调事件
     * @param st 触发回调的表面纹理对象
     */
    override fun onFrameAvailable(st: SurfaceTexture) {
        frameAvailable = true
    }

    /**
     * 停止播放并彻底释放当前视频流占用的 VLC 引擎和底层 OpenGL 纹理内存资源
     */
    fun release() {
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        decodeSurface?.release()
        surfaceTexture?.release()
        eglCore.deleteTexture(oesTextureId)
        eglCore.deleteFBO(fboId, tex2DId)
    }
}