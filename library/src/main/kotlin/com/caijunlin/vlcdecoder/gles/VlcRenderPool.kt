package com.caijunlin.vlcdecoder.gles

import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import org.videolan.libvlc.LibVLC
import java.util.concurrent.ConcurrentHashMap

/**
 * @author caijunlin
 * @date   2026/2/28
 * @description   极速单线程核心调度池统筹管理所有播放流具备接管废弃死流并自动切断关联组件的安全熔断清除能力
 */
object VlcRenderPool {
    // 触发引擎底层环境构建逻辑的指令数值
    private const val MSG_INIT = 1

    // 触发外部视口视图绑定及流载入逻辑的指令数值
    private const val MSG_BIND_SURFACE = 2

    // 触发剔除旧视口及判断视频销毁逻辑的指令数值
    private const val MSG_UNBIND_SURFACE = 3

    // 触发接收并更新窗口物理高宽变更数据的指令数值
    private const val MSG_UPDATE_SURFACE_SIZE = 5

    // 触发核平引擎销毁全盘所有缓存以及对象的指令数值
    private const val MSG_RELEASE_ALL = 6

    // 触发挂起指定视频解码工作的指令数值
    private const val MSG_PAUSE_STREAM = 7

    // 触发唤醒指定视频解码工作的指令数值
    private const val MSG_RESUME_STREAM = 8

    // 触发提取并返回当前活跃表面图像数据的指令数值
    private const val MSG_CAPTURE_FRAME = 9

    // 触发将无可救药的坏死视频流彻底剥离出系统池的指令数值
    private const val MSG_STREAM_DEAD = 10

    // 引擎核心运作维持生命周期的后台守护守护线程实例
    private val thread = HandlerThread("VlcRenderPool").apply { start() }

    // 解析外部行为命令并投递进入引擎循环处理的主控通讯通道
    val handler = Handler(thread.looper) { msg -> handleMessage(msg) }

    // 集中缓存检索系统中当前存活活跃的所有视频播放输入流实例字典
    private val streams = ConcurrentHashMap<String, DecoderStream>()

    // 集中缓存检索系统中目前承载最终输出画面显像的目标视口实例字典
    private val displayMap = ConcurrentHashMap<Surface, DisplayWindow>()

    // 统领全盘执行所有底层硬件调用和数据操作计算的唯一图形引擎环境核心
    private lateinit var eglCore: EglCore

    // 从系统外部动态挂载切入用于构建输入流解压过程的解码器工厂实例
    private var libVLC: LibVLC? = null

    // 表示当前系统脉冲计时器是否处于持续跳动触发轮询扫描状态的运行指示标记
    private var isTicking = false

    // 获取并锚定操作硬件级真实刷新帧周期的编排器钩子句柄对象
    private var choreographer: Choreographer? = null

    // 记录向系统注册回调监听以用于承接每次跳动任务的触发挂载目标
    private var frameCallback: Choreographer.FrameCallback? = null

    // 系统允许并发解析处理的视频流数量安全阈值上限防止榨干硬件算力
    @Volatile
    private var maxStreamLimit = 16

    init {
        handler.sendEmptyMessage(MSG_INIT)
    }

    /**
     * 接收传递上层组装好的核心工厂提供后期的解析工作引用使用
     * @param vlc 已组装好参数配置的核心播放器外壳
     */
    fun setLibVLC(vlc: LibVLC) {
        this.libVLC = vlc
    }

    /**
     * 动态修改流并发容纳上限值
     * @param maxCount 新的安全阈值
     */
    fun setMaxStreamCount(maxCount: Int) {
        this.maxStreamLimit = maxCount
    }

    /**
     * 将目标视图压入执行队列与目标网络源构成呈现链接关系
     * @param url 网络源地址信息
     * @param x5Surface 终端图层展示块
     * @param width 最终显像宽度数值
     * @param height 最终显像高度数值
     */
    fun bindSurface(url: String, x5Surface: Surface, width: Int, height: Int) {
        handler.sendMessage(
            handler.obtainMessage(
                MSG_BIND_SURFACE,
                arrayOf(url, x5Surface, width, height)
            )
        )
    }

    /**
     * 解除指定目标显示区块对其视频来源地址的播放监听
     * @param url 取消绑定的源地址
     * @param x5Surface 执行剥离流程的底层区块对象
     */
    fun unbindSurface(url: String, x5Surface: Surface) {
        handler.sendMessage(handler.obtainMessage(MSG_UNBIND_SURFACE, arrayOf(url, x5Surface)))
    }

