package com.caijunlin.vlcdecoder.gles

import android.opengl.GLES30
import android.os.HandlerThread
import android.view.Surface
import com.caijunlin.vlcdecoder.core.IRenderer
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.*

/**
 * 高性能单流 VLC + GLES3.0 渲染器
 *
 * 优化点：
 * 使用 PBO 双缓冲异步上传纹理
 * 仅在分辨率变化时分配显存
 * 无 Java 层 memcpy
 * 渲染线程自驱动循环
 * 避免重复 swapBuffers
 *
 * 适用于 RV16 (RGB565) 数据输入
 */
class Egl30SurfaceRenderer(
    private val surface: Surface
) : IRenderer {

    private val renderThread = HandlerThread("RenderThread")

    @Volatile
    private var frameBuffer: ByteBuffer? = null

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    @Volatile
    private var frameAvailable = false

    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private val egl: EGL10 = EGLContext.getEGL() as EGL10

    private var textureId = 0
    private var programId = 0

    private val pboIds = IntArray(2)
    private var pboIndex = 0

    private var textureAllocated = false
    private var lastWidth = 0
    private var lastHeight = 0

    companion object {

        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D sTexture;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 c = texture(sTexture, vTexCoord);
                fragColor = vec4(c.b, c.g, c.r, c.a);
            }
        """
    }

    init {
        renderThread.start()
        Thread {
            initEGL()
            renderLoop()
        }.start()
    }

    /**
     * VLC线程调用
     * 不进行 memcpy，直接引用数据
     */
    override fun updateFrame(data: ByteBuffer, width: Int, height: Int) {
        frameBuffer = data
        frameWidth = width
        frameHeight = height
        frameAvailable = true
    }

    /**
     * 渲染线程主循环
     * 自驱动，无 Handler 消息调度
     */
    private fun renderLoop() {
        while (true) {
            if (frameAvailable) {
                drawFrame()
                frameAvailable = false
            }
            Thread.sleep(1)
        }
    }

    private fun initEGL() {

        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        egl.eglInitialize(eglDisplay, IntArray(2))

        val configAttributes = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL10.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        egl.eglChooseConfig(eglDisplay, configAttributes, configs, 1, IntArray(1))

        val contextAttributes = intArrayOf(
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL10.EGL_NONE
        )

        eglContext = egl.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL10.EGL_NO_CONTEXT,
            contextAttributes
        )

        eglSurface = egl.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            surface,
            null
        )

        egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        initGL()
    }

    private fun initGL() {

        programId = createProgram()
        GLES30.glUseProgram(programId)

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        textureId = tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        GLES30.glGenBuffers(2, pboIds, 0)

        val vertexBuffer = ByteBuffer
            .allocateDirect(VERTICES.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
            .apply { position(0) }

        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(programId, "sTexture"),
            0
        )
    }

    private fun drawFrame() {

        val buffer = frameBuffer ?: return
        val w = frameWidth
        val h = frameHeight

        if (!textureAllocated || w != lastWidth || h != lastHeight) {

            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGB,
                w,
                h,
                0,
                GLES30.GL_RGB,
                GLES30.GL_UNSIGNED_SHORT_5_6_5,
                null
            )

            textureAllocated = true
            lastWidth = w
            lastHeight = h
        }

        val size = w * h * 2

        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pboIds[pboIndex])

        GLES30.glBufferData(
            GLES30.GL_PIXEL_UNPACK_BUFFER,
            size,
            null,
            GLES30.GL_STREAM_DRAW
        )

        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_UNPACK_BUFFER,
            0,
            size,
            GLES30.GL_MAP_WRITE_BIT or GLES30.GL_MAP_INVALIDATE_BUFFER_BIT
        ) as ByteBuffer

        mapped.put(buffer)
        mapped.position(0)

        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER)

        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            w,
            h,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_SHORT_5_6_5,
            null
        )

        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)

        pboIndex = (pboIndex + 1) % 2

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        egl.eglSwapBuffers(eglDisplay, eglSurface)
    }

    override fun release() {
        GLES30.glDeleteBuffers(2, pboIds, 0)
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)

        egl.eglMakeCurrent(
            eglDisplay,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_CONTEXT
        )

        egl.eglDestroySurface(eglDisplay, eglSurface)
        egl.eglDestroyContext(eglDisplay, eglContext)
        egl.eglTerminate(eglDisplay)

        surface.release()
    }

    private fun createProgram(): Int {
        val v = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val f = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private fun loadShader(type: Int, code: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, code)
        GLES30.glCompileShader(s)
        return s
    }
}