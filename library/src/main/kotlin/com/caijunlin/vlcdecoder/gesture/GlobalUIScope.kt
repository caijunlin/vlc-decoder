package com.caijunlin.vlcdecoder.gesture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * @author caijunlin
 * @date   2026/3/11
 * @description 全局 UI 协程作用域池。
 * 采用 SupervisorJob，保证某个子协程崩溃或取消时，不会牵连其他正在执行的任务。
 */
object GlobalUIScope {
    // 整个 App 生命周期内只创建一次，随处复用
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
}