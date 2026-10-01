package com.lemwoodmc.launcher.viewmodel

import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import com.lemwoodmc.launcher.bridge.GameLaunchConfig
import com.lemwoodmc.launcher.bridge.GcType
import com.lemwoodmc.launcher.bridge.LmcPojavBridgeHelper
import com.lemwoodmc.launcher.bridge.NativeBridge
import com.lemwoodmc.launcher.bridge.RenderMode
import com.lemwoodmc.launcher.data.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏运行期 ViewModel：Compose 与 native 后端的核心状态桥。
 *
 * 启动序列全面对齐 Zalith 2（VMActivity → GameHandler → Launcher 三段式）：
 *  1. Surface 就绪（主线程）：
 *     ZLBridge.setupBridgeWindow(surface) —— ANativeWindow 注入 pojavexec
 *     CallbackBridge.sendUpdateWindowSize —— 窗口尺寸同步进事件流
 *  2. 后台协程（Launcher.launchJvm 等价序列）：
 *     ZLBridge.setLdLibraryPath → setenv(POJAV_*) → dlopen JRE 链 →
 *     dlopen libopenal → 组 JVM 参数 → setupExitMethod + initializeGameExitHook +
 *     chdir → VMLauncher.launchJVM
 *
 * 组件契约：dex 桥（CallbackBridge/ZLBridge/ZLNativeInvoker/LoggerBridge）+
 * libpojavexec(_awt)/exithook（APK jniLibs）+ LWJGL jar（HotSpot classpath，
 * 3.3.6-snapshot）+ natives/ 全套（gl4es/Mesa/LWJGL natives，Zalith 2 同源）。
 * 全部组件同源自 ZalithLauncher 2 仓库，不再有跨 APK 配对问题。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { IDLE, PREPARING, JVM_STARTING, RENDERING, RUNNING, EXITED, CRASHED }

    data class GameState(
        val phase: Phase = Phase.IDLE,
        val exitCode: Int = 0,
        val logTail: String = "",
        /** native 温度预警的最新值，控制层角标展示 */
        val thermalPredictedC: Float = 0f,
        val thermalSeverity: Int = -1,
    )

    private val repo = SettingsRepository(app)
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    /** 悬浮控制层可见性（游戏内可整体隐藏 → Compose 层零开销） */
    private val _controlVisible = MutableStateFlow(true)
    val controlVisible: StateFlow<Boolean> = _controlVisible.asStateFlow()

    /** 悬浮控制层全局不透明度 */
    private val _controlOpacity = MutableStateFlow(0.6f)
    val controlOpacity: StateFlow<Float> = _controlOpacity.asStateFlow()

    /** 保证 JVM 只启动一次 */
    private val jvmStarted = AtomicBoolean(false)

    /** 温度回调取消句柄 */
    private var thermalHandle: (() -> Unit)? = null

    init {
        // dex 桥上下文注入（剪贴板等服务获取）
        LmcPojavBridgeHelper.injectAppContext(app)
    }

    // ------------------------------------------------------------------
    // 启动流程
    // ------------------------------------------------------------------

    /**
     * SurfaceView 路径（Compose AndroidView 内嵌）：surface 就绪后调用。
     *
     * lmc 特有优化（在 Zalith 序列之外叠加）：
     *   nativeInitAudio —— AAudio MMAP 探测 + OpenAL-Soft 配置生成
     *   nativeSetOomAdj —— lowmemorykiller 保护
     *   nativeBindGameThreadToBigCores —— 游戏主线程绑大核
     *   nativeStartThermalMonitor —— 温度监控与降频预测
     */
    fun launchOnSurface(surface: Surface, width: Int, height: Int) = viewModelScope.launch(Dispatchers.Default) {
        if (!jvmStarted.compareAndSet(false, true)) return@launch
        runCatching {
            _state.value = _state.value.copy(phase = Phase.PREPARING)
            val cfg = buildConfig()
            val filesDir = getApplication<Application>().filesDir
            val mcHome = File(filesDir, "minecraft")
            // log4j 的 RollingRandomAccessFile 需要 gameDir/logs/ 已存在（不会自建父目录）
            File(mcHome, "logs").mkdirs()

            // ---- lmc 性能层（Zalith 序列之外的叠加优化）----
            NativeBridge.nativeApplyRendererEnv(cfg.renderMode.id, filesDir.absolutePath)
            NativeBridge.nativeInitAudio()
            NativeBridge.nativeSetOomAdj(-900)
            NativeBridge.nativeStartThermalMonitor(cfg.thermalThresholdC, cfg.thermalHorizonSec)
            registerThermal()
            if (cfg.pinMainThread) {
                NativeBridge.nativeBindGameThreadToBigCores()
            }

            // ---- 窗口注入（主线程，HotSpot 启动前）----
            // 对齐 Zalith VMActivity.surfaceCreated → GameHandler.execute 开头：
            // setupBridgeWindow 先于一切 JVM/游戏线程动作。
            // ZLBridge 首次访问触发其静态块：System.loadLibrary(exithook→pojavexec→awt)，
            // 各 JNI_OnLoad 在 ART 侧完成 RegisterNatives（dex 契约生效）。
            withContext(Dispatchers.Main) {
                LmcPojavBridgeHelper.setupBridgeWindow(surface, width, height)
                Log.i("LMC", "Zalith 桥窗口注入完成（${width}x${height}）")
            }

            // ---- 游戏首帧监听 ----
            // 暂不注册 GraphicOutputListener：onGraphicOutput 回调在 HotSpot 渲染线程
            // 经 JNI 调入 dex，若回调路径抛异常，pojavexec calculateFPS 的
            // DetachCurrentThread 会因 pending exception 被 CheckJNI abort
            // （真机 2026-10-01：pojavSwapBuffers 首帧即崩）。RUNNING 状态改由
            // 日志轮询驱动（GameScreen 侧）。
            // LmcPojavBridgeHelper.setGraphicOutputListener { _state.value = ... }

            _state.value = _state.value.copy(phase = Phase.JVM_STARTING)

            // ---- JVM 日志重定向（对齐 Zalith VMActivity：pojavexec logger
            //      接住 HotSpot 的 stdout/stderr → 文件 + LoggerBridge.append）----
            val gameLogFile = File(mcHome, "logs/latest_game.log")
            gameLogFile.parentFile?.mkdirs()
            if (!gameLogFile.exists()) gameLogFile.createNewFile()
            runCatching {
                com.movtery.zalithlauncher.bridge.LoggerBridge.start(gameLogFile.absolutePath)
            }.onFailure { Log.w("LMC", "LoggerBridge.start 失败: ${it.message}") }

            // ---- Zalith Launcher.launchJvm 等价序列（后台线程）----
            val nativesDir = File(filesDir, "versions/${cfg.versionId}/natives")
            val jreHome = File(filesDir, "runtime/jre21").absolutePath
            val ctx = getApplication<Application>()

            // ---- 渲染器插件扫描（MobileGlues 等，FCL/Zalith 协议）----
            // 插件优先：提供 POJAV_RENDERER/env/GL 库名；无插件时回落内置
            // Freedreno/Turnip 配方。插件 so 目录必须进库搜索路径（egl_loader
            // 按名字 dlopen POJAVEXEC_EGL）。
            val rendererPlugin = runCatching {
                com.lemwoodmc.launcher.game.RendererPluginLoader.pick(ctx)
            }.getOrNull()
            if (rendererPlugin != null) {
                Log.i("LMC", "渲染器插件: ${rendererPlugin.packageName} id=${rendererPlugin.rendererId} dir=${rendererPlugin.nativeLibraryDir}")
            } else {
                Log.i("LMC", "未发现渲染器插件，使用内置 Freedreno/Turnip")
            }

            // 1) HotSpot 侧库搜索路径（native 侧记录，供 dlopen/解析）
            val runtimeLibPath = buildRuntimeLibraryPath(nativesDir, jreHome, rendererPlugin)
            LmcPojavBridgeHelper.zalithSetLdLibraryPath(runtimeLibPath)

            // 2) 环境变量（对齐 Zalith setJavaEnv + 渲染配方）
            setupEnvironment(nativesDir, jreHome, width, height, rendererPlugin)

            // 3) dlopen JRE 库链 + 引擎库（pojavexec 的 dlopen，namespace 正确）
            dlopenJavaRuntime(jreHome)
            dlopenEngine(nativesDir)
            rendererPlugin?.let { plugin ->
                plugin.dlopen.forEach { LmcPojavBridgeHelper.zalithDlopen("${plugin.nativeLibraryDir}/$it") }
            }

            // 4) MC options.txt 预写（对齐 Zalith MCOptions.setup）
            writeMcOptions(mcHome, width, height, cfg)

            // 5) JVM 参数
            val args = buildJvmArgs(cfg, filesDir, nativesDir, width, height, rendererPlugin)

            // 6) MC 主类与启动参数
            // --assetsDir/--assetIndex 必传:否则 MC 找不到资源对象,
            // 全部声音 Missing(真机 2026-10-01:assets/ 部署后仍静音即此因)
            val baseArgs = if (cfg.gameArgs.isEmpty()) {
                listOf(
                    "--gameDir", mcHome.absolutePath,
                    "--version", cfg.versionId,
                    "--accessToken", "0",
                    "--username", "Player",
                    "--assetsDir", File(mcHome, "assets").absolutePath,
                    "--assetIndex", "19", // assets/indexes/19.json(1.21.4)
                )
            } else cfg.gameArgs
            val versionJson = File(filesDir, "versions/${cfg.versionId}/version.json")
            val mainClass = if (versionJson.isFile) {
                runCatching {
                    org.json.JSONObject(versionJson.readText()).optString("mainClass", cfg.mainClass)
                }.getOrDefault(cfg.mainClass)
            } else cfg.mainClass

            // 7) 退出钩子 + CWD（Zalith launchJavaVM 的三连）
            LmcPojavBridgeHelper.zalithSetupExitMethod(ctx)
            LmcPojavBridgeHelper.zalithInitializeGameExitHook()
            LmcPojavBridgeHelper.zalithChdir(mcHome.absolutePath)

            // 8) 启动（阻塞至 JVM 退出）
            // 路径说明：JVM 必须跑在纯 native 线程（pthread）上——pojavexec 的
            // onGraphicOutput 首帧回调（SwapBuffers → Attach→Call→Detach）对
            // "带活跃 JNI 栈的 ART 协程线程" 是非法 detach（ART abort：
            // "detach while still running code"，debug/release 均崩，真机 2026-10-01）。
            // JLI_Launch（VMLauncher）路径虽也新建线程，但其 Boardwalk 时代的
            // sigaction 重置与本机 Termux JRE 组合静默失败，故由 lmc_core
            // 自管线程 + JNI_CreateJavaVM（该路径 9-28 真机验证过）。
            Log.i("LMC", "nativeCreateJvmOnNewThread 即将启动, jvmArgs=${args.size} 项, mainClass=$mainClass")
            NativeBridge.nativeCreateJvmOnNewThread(
                jreHome, args.toTypedArray(), mainClass, baseArgs.toTypedArray()
            )
            val code = NativeBridge.nativeWaitJvmExit()
            Log.i("LMC", "JVM 退出 code=$code")
            NativeBridge.nativeStopThermalMonitor()
            _state.value = _state.value.copy(
                phase = if (code == 0) Phase.EXITED else Phase.CRASHED,
                exitCode = code,
            )
        }.onFailure {
            _state.value = _state.value.copy(phase = Phase.CRASHED, logTail = it.stackTraceToString())
        }
    }

    // ------------------------------------------------------------------
    // 输入注入（悬浮控制层 → CallbackBridge 事件流 → pojavexec 输入桥）
    // ------------------------------------------------------------------

    /**
     * 虚拟按键 → 事件流。参数为 GLFW 键码（控制层 ControlButton 的约定：
     * 87=W、83=S、65=A、68=D、32=空格、340=左Shift、69=E、81=Q）。
     * pressed 区分按下/抬起：方向键长按 = 持续移动（MC 以 down/up 状态机维持）。
     */
    fun sendKey(glfwKeyCode: Int, pressed: Boolean) {
        LmcPojavBridgeHelper.sendGlfwKeyEvent(glfwKeyCode, pressed)
    }

    /** action: 0=down 1=up 2=move；button: 0=左 1=右 2=中 */
    fun sendPointer(action: Int, x: Float, y: Float, button: Int = 0) {
        LmcPojavBridgeHelper.sendPointerEvent(action, x, y, button)
    }

    fun sendScroll(xOffset: Float, yOffset: Float) {
        LmcPojavBridgeHelper.sendScrollEvent(xOffset, yOffset)
    }

    /**
     * SurfaceView 触摸 → 鼠标（单指=左键）：
     * pressed=true 按下 / false 抬起 / null 仅移动。
     */
    /** 虚拟鼠标光标的最新位置(px,游戏 Surface 坐标系),供 Compose 层绘制可见光标 */
    val cursorPosition = mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)

    fun sendTouch(x: Float, y: Float, pressed: Boolean?) {
        cursorPosition.value = androidx.compose.ui.geometry.Offset(x, y)
        LmcPojavBridgeHelper.sendCursorPos(x, y)
        if (pressed != null) {
            LmcPojavBridgeHelper.sendMouseButtonEvent(0, pressed)
        }
    }

    fun toggleControlLayer() { _controlVisible.value = !_controlVisible.value }

    /** 显式设置控制层可见性（悬浮球菜单的开关行用） */
    fun setControlVisible(v: Boolean) { _controlVisible.value = v }
    fun setControlOpacity(v: Float) {
        _controlOpacity.value = v
        viewModelScope.launch { repo.update { it.copy(controlOpacity = v) } }
    }

    /** SurfaceView 销毁：通知 native 渲染桥释放 swapchain 与 ANativeWindow */
    fun onSurfaceDestroyed() {
        if (jvmStarted.get()) NativeBridge.nativeDetachSurface()
    }

    /** JVM 是否已启动（Surface 尺寸变化时区分首启与重注入） */
    val isJvmStarted: Boolean get() = jvmStarted.get()

    /**
     * Surface 尺寸变化（旋转/重布局）：重新注入桥窗口并同步尺寸事件流。
     * MC 侧 GLFW 收到 EVENT_TYPE_WINDOW_SIZE 后触发 internalWindowSizeChanged，
     * 游戏内窗口随之调整。必须在主线程操作 Surface。
     */
    fun onSurfaceSizeChanged(surface: Surface, width: Int, height: Int) {
        if (!jvmStarted.get()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                LmcPojavBridgeHelper.setupBridgeWindow(surface, width, height)
                Log.i("LMC", "Surface 尺寸变化重注入: ${width}x$height")
            }.onFailure { Log.w("LMC", "重注入失败: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // Zalith 启动序列的各环节
    // ------------------------------------------------------------------

    /** HotSpot/引擎 so 的库搜索路径（对齐 Zalith getRuntimeLibraryPath 简化版；
     *  渲染器插件的 so 目录必须前置——egl_loader 按名字 dlopen 插件的 EGL 库） */
    private fun buildRuntimeLibraryPath(
        nativesDir: File,
        jreHome: String,
        plugin: com.lemwoodmc.launcher.game.RendererPluginLoader.Plugin?,
    ): String = listOfNotNull(
        plugin?.nativeLibraryDir,
        File(jreHome, "lib").absolutePath,
        File(jreHome, "lib/server").absolutePath,
        nativesDir.absolutePath,
        getApplication<Application>().applicationInfo.nativeLibraryDir,
        "/system/lib64",
        "/vendor/lib64",
    ).joinToString(":")

    /**
     * 环境变量（对齐 Zalith setJavaEnv + 渲染配方）。
     * libpojavexec 的 env_init 读取 POJAV_* 系列决定渲染后端与窗口行为。
     */
    private fun setupEnvironment(
        nativesDir: File,
        jreHome: String,
        width: Int,
        height: Int,
        plugin: com.lemwoodmc.launcher.game.RendererPluginLoader.Plugin?,
    ) {
        val filesDir = getApplication<Application>().filesDir
        val e = LmcPojavBridgeHelper.EnvPutter()
        // 必须指向设备 natives 目录（files/versions/<id>/natives）：osm_bridge 用
        // POJAV_NATIVEDIR + LIB_MESA_NAME 拼 libOSMesa 路径；Zalith 的同款配置
        // 也指向其 files 下的 natives（不是 APK nativeLibraryDir，真机踩坑）
        e.put("POJAV_NATIVEDIR", nativesDir.absolutePath)
        e.put("DRIVER_PATH", nativesDir.absolutePath)
        e.put("JAVA_HOME", jreHome)
        e.put("HOME", File(filesDir, "minecraft").absolutePath)
        e.put("TMPDIR", File(filesDir, "../cache").canonicalPath)
        e.put("LD_LIBRARY_PATH", buildRuntimeLibraryPath(nativesDir, jreHome, plugin))
        e.put("PATH", File(jreHome, "bin").absolutePath + ":" + (System.getenv("PATH") ?: ""))
        e.put("AWTSTUB_WIDTH", width.toString())
        e.put("AWTSTUB_HEIGHT", height.toString())

        if (plugin != null) {
            // ---- 渲染器插件配方（MobileGlues 等，FCL/Zalith 协议）----
            e.put("POJAV_RENDERER", plugin.rendererId)
            for ((k, v) in plugin.env) {
                // EGL/GL 库名相对插件的 nativeLibraryDir，需展开为绝对路径
                //（egl_loader/osm_bridge 的 dlopen 按库搜索路径查找，插件目录
                // 已在 LD_LIBRARY_PATH 前列，但绝对路径最稳）
                e.put(k, "${plugin.nativeLibraryDir}/$v")
            }
            // 插件配方自带的 LIBGL_* 系列已随 envMap 注入；gl4es 兼容参数兜底
            e.put("LIBGL_MIPMAP", "3")
            e.put("LIBGL_NOERROR", "1")
        } else {
            // ---- 内置 Freedreno/Turnip 配方（无插件时的回落）----
            e.put("POJAV_RENDERER", "gallium_freedreno")
            e.put("LIB_MESA_NAME", "libOSMesa_8.so")
            e.put("LIBGL_ES", "2")
            e.put("LIBGL_MIPMAP", "3")
            e.put("LIBGL_NOERROR", "1")
            e.put("LIBGL_NOINTOVLHACK", "1")
            e.put("LIBGL_NORMALIZE", "1")
        }
        // 大核亲和（pojavexec 内建支持，与 lmc 的 sched 层互补）
        e.put("POJAV_BIG_CORE_AFFINITY", "1")
        // OpenAL 诊断：详细日志进 stderr（jvm_stderr.log）；配置文件直指设备路径
        e.put("ALSOFT_LOGLEVEL", "3")
        e.put(
            "ALSOFT_CONF",
            File(getApplication<Application>().filesDir, "openalsoft/alsoft.conf").absolutePath
        )
    }

    /**
     * dlopen JRE 库链（对齐 Zalith dlopenJavaRuntime）：
     * libjli → libjvm → freetype/verify/java/net/nio/awt/awt_headless/fontmanager，
     * 最后递归 dlopen runtime 下全部 so（JDK 内部依赖自洽）。
     * 必须走 ZLBridge.dlopen（pojavexec 的 native dlopen），namespace 才正确。
     */
    private fun dlopenJavaRuntime(jreHome: String) {
        val javaLibDir = File(jreHome, "lib").absolutePath
        val jliDir = if (File(javaLibDir, "jli/libjli.so").isFile) "$javaLibDir/jli" else javaLibDir
        val jvmDir = if (File(javaLibDir, "server/libjvm.so").isFile) "$javaLibDir/server" else "$javaLibDir/client"

        dlopenLogged("jli", "$jliDir/libjli.so")
        dlopenLogged("jvm", "$jvmDir/libjvm.so")
        dlopenLogged("freetype", "$javaLibDir/libfreetype.so")
        dlopenLogged("verify", "$javaLibDir/libverify.so")
        dlopenLogged("java", "$javaLibDir/libjava.so")
        dlopenLogged("net", "$javaLibDir/libnet.so")
        dlopenLogged("nio", "$javaLibDir/libnio.so")
        dlopenLogged("awt", "$javaLibDir/libawt.so")
        dlopenLogged("awt_headless", "$javaLibDir/libawt_headless.so")
        dlopenLogged("fontmanager", "$javaLibDir/libfontmanager.so")

        // 递归补齐 runtime 下所有 so（对齐 Zalith locateLibs）。
        // 多轮收敛：依赖序不保证（如 libinstrument 依赖 libiconv），第一轮
        // 因依赖未加载而失败的，下一轮依赖就位后重试即可命中。
        var extra = 0
        val all = File(jreHome).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") }
            .toList()
        var pending = all
        repeat(3) {
            val next = mutableListOf<File>()
            for (f in pending) {
                if (!LmcPojavBridgeHelper.zalithDlopen(f.absolutePath)) next.add(f)
            }
            extra += pending.size - next.size
            if (next.isEmpty()) return@repeat
            pending = next
        }
        Log.i("LMC", "JRE dlopen 完成: 固定链 + 递归成功 $extra/${all.size}，仍失败 ${pending.size}: ${pending.take(3).joinToString { it.name }}")
    }

    /** 引擎库：OpenAL（对齐 Zalith dlopenEngine） */
    private fun dlopenEngine(nativesDir: File) {
        val openal = File(nativesDir, "libopenal.so")
        if (openal.isFile) {
            LmcPojavBridgeHelper.zalithDlopen(openal.absolutePath)
        }
    }

    /** dlopen 诊断版（逐项打日志，namespace/依赖问题一眼可见） */
    private fun dlopenLogged(tag: String, path: String) {
        val ok = LmcPojavBridgeHelper.zalithDlopen(path)
        if (!ok) Log.w("LMC", "dlopen 失败 [$tag] $path")
    }

    /**
     * MC options.txt 预写（对齐 Zalith MCOptions.setup / GameHandler.execute）。
     * overrideWidth/Height 决定 MC 内部窗口尺寸，必须与 Surface 一致。
     */
    private fun writeMcOptions(mcHome: File, width: Int, height: Int, cfg: GameLaunchConfig) {
        val optionsFile = File(mcHome, "options.txt")
        val existing = if (optionsFile.isFile) {
            optionsFile.readLines().associate {
                val idx = it.indexOf(':')
                if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else it to null
            }.toMutableMap()
        } else mutableMapOf()

        fun set(key: String, value: String) { existing[key] = value }
        set("fullscreen", "true")
        set("touchscreen", "false")
        set("options.narrator", "0")
        set("narrator", "0")
        set("overrideWidth", width.toString())
        set("overrideHeight", height.toString())
        // 渲染后端偏好（version.json 的 GraphicsApi 可覆盖，骨架阶段默认 GL）
        if (!existing.containsKey("preferredGraphicsBackend")) {
            set("preferredGraphicsBackend", "GL")
        }
        // 旧版本按键冲突规避（对齐 Zalith isLowerVer("1.13") 分支）
        val mcVer = runCatching {
            val vj = File(
                getApplication<Application>().filesDir,
                "versions/${cfg.versionId}/version.json"
            )
            org.json.JSONObject(vj.readText()).optString("id", "")
        }.getOrDefault("")
        val minor = mcVer.split('.').getOrNull(1)?.toIntOrNull()
        if (minor != null && minor < 13) {
            set("key_key.fullscreen", "0")
            set("key_key.streamStartStop", "0")
            set("key_key.streamPauseUnpause", "0")
        }

        optionsFile.bufferedWriter().use { w ->
            for ((k, v) in existing) w.write("$k:${v ?: ""}\n")
        }
    }

    // ------------------------------------------------------------------
    // 配置与 JVM 参数
    // ------------------------------------------------------------------

    private suspend fun buildConfig(): GameLaunchConfig {
        val s = repo.settings.first()
        val gc = GcType.fromId(s.gcId)
        // Termux 官方 JRE 的 ZGC 会被 seccomp 杀死（NUMA 探测 get_mempolicy → SIGSYS，
        // 旧断点 #8）；换 scripts/build_openjdk.sh 方案 B 的自构建 JRE 后才可放开
        val safeGc = if (gc == GcType.ZGC) {
            Log.w("LMC", "ZGC 需自编译 JRE（NUMA 补丁），本次回落 ParallelGC")
            GcType.PARALLEL
        } else gc
        return GameLaunchConfig(
            heapSizeMb = s.heapSizeMb,
            renderMode = RenderMode.fromId(s.renderModeId),
            gcType = safeGc,
            appCds = s.appCds,
            graalNativeImage = s.graalNativeImage,
            pinMainThread = s.pinMainThread,
            thermalThresholdC = s.thermalGuardThresholdC,
            thermalHorizonSec = s.thermalHorizonSec,
            versionId = s.selectedVersionId,
            mainClass = s.mainClass,
        )
    }

    /**
     * 生成 JVM 参数（对齐 Zalith getJavaArgs + progressFinalUserArgs + getCacioJavaArgs，
     * 保留 lmc 特性：AppCDS / GC 选择 / 绑核相关）。
     */
    private fun buildJvmArgs(
        cfg: GameLaunchConfig,
        filesDir: File,
        nativesDir: File,
        width: Int,
        height: Int,
        rendererPlugin: com.lemwoodmc.launcher.game.RendererPluginLoader.Plugin?,
    ): List<String> = buildList {
        // ---- Zalith overridable 系统属性 ----
        add("-Djava.home=${File(filesDir, "runtime/jre21").absolutePath}")
        add("-Djava.io.tmpdir=${File(filesDir, "../cache").canonicalPath}")
        add("-Djna.boot.library.path=${nativesDir.absolutePath}")
        add("-Duser.home=${File(filesDir, "minecraft").absolutePath}")
        add("-Dos.name=Linux")
        add("-Dos.version=Android-${android.os.Build.VERSION.RELEASE}")
        add("-Dpojav.path.minecraft=${File(filesDir, "minecraft").absolutePath}")
        add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
        // GLFW stub 窗口属性（initEgl=false：EGL 上下文由 egl_bridge 的桥接层管理，
        // 不由 GLFW stub 自建——Zalith 启动配方，缺失会导致 no current context）
        add("-Dglfwstub.windowWidth=$width")
        add("-Dglfwstub.windowHeight=$height")
        add("-Dglfwstub.initEgl=false")
        add("-Dlog4j2.formatMsgNoLookups=true")
        add("-Djava.rmi.server.useCodebaseOnly=true")
        add("-Dcom.sun.jndi.rmi.object.trustURLCodebase=false")
        add("-Dcom.sun.jndi.cosnaming.object.trustURLCodebase=false")
        add("-Dfml.earlyprogresswindow=false")
        add("-Dfml.ignoreInvalidMinecraftCertificates=true")
        add("-Dfml.ignorePatchDiscrepancies=true")
        add("-Dloader.disable_forked_guis=true")
        add("-Djdk.lang.Process.launchMechanism=FORK")
        add("-Dsodium.checks.issue2561=false")

        // ---- lmc 特性：GC / 堆 / AppCDS ----
        add("-Xms${cfg.heapSizeMb}M"); add("-Xmx${cfg.heapSizeMb}M")
        cfg.gcType.flag.split(" ").forEach { add(it) }
        cfg.jitThreads?.let {
            add("-XX:CICompilerCount=$it")
            add("-XX:-CICompilerCountPerCPU")
        }
        // JIT:完整分层编译(C1+C2)。9-28 曾因 ART 线程时代的 C2 崩溃疑虑
        // 加 -XX:TieredStopAtLevel=1,现 JVM 已迁至纯 native 线程,且
        // Zalith 对同一 Termux JRE 不做任何 JIT 限制 —— 该开关会让长期
        // 性能损失 30-50%(server tick 2-4s 的主因之一),已移除。
        add("-XX:ActiveProcessorCount=${java.lang.Runtime.getRuntime().availableProcessors()}")
        // javaagent 与 CDS 互斥。cacio/mio agent 均已移除（#38 轮），CDS 可用：
        // AppCDS 类存档显著加速类加载（首启自动创建 archive，次启生效）
        if (cfg.appCds) {
            add("-XX:SharedArchiveFile=${File(filesDir, "cache/app.jsa").absolutePath}")
            add("-XX:+AutoCreateSharedArchive")
            add("-Xshare:auto")
        }

        // ---- 类路径：client.jar + versions/<id>/libs/ 全部 jar（递归）----
        if (!cfg.gameArgs.contains("-cp") && !cfg.gameArgs.contains("-classpath")) {
            val versionDir = File(filesDir, "versions/${cfg.versionId}")
            val cpFiles = mutableListOf<File>()
            File(versionDir, "client.jar").takeIf { it.isFile }?.let { cpFiles.add(it) }
            File(versionDir, "libs").takeIf { it.isDirectory }?.walkTopDown()
                ?.filter { it.isFile && it.extension == "jar" }
                ?.forEach { cpFiles.add(it) }
            if (cpFiles.isNotEmpty()) {
                add("-Djava.class.path=" + cpFiles.joinToString(":") { it.absolutePath })
            }
        }

        // ---- natives 路径（LWJGL natives 由 HotSpot 按 librarypath 加载）----
        add("-Djava.library.path=" + listOf(
            nativesDir.absolutePath,
            getApplication<Application>().applicationInfo.nativeLibraryDir,
        ).joinToString(":"))
        add("-Dorg.lwjgl.librarypath=${nativesDir.absolutePath}")
        // LWJGL 组件库指名（对齐 Zalith progressFinalUserArgs）
        add("-Dorg.lwjgl.openal.libname=${File(nativesDir, "libopenal.so").absolutePath}")
        add("-Dorg.lwjgl.freetype.libname=${File(nativesDir, "libfreetype.so").absolutePath}")
        add("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared")
        add("-Dorg.lwjgl.system.allocator=system")
        // 渲染库指名：插件（MobileGlues 等）优先用其 glName；无插件回落
        // Freedreno/Turnip 的 libOSMesa_8.so。缺失时 MC 在 GL.<clinit> 找
        // libGL.so.1 直接 UnsatisfiedLinkError。
        val glLibName = rendererPlugin?.let { "${it.nativeLibraryDir}/${it.glName}" }
            ?: File(nativesDir, "libOSMesa_8.so").absolutePath
        add("-Dorg.lwjgl.opengl.libname=$glLibName")
        add("-Dorg.lwjgl.util.Debug=true")
        add("-Dorg.lwjgl.util.DebugLoader=true")
        add("-Dminecraft.jar=${File(filesDir, "versions/${cfg.versionId}/client.jar").absolutePath}")

        // ---- Mio patcher：暂不挂 javaagent ----
        // MioLibPatcher 依赖 pojavexec 的 pojav_environ(dalvikJNIEnvPtr_ANDROID)，
        // 该指针仅在 VMLauncher.launchJVM 路径赋值；我们走 lmc_core 的
        // JNI_CreateJavaVM 路径，agent 的 processJavaStart 会失败。
        // 待确认无 pojav_environ 依赖后再启用（2026-10-01 真机日志：MioPatcher
        // is running → processing of -javaagent failed）。
        // val mioPatcher = File(filesDir, "versions/${cfg.versionId}/libs/mio/mio-patcher.jar")
        // if (mioPatcher.isFile) add("-javaagent:${mioPatcher.absolutePath}")

        // ---- caciocavallo AWT 桥（MC 内 Swing/AWT 组件用，非主渲染路径）----
        add("-Djava.awt.headless=false")
        add("-Dcacio.managed.screensize=${width}x$height")
        add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
        add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
        add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
        add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
        add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
        // ---- cacio agent：暂不挂 javaagent（同 Mio：依赖 pojavexec 的 ART
        // 环境指针，nativeCreateJvm 路径下 premain 失败 → VM 初始化中止）。
        // cacio 类仍经 -Xbootclasspath/a 加载，MC 主渲染路径（GLFW 桥）不依赖 AWT。
        val cacioAgent = File(filesDir, "versions/${cfg.versionId}/libs/cacio/cacio-agent.jar")
        if (false && cacioAgent.isFile) add("-javaagent:${cacioAgent.absolutePath}")
        listOf(
            "--add-exports=java.desktop/java.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
        ).forEach { add(it) }
        val cacioDir = File(filesDir, "versions/${cfg.versionId}/libs/cacio")
        if (cacioDir.isDirectory) {
            val bootCp = cacioDir.listFiles { f -> f.name.endsWith(".jar") }
                ?.joinToString(":") { it.absolutePath } ?: ""
            if (bootCp.isNotEmpty()) add("-Xbootclasspath/a:$bootCp")
        }
    }

    private fun registerThermal() {
        thermalHandle = NativeBridge.addThermalListener { predictedC, _, severity ->
            _state.value = _state.value.copy(thermalPredictedC = predictedC, thermalSeverity = severity)
        }
    }

    override fun onCleared() {
        thermalHandle?.invoke()
        if (jvmStarted.get()) NativeBridge.nativeDestroyJvm()
        super.onCleared()
    }

    /** 供 Compose 调试预览：后台线程取一次设置快照 */
    suspend fun currentSettingsForPreview() = withContext(Dispatchers.Default) { repo.settings.first() }
}