    /**
     * 当外部宿主界面缩放位移后用于将新的真实宽高界限下发至核心计算层中
     * @param x5Surface 指示尺寸发生变动的视图底层载体
     * @param width 更新到达的新宽数值
     * @param height 更新到达的新高数值
     */
    fun updateSurfaceSize(x5Surface: Surface, width: Int, height: Int) {
        handler.sendMessage(
            handler.obtainMessage(
                MSG_UPDATE_SURFACE_SIZE,
                arrayOf(x5Surface, width, height)
            )
        )
    }

    /**
     * 向系统派发冻结某条活跃数据管线的休眠指令
     * @param url 待冻结处理的数据管线源址
     */
    fun pauseStream(url: String) {
        handler.sendMessage(handler.obtainMessage(MSG_PAUSE_STREAM, url))
    }

    /**
     * 向系统派发唤醒某条处于休眠状态管线的继续运作指令
     * @param url 待唤醒处理的数据管线源址
     */
    fun resumeStream(url: String) {
        handler.sendMessage(handler.obtainMessage(MSG_RESUME_STREAM, url))
    }

    /**
     * 投递画面提取动作并附带上层提供的回调函数通道
     * @param x5Surface 待提取画面的源载体
     * @param callback 携带图板数据回传上层业务的动作通道
     */
    fun captureFrame(x5Surface: Surface, callback: (Bitmap?) -> Unit) {
        handler.sendMessage(handler.obtainMessage(MSG_CAPTURE_FRAME, arrayOf(x5Surface, callback)))
    }

    /**
     * 下达终极销毁信号让调度器回收排空全部残留显存以及工厂服务空间
     */
    fun releaseAll() {
        handler.sendEmptyMessage(MSG_RELEASE_ALL)
    }

    /**
     * 根据线程通道传达的指令类别路由调配系统内相应功能的逻辑模块处理工作
     * @param msg 含有请求数据类型的包裹信息载体
     * @return 返回执行接管的状态告知通道指令已被消费掉
     */
    private fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_INIT -> {
                eglCore = EglCore()
                eglCore.initEGL()
                choreographer = Choreographer.getInstance()
                frameCallback = Choreographer.FrameCallback { doTick() }
            }

            MSG_BIND_SURFACE -> {
                val args = msg.obj as Array<*>
                val url = args[0] as String
                val surface = args[1] as Surface
                val w = args[2] as Int
                val h = args[3] as Int
                handleBind(url, surface, w, h)
                startTicking()
            }

            MSG_UNBIND_SURFACE -> {
                val args = msg.obj as Array<*>
                handleUnbind(args[0] as String, args[1] as Surface)
            }

            MSG_UPDATE_SURFACE_SIZE -> {
                val args = msg.obj as Array<*>
                val s = args[0] as Surface
                val w = args[1] as Int
                val h = args[2] as Int
                displayMap[s]?.let {
                    it.physicalW = w
                    it.physicalH = h
                }
            }

            MSG_PAUSE_STREAM -> {
                val url = msg.obj as String
                streams[url]?.pause()
            }

            MSG_RESUME_STREAM -> {
                val url = msg.obj as String
                streams[url]?.resume()
            }

            MSG_CAPTURE_FRAME -> {
                val args = msg.obj as Array<*>
                val surface = args[0] as Surface

                @Suppress("UNCHECKED_CAST")
                val callback = args[1] as (Bitmap?) -> Unit
                handleCaptureFrame(surface, callback)
            }

            MSG_STREAM_DEAD -> {
                val url = msg.obj as String
                handleStreamDead(url)
            }

