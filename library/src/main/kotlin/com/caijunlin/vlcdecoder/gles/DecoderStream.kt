package com.caijunlin.vlcdecoder.gles

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

class DecoderStream(
    val url: String,
    private val eglCore: EglCore,
    private val libVLC: LibVLC?,
    private val renderHandler: Handler
) : SurfaceTexture.OnFrameAvailableListener {

    var oesTextureId = -1
        private set
    var surfaceTexture: SurfaceTexture? = null
        private set
    private var decodeSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null

    val transformMatrix = FloatArray(16)

    @Volatile
    var frameAvailable = false
    var hasFirstFrame = false

    var videoWidth = 1280
    var videoHeight = 720

    // 这个解码流同时要画到哪些 X5 窗口上
    val displayWindows = CopyOnWriteArrayList<DisplayWindow>()

    fun start() {
        oesTextureId = eglCore.generateOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener(this@DecoderStream, renderHandler)
        }
        decodeSurface = Surface(surfaceTexture)

        libVLC?.let { vlc ->
            mediaPlayer = MediaPlayer(vlc)
            Log.i("VLC", "MediaPlayer start")
            val media = Media(vlc, Uri.parse(url))
            media.addOption(":network-caching=300")
            media.addOption(":input-repeat=65535")
            mediaPlayer?.media = media
            mediaPlayer?.aspectRatio = null
            mediaPlayer?.scale = 0f
            mediaPlayer?.vlcVout?.setVideoSurface(decodeSurface, null)
            mediaPlayer?.vlcVout?.attachViews { _, _, _, vw, vh, _, _ ->
                if (vw > 0 && vh > 0) {
                    // 收到真实的硬件分辨率，抛给 RenderPool 更新
                    renderHandler.sendMessage(
                        renderHandler.obtainMessage(
                            5,
                            arrayOf<Any>(url, vw, vh)
                        )
                    )
                }
            }
            mediaPlayer?.play()
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        frameAvailable = true
    }

    fun release() {
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        decodeSurface?.release()
        surfaceTexture?.release()
        eglCore.deleteTexture(oesTextureId)
    }
}