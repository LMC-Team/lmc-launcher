package com.movtery.zalithlauncher.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep

/**
 * Zalith 版 libpojavexec(_awt) 的 dex 回调目标（JNI CallStaticMethodID）：
 *  - querySystemClipboard: 游戏请求读剪贴板 → clipboardReceived 回传
 *  - putClipboardData:     游戏写剪贴板
 *  - jvmExit:              JVM 退出回调（exit_hook 触发）
 *  - openLink:             游戏请求打开链接/分享文件
 *
 * 裁剪自 ZalithLauncher ZLNativeInvoker（去除 GlobalContext/BuildKeys 依赖，
 * Activity 由 GameViewModel 注入）。
 */
@Keep
object ZLNativeInvoker {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前游戏 Activity（启动时注入，退出时清空） */
    @JvmStatic
    var gameActivity: Activity? = null

    /** JVM 退出回调（GameViewModel 注册） */
    @JvmStatic
    var onJvmExit: ((code: Int, isSignal: Boolean) -> Unit)? = null

    @Keep
    @JvmStatic
    fun openLink(link: String) {
        // lmc 骨架：链接打开转发到宿主 Activity（后续接 WebView/分享面板）
        android.util.Log.i("ZLNativeInvoker", "openLink: $link")
    }

    @Keep
    @JvmStatic
    fun querySystemClipboard() {
        val activity = gameActivity ?: return
        activity.runOnUiThread {
            val cm = activity.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
            ZLBridge.clipboardReceived(text, if (text != null) "plain" else null)
        }
    }

    @Keep
    @JvmStatic
    fun putClipboardData(data: String, mimeType: String) {
        val activity = gameActivity ?: return
        activity.runOnUiThread {
            val clipData = when (mimeType) {
                "text/plain" -> ClipData.newPlainText("lmc", data)
                "text/html" -> ClipData.newHtmlText("lmc", data, data)
                else -> null
            }
            clipData?.let {
                (activity.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(it)
            }
        }
    }

    @Keep
    @JvmStatic
    fun jvmExit(exitCode: Int, isSignal: Boolean) {
        android.util.Log.i("ZLNativeInvoker", "jvmExit code=$exitCode signal=$isSignal")
        onJvmExit?.invoke(exitCode, isSignal)
        gameActivity = null
    }
}