            MSG_RELEASE_ALL -> {
                streams.values.forEach { it.release() }
                displayMap.values.forEach { it.release(eglCore) }
                streams.clear()
                displayMap.clear()

                frameCallback?.let { choreographer?.removeFrameCallback(it) }
                eglCore.release()
            }
        }
        return true
    }

    /**
     * 执行拦截超量流判定并完成后续对象的拼装载入
     * @param url 建立关联的网络位置码串
     * @param x5Surface 作为画板落脚点的原生视窗体
     * @param w 所持有的画布平面宽
     * @param h 所持有的画布平面高
     */
    private fun handleBind(url: String, x5Surface: Surface, w: Int, h: Int) {
        var stream = streams[url]
        if (stream == null) {
            if (streams.size >= maxStreamLimit) {
                Log.w("VLCDecoder", "Maximum stream limit reached new source rejected")
                return
            }
            stream = DecoderStream(url, eglCore, libVLC, handler)
            stream.start()
            streams[url] = stream
        }

        val window = DisplayWindow(x5Surface)
        window.initEGLSurface(eglCore)
        window.physicalW = w
        window.physicalH = h

        stream.displayWindows.add(window)
        displayMap[x5Surface] = window
    }

    /**
     * 收回不再展现视图上占用的一切对象内存如判定源址不再需要即将其也同步砍掉清理
     * @param url 需要检验移除关系的指定资源地址
     * @param x5Surface 作为查找依据进行抛弃释放的目标基盘面
     */
    private fun handleUnbind(url: String, x5Surface: Surface) {
        val window = displayMap.remove(x5Surface)
        window?.release(eglCore)

        val stream = streams[url]
        if (stream != null) {
            stream.displayWindows.remove(window)
            if (stream.displayWindows.isEmpty()) {
                stream.release()
                streams.remove(url)
            }
        }
    }

    /**
     * 处理经过多次抢救依然未能复活的废弃视频流剥离所有依附其上的展现窗口并将其从调度集合中扫地出门
     * @param url 被宣告彻底放弃的源端地址链接
     */
    private fun handleStreamDead(url: String) {
        val deadStream = streams.remove(url)
        if (deadStream != null) {
            // 倒序或者迭代搜寻该死亡流底下的所有展示窗口并将它们一并作废
            deadStream.displayWindows.forEach { window ->
                val surfaceKey = displayMap.entries.find { it.value == window }?.key
                if (surfaceKey != null) {
                    displayMap.remove(surfaceKey)
                }
                window.release(eglCore)
            }
            deadStream.release()
        }
    }

    /**
     * 截获关联内部缓冲池的纯净图形数据通过安全通道推至主前台
     * @param surface 界面关联绑定的目标画布载体
     * @param callback 向主线程运送生成图像结果的操作函式
     */
    private fun handleCaptureFrame(surface: Surface, callback: (Bitmap?) -> Unit) {
        val window = displayMap[surface]
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
        val bmp = eglCore.readPixelsFromFBO(
            targetStream.fboId,
            targetStream.videoWidth,
            targetStream.videoHeight
        )

        mainHandler.post { callback(bmp) }
    }

    /**
     * 发出并启用硬件监听若已经处于运转挂载状况将跳开避开重置保证脉冲节奏的连贯平滑
     */
    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            frameCallback?.let { choreographer?.postFrameCallback(it) }
        }
    }

    /**
     * 基于 VSYNC 触发的周期渲染主线剥离更新解码与高速下发的行为规避主线程因为单次串行同步交接陷入长时间阻塞死胡同
     */
    private fun doTick() {
        var hasActiveDraws = false
        var isDummyCurrent = false
        val streamsToRender = ArrayList<DecoderStream>()

        // 阶段一提取画面所有产生了新图形的信号源逐一完成离线降维准备
        for (stream in streams.values) {
            if (stream.displayWindows.isEmpty()) continue
            hasActiveDraws = true

            if (stream.frameAvailable) {
                if (!isDummyCurrent) {
                    eglCore.makeCurrentMain()
                    isDummyCurrent = true
                }
                try {
                    stream.surfaceTexture?.updateTexImage()
                    stream.surfaceTexture?.getTransformMatrix(stream.transformMatrix)
                    stream.frameAvailable = false
                    stream.hasFirstFrame = true

                    eglCore.drawOESToFBO(
                        stream.fboId,
                        stream.oesTextureId,
                        stream.transformMatrix,
                        stream.videoWidth,
                        stream.videoHeight
                    )
                    streamsToRender.add(stream)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 阶段二极速推流利用非阻塞优势将画面飞速刻印在所有对应的端口上
        for (stream in streamsToRender) {
            val windowCount = stream.displayWindows.size
            for (j in 0 until windowCount) {
                val window = stream.displayWindows[j]

                if (!eglCore.makeCurrent(window.eglSurface, eglCore.eglContext)) continue

                eglCore.setSwapInterval(0)

                val pw = window.physicalW
                val ph = window.physicalH
                if (pw > 0 && ph > 0) {
                    Matrix.setIdentityM(window.mvpMatrix, 0)

                    eglCore.drawTex2DScreen(
                        stream.tex2DId,
                        window.mvpMatrix,
                        pw,
                        ph
                    )

                    eglCore.swapBuffers(window.eglSurface)
                }
            }
        }

        if (hasActiveDraws) {
            frameCallback?.let {
                choreographer?.removeFrameCallback(it)
                choreographer?.postFrameCallback(it)
            }
        } else {
            isTicking = false
            eglCore.makeCurrentMain()
        }
    }
}