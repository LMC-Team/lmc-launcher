package com.lemwoodmc.launcher

import android.app.Application
import com.lemwoodmc.launcher.bridge.NativeBridge

/**
 * 应用入口。
 *
 * 启动时立即初始化 C++ 核心运行时（记录目录、读取 CPU 拓扑、探测音频低延迟能力），
 * 这样点击“开始游戏”时无需再做任何 native 侧准备，减少首帧延迟。
 */
class LmcApplication : Application() {

    lateinit var settingsRepo: com.lemwoodmc.launcher.data.SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepo = com.lemwoodmc.launcher.data.SettingsRepository(this)

        // 触发 liblmc_core.so 加载并初始化核心运行时
        val ok = NativeBridge.nativeInitCore(
            nativeLibDir = applicationInfo.nativeLibraryDir,
            filesDir = filesDir.absolutePath,
            cacheDir = cacheDir.absolutePath,
        )
        if (!ok) {
            android.util.Log.e("LMC", "核心运行时初始化失败：设备不满足 ARM64 / Android 10+ 要求")
        }

        // CallbackBridge 类的注入延迟到 GameViewModel.launchOnSurface
        // （此时 java.library.path 已设置，libpojavexec 已可加载）
    }
}
