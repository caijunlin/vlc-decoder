package com.caijunlin.vlcdecoder.gles

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

class EglCore {
    private val egl = EGLContext.getEGL() as EGL10
    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    var dummySurface: EGLSurface = EGL10.EGL_NO_SURFACE
        private set

    private var programId = 0
    private val vertexBuffer: FloatBuffer

    init {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }
    }

    fun initEGL() {
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        egl.eglInitialize(eglDisplay, IntArray(2))
        val attribs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        egl.eglChooseConfig(eglDisplay, attribs, configs, 1, IntArray(1))
        eglConfig = configs[0]
        eglContext = egl.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL10.EGL_NO_CONTEXT,
            intArrayOf(0x3098, 3, EGL10.EGL_NONE)
        )

        val pbufferAttribs = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
        dummySurface = egl.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs)

        makeCurrent(dummySurface)
        programId = createOESProgram()
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        return egl.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null)
    }

    fun destroySurface(eglSurface: EGLSurface) {
        if (eglSurface != EGL10.EGL_NO_SURFACE) {
            egl.eglDestroySurface(eglDisplay, eglSurface)
        }
    }

    fun makeCurrent(eglSurface: EGLSurface): Boolean {
        return egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun swapBuffers(eglSurface: EGLSurface) {
        egl.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // 直接向底层 EGL 查询 Surface 的真实物理发光像素宽度！
    fun querySurfaceWidth(eglSurface: EGLSurface): Int {
        val sizeCache = IntArray(1)
        egl.eglQuerySurface(eglDisplay, eglSurface, EGL10.EGL_WIDTH, sizeCache)
        return sizeCache[0]
    }

    // 查询真实的物理高度
    fun querySurfaceHeight(eglSurface: EGLSurface): Int {
        val sizeCache = IntArray(1)
        egl.eglQuerySurface(eglDisplay, eglSurface, EGL10.EGL_HEIGHT, sizeCache)
        return sizeCache[0]
    }

    fun generateOESTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val oesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR.toFloat()
        )
        GLES30.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR.toFloat()
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        return oesTextureId
    }

    fun deleteTexture(textureId: Int) {
        if (textureId != -1) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    fun drawOES(
        oesTextureId: Int,
        transformMatrix: FloatArray,
        mvpMatrix: FloatArray,
        width: Int,
        height: Int
    ) {
        GLES30.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        val uMatrixLoc = GLES30.glGetUniformLocation(programId, "uTransformMatrix")
        GLES30.glUniformMatrix4fv(uMatrixLoc, 1, false, transformMatrix, 0)

        val uMvpLoc = GLES30.glGetUniformLocation(programId, "uMVPMatrix")
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "texOES"), 0)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun clearBlack() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun createOESProgram(): Int {
        val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 aPosition;
            layout(location = 1) in vec4 aTexCoord;
            uniform mat4 uTransformMatrix;
            uniform mat4 uMVPMatrix; 
            out vec2 vTexCoord;
            void main() { 
                gl_Position = uMVPMatrix * aPosition; 
                vTexCoord = (uTransformMatrix * aTexCoord).xy; 
            }
        """
        val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTexCoord;
            uniform samplerExternalOES texOES;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(texOES, vTexCoord);
            }
        """
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
            .also { GLES30.glShaderSource(it, VERTEX_SHADER); GLES30.glCompileShader(it) }
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
            .also { GLES30.glShaderSource(it, FRAGMENT_SHADER); GLES30.glCompileShader(it) }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v); GLES30.glAttachShader(
            it,
            f
        ); GLES30.glLinkProgram(it)
        }
    }
}