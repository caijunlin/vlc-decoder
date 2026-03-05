package com.caijunlin.vlcdecoder.callback

import androidx.annotation.Keep

/**
 * @author : caijunlin
 * @date   : 2026/2/25
 * @description : 外部统一的内核初始化状态回调抽象类
 */
abstract class KernelInitCallback {

    @Keep
    abstract fun onSuccess(isX5Core: Boolean)

    @Keep
    abstract fun onFailed(code: Int, msg: String?)

}