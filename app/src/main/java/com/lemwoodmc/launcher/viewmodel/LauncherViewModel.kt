package com.lemwoodmc.launcher.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lemwoodmc.launcher.bridge.NativeBridge
import com.lemwoodmc.launcher.data.LmcSettings
import com.lemwoodmc.launcher.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 启动器主页 ViewModel：设置状态 + CPU 拓扑展示 + 版本/账户列表。
 * Compose 与 native 后端之间的状态桥之一（非游戏运行期）。
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    private val _settings = MutableStateFlow(LmcSettings())
    val settings: StateFlow<LmcSettings> = _settings.asStateFlow()

    /** native 层解析出的 big.LITTLE 拓扑（设置页展示用） */
    private val _cpuTopology = MutableStateFlow("")
    val cpuTopology: StateFlow<String> = _cpuTopology.asStateFlow()

    /** 版本列表：真实扫描 <files>/versions/ 目录（目录名即版本 id，含 client.jar 视为已安装） */
    data class GameVersion(val id: String, val path: String, val installed: Boolean)

    /** 账户列表骨架：离线账户 + Microsoft OAuth（留接口） */
    data class GameAccount(val id: String, val name: String, val type: String)

    private val _versions = MutableStateFlow<List<GameVersion>>(emptyList())
    val versions: StateFlow<List<GameVersion>> = _versions.asStateFlow()

    private val _accounts = MutableStateFlow(
        listOf(GameAccount("offline:Player", "Player", "离线"))
    )
    val accounts: StateFlow<List<GameAccount>> = _accounts.asStateFlow()

    // ---- 远程版本（Mojang piston-meta）与下载状态 ----
    private val _remoteVersions = MutableStateFlow<List<com.lemwoodmc.launcher.data.MojangApi.RemoteVersion>>(emptyList())
    val remoteVersions: StateFlow<List<com.lemwoodmc.launcher.data.MojangApi.RemoteVersion>> = _remoteVersions.asStateFlow()

    private val _remoteLoading = MutableStateFlow(false)
    val remoteLoading: StateFlow<Boolean> = _remoteLoading.asStateFlow()

    private val _downloadProgress = MutableStateFlow<com.lemwoodmc.launcher.data.MojangApi.DownloadProgress?>(null)
    val downloadProgress: StateFlow<com.lemwoodmc.launcher.data.MojangApi.DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _downloadingVersion = MutableStateFlow<String?>(null)
    val downloadingVersion: StateFlow<String?> = _downloadingVersion.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    /** 拉取 Mojang 官方版本清单 */
    fun fetchRemoteVersions() {
        if (_remoteLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _remoteLoading.value = true
            runCatching { com.lemwoodmc.launcher.data.MojangApi.fetchManifest() }
                .onSuccess { _remoteVersions.value = it }
                .onFailure { _downloadError.value = "清单拉取失败: ${it.message}" }
            _remoteLoading.value = false
        }
    }

    /**
     * 下载官方版本（client.jar + 纯 Java libraries）到 files/versions/<id>/，
     * 完成后写入 version.json（记录真实 mainClass）并刷新本地列表。
     */
    fun downloadRemoteVersion(id: String) {
        val remote = _remoteVersions.value.firstOrNull { it.id == id } ?: return
        if (_downloadingVersion.value != null) return
        _downloadError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _downloadingVersion.value = id
            runCatching {
                val meta = com.lemwoodmc.launcher.data.MojangApi.fetchVersionMeta(remote.url)
                val dir = java.io.File(getApplication<Application>().filesDir, "versions/$id")
                com.lemwoodmc.launcher.data.MojangApi.downloadVersion(meta, dir) { p ->
                    _downloadProgress.value = p
                }
                // 记录真实 mainClass，启动时优先于设置值
                val metaJson = org.json.JSONObject().put("mainClass", meta.mainClass)
                java.io.File(dir, "version.json").writeText(metaJson.toString())
            }.onSuccess {
                refreshVersions()
                repo.update { it.copy(selectedVersionId = id) }
            }.onFailure {
                _downloadError.value = "下载失败: ${it.message}"
            }
            _downloadingVersion.value = null
            _downloadProgress.value = null
        }
    }

    init {
        // 收集设置流
        viewModelScope.launch {
            _settings.value = repo.settings.first()
            repo.settings.collect { _settings.value = it }
        }
        // 读取 CPU 拓扑（native 层已在 Application.onCreate 完成初始化）
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val json = NativeBridge.nativeDetectCpuTopology()
                _cpuTopology.value = prettify(json)
            }.onFailure { _cpuTopology.value = "拓扑读取失败: ${it.message}" }
        }
        refreshVersions()
    }

    /** 重新扫描版本目录（版本导入/删除后调用；UI 提供刷新按钮） */
    fun refreshVersions() {
        val dir = java.io.File(getApplication<Application>().filesDir, "versions")
        val list = dir.listFiles { f -> f.isDirectory }
            ?.map { d ->
                GameVersion(
                    id = d.name,
                    path = "versions/${d.name}",
                    installed = java.io.File(d, "client.jar").isFile,
                )
            }
            ?.sortedWith(compareByDescending< GameVersion> { it.installed }.thenBy { it.id })
            ?: emptyList()
        _versions.value = list
    }

    fun setHeapSize(mb: Int) = viewModelScope.launch { repo.update { it.copy(heapSizeMb = mb) } }
    fun setGc(id: Int) = viewModelScope.launch { repo.update { it.copy(gcId = id) } }
    fun setRenderMode(id: Int) = viewModelScope.launch { repo.update { it.copy(renderModeId = id) } }
    fun setAppCds(on: Boolean) = viewModelScope.launch { repo.update { it.copy(appCds = on) } }
    fun setGraalNative(on: Boolean) = viewModelScope.launch { repo.update { it.copy(graalNativeImage = on) } }
    fun setPipelineCache(on: Boolean) = viewModelScope.launch { repo.update { it.copy(vulkanPipelineCache = on) } }
    fun setPinMainThread(on: Boolean) = viewModelScope.launch { repo.update { it.copy(pinMainThread = on) } }
    fun setControlOpacity(v: Float) = viewModelScope.launch { repo.update { it.copy(controlOpacity = v) } }
    fun setControlVisible(on: Boolean) = viewModelScope.launch { repo.update { it.copy(controlVisible = on) } }
    fun setThermalThreshold(c: Float) = viewModelScope.launch { repo.update { it.copy(thermalGuardThresholdC = c) } }
    fun setMainClass(cls: String) = viewModelScope.launch {
        if (cls.isNotBlank()) repo.update { it.copy(mainClass = cls.trim()) }
    }
    fun selectVersion(id: String) = viewModelScope.launch { repo.update { it.copy(selectedVersionId = id) } }
    fun selectAccount(id: String) = viewModelScope.launch { repo.update { it.copy(selectedAccountId = id) } }

    /** 紧凑化展示（逗号后加空格），由 Compose Text 自动换行 */
    private fun prettify(json: String): String = json.replace(",", ", ")
}
