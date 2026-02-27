package com.caijunlin.vlcdecoder.gles

import android.view.Surface
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLSurface

class DisplayWindow(val x5Surface: Surface) {
    var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
        private set

    // 缓存真实的物理宽高，避免每帧重复调用 eglQuerySurface
    var physicalW: Int = 0
    var physicalH: Int = 0

    // 标记位：当它为 true 时，才去查显卡获取宽高
    @Volatile
    var needsUpdateSize = true

    fun initEGLSurface(eglCore: EglCore) {
        if (eglSurface == EGL10.EGL_NO_SURFACE) {
            eglSurface = eglCore.createWindowSurface(x5Surface)
            needsUpdateSize = true
        }
    }

    fun release(eglCore: EglCore) {
        eglCore.destroySurface(eglSurface)
        eglSurface = EGL10.EGL_NO_SURFACE
    }
}