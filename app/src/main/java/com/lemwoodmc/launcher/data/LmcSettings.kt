package com.lemwoodmc.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全局设置持久化（Jetpack DataStore Preferences）。
 * 版本管理 / 账户管理页也读写这里（骨架阶段以内存列表 + DataStore 为主）。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lmc_settings")

data class LmcSettings(
    // ---- JVM 层 ----
    val heapSizeMb: Int = 3072,
    // 默认 ParallelGC：ZGC 的 NUMA 探测调用 get_mempolicy 被 Android 应用
    // seccomp 白名单拒绝（SIGSYS），需要打补丁的 JRE 才可用（见 docs/BUILD.md）
    val gcId: Int = 1,            // 0=ZGC 1=ParallelGC
    val appCds: Boolean = true,
    val graalNativeImage: Boolean = false,

    // ---- 渲染层 ----
    val renderModeId: Int = 0,    // 见 RenderMode
    val vulkanPipelineCache: Boolean = true,
    val targetFps: Int = 120,

    // ---- 调度层 ----
    val pinMainThread: Boolean = true,
    val thermalGuardThresholdC: Float = 78f,
    val thermalHorizonSec: Float = 20f,

    // ---- 悬浮控制层 ----
    val controlOpacity: Float = 0.6f,
    val controlVisible: Boolean = true,

    // ---- 当前选择 ----
    val selectedVersionId: String = "1.21.4",
    val selectedAccountId: String = "offline:Player",

    // ---- 高级 ----
    /** 游戏主类（默认 vanilla 启动器主类；测试/模组化场景可自定义） */
    val mainClass: String = "net.minecraft.client.main.Main",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val heapSizeMb = intPreferencesKey("heap_size_mb")
        val gcId = intPreferencesKey("gc_id")
        val appCds = booleanPreferencesKey("app_cds")
        val graalNative = booleanPreferencesKey("graal_native")
        val renderModeId = intPreferencesKey("render_mode_id")
        val pipelineCache = booleanPreferencesKey("vulkan_pipeline_cache")
        val targetFps = intPreferencesKey("target_fps")
        val pinMainThread = booleanPreferencesKey("pin_main_thread")
        val thermalThreshold = floatPreferencesKey("thermal_threshold")
        val thermalHorizon = floatPreferencesKey("thermal_horizon")
        val controlOpacity = floatPreferencesKey("control_opacity")
        val controlVisible = booleanPreferencesKey("control_visible")
        val selectedVersionId = androidx.datastore.preferences.core.stringPreferencesKey("selected_version_id")
        val selectedAccountId = androidx.datastore.preferences.core.stringPreferencesKey("selected_account_id")
        val mainClass = androidx.datastore.preferences.core.stringPreferencesKey("main_class")
    }

    val settings: Flow<LmcSettings> = context.dataStore.data.map { p ->
        LmcSettings(
            heapSizeMb = p[Keys.heapSizeMb] ?: 3072,
            gcId = p[Keys.gcId] ?: 0,
            appCds = p[Keys.appCds] ?: true,
            graalNativeImage = p[Keys.graalNative] ?: false,
            renderModeId = p[Keys.renderModeId] ?: 0,
            vulkanPipelineCache = p[Keys.pipelineCache] ?: true,
            targetFps = p[Keys.targetFps] ?: 120,
            pinMainThread = p[Keys.pinMainThread] ?: true,
            thermalGuardThresholdC = p[Keys.thermalThreshold] ?: 78f,
            thermalHorizonSec = p[Keys.thermalHorizon] ?: 20f,
            controlOpacity = p[Keys.controlOpacity] ?: 0.6f,
            controlVisible = p[Keys.controlVisible] ?: true,
            selectedVersionId = p[Keys.selectedVersionId] ?: "1.21.4",
            selectedAccountId = p[Keys.selectedAccountId] ?: "offline:Player",
            mainClass = p[Keys.mainClass] ?: "net.minecraft.client.main.Main",
        )
    }

    suspend fun update(transform: (LmcSettings) -> LmcSettings) {
        context.dataStore.edit { prefs ->
            val current = LmcSettings(
                heapSizeMb = prefs[Keys.heapSizeMb] ?: 3072,
                gcId = prefs[Keys.gcId] ?: 0,
                appCds = prefs[Keys.appCds] ?: true,
                graalNativeImage = prefs[Keys.graalNative] ?: false,
                renderModeId = prefs[Keys.renderModeId] ?: 0,
                vulkanPipelineCache = prefs[Keys.pipelineCache] ?: true,
                targetFps = prefs[Keys.targetFps] ?: 120,
                pinMainThread = prefs[Keys.pinMainThread] ?: true,
                thermalGuardThresholdC = prefs[Keys.thermalThreshold] ?: 78f,
                thermalHorizonSec = prefs[Keys.thermalHorizon] ?: 20f,
                controlOpacity = prefs[Keys.controlOpacity] ?: 0.6f,
                controlVisible = prefs[Keys.controlVisible] ?: true,
                selectedVersionId = prefs[Keys.selectedVersionId] ?: "1.21.4",
                selectedAccountId = prefs[Keys.selectedAccountId] ?: "offline:Player",
                mainClass = prefs[Keys.mainClass] ?: "net.minecraft.client.main.Main",
            )
            val next = transform(current)
            prefs[Keys.heapSizeMb] = next.heapSizeMb
            prefs[Keys.gcId] = next.gcId
            prefs[Keys.appCds] = next.appCds
            prefs[Keys.graalNative] = next.graalNativeImage
            prefs[Keys.renderModeId] = next.renderModeId
            prefs[Keys.pipelineCache] = next.vulkanPipelineCache
            prefs[Keys.targetFps] = next.targetFps
            prefs[Keys.pinMainThread] = next.pinMainThread
            prefs[Keys.thermalThreshold] = next.thermalGuardThresholdC
            prefs[Keys.thermalHorizon] = next.thermalHorizonSec
            prefs[Keys.controlOpacity] = next.controlOpacity
            prefs[Keys.controlVisible] = next.controlVisible
            prefs[Keys.selectedVersionId] = next.selectedVersionId
            prefs[Keys.selectedAccountId] = next.selectedAccountId
            prefs[Keys.mainClass] = next.mainClass
        }
    }
}
