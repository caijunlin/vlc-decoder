package com.caijunlin.vlcdecoder.core

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.caijunlin.vlcdecoder.callback.KernelInitCallback
import com.caijunlin.vlcdecoder.gles.VlcRenderPool
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.export.external.interfaces.IAuthRequestCallback
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsFramework
import com.tencent.smtt.sdk.X5Downloader
import java.io.File
import java.io.FileOutputStream

/**
 * @author : caijunlin
 * @date   : 2026/2/25
 * @description : X5内核生命周期管理类
 */
object KernelManager {
    private val kernelVersion = 48445
    private val kernelFile = "tbs_core_048445_arm64-v8a.tbs"
    // 内部缓存初始化状态，解决时序和粘性事件问题
    private var isFinished = false
    private var isSuccess = false
    private var cachedIsX5Core = false
    private var cachedErrCode = 0
    private var cachedErrMsg: String? = null

    // 存储当前注册的回调
    private var callback: KernelInitCallback? = null

    /**
     * 判断当前是否是主进程
     */
    private fun isMainProcess(application: Application): Boolean {
        val pid = Process.myPid()
        val am =
            application.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val processName = am.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
        return processName == application.packageName
    }

    /**
     * 注册监听回调。
     * 如果注册时内核已经初始化完毕，会立刻将缓存的结果回调出去，解决时序差问题。
     */
    fun registerCallback(callback: KernelInitCallback) {
        // 如果已经执行结束，立即分发缓存的结果
        if (isFinished) {
            if (isSuccess) {
                callback.onSuccess(cachedIsX5Core)
            } else {
                callback.onFailed(cachedErrCode, cachedErrMsg)
            }
        }
        this.callback = callback
    }

    /**
     * 初始化内核入口
     */
    fun init(application: Application, authCode: String) {
        if (authCode.isBlank()) {
            Log.e("VLCDecoder", "Init fail: authCode is empty or blank")
            dispatchFailed(-1, "AuthCode is empty")
            return
        }
        if (!isMainProcess(application)) {
            return
        }
        // 重置状态
        isFinished = false
        isSuccess = false
        VlcRenderPool.initLibVLC(application)
        installX5SelfHosted(application, authCode)
    }

    /**
     * 释放内核及相关资源
     */
    fun release(context: Context) {
        Log.i("VLCDecoder", "Rel X5 & VLC res...")
        QbSdk.clearAllWebViewCache(context, true)
        // 清理回调和状态
        callback = null
        isFinished = false
        isSuccess = false
    }

    /**
     * 安装或加载X5自运营版
     */
    private fun installX5SelfHosted(application: Application, authCode: String) {
        val map = HashMap<String, Any>()
        map[TbsCoreSettings.MULTI_PROCESS_ENABLE] = TbsCoreSettings.Render.MULTI_PROCESS_OPEN
        QbSdk.initTbsSettings(map)

        TbsFramework.setUp(application, authCode)
        QbSdk.usePrivateCDN(QbSdk.PrivateCDNMode.STANDARD_IMPL)

        val version = QbSdk.getTbsVersion(application)
        val needInstallOrUpdateX5 = version != kernelVersion

        if (needInstallOrUpdateX5) {
            val downloader = object : X5Downloader(application) {
                override fun onFinished() {
                    Log.i("VLCDecoder", "Inst done, prep auth")
                    doAuthAndInit(application)
                }

                override fun onFailed(code: Int, msg: String?) {
                    Log.e("VLCDecoder", "Inst fail: $msg $code")
                    dispatchFailed(code, msg)
                }
            }

            downloader.setTargetX5Version(kernelVersion)
            val outFile = File(application.cacheDir, kernelFile)

            if (!outFile.exists() || outFile.length() == 0L) {
                Log.d("VLCDecoder", "Copy assets...")
                application.assets.open(kernelFile).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("VLCDecoder", "Copy done")
            } else {
                Log.d("VLCDecoder", "Cache exist, skip copy")
            }

            downloader.installX5(outFile)
        } else {
            doAuthAndInit(application)
        }
    }

    /**
     * 抽取公共的 授权与初始化逻辑
     */
    private fun doAuthAndInit(application: Application) {
        TbsFramework.authenticateX5(false, object : IAuthRequestCallback {
            override fun onResponse(license: String?) {
                QbSdk.preInit(application, object : QbSdk.PreInitCallback {
                    override fun onCoreInitFinished() {
                        Log.i("VLCDecoder", "CoreInit done")
                    }

                    override fun onViewInitFinished(isX5Core: Boolean) {
                        Log.i("VLCDecoder", "ViewInit done, x5: $isX5Core")
                        dispatchSuccess(isX5Core)
                    }
                })
            }

            override fun onFailed(code: Int, msg: String?) {
                Log.e("VLCDecoder", "Auth fail: $msg $code")
                dispatchFailed(code, msg)
            }
        })
    }

    /**
     * 统一处理成功状态并分发
     */
    private fun dispatchSuccess(isX5Core: Boolean) {
        isFinished = true
        isSuccess = true
        cachedIsX5Core = isX5Core
        callback?.onSuccess(isX5Core)
    }

    /**
     * 统一处理失败状态并分发
     */
    private fun dispatchFailed(code: Int, msg: String?) {
        isFinished = true
        isSuccess = false
        cachedErrCode = code
        cachedErrMsg = msg
        callback?.onFailed(code, msg)
    }

}