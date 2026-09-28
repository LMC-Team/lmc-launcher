package com.lemwoodmc.launcher.viewmodel

import android.app.Application
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lemwoodmc.launcher.bridge.GameLaunchConfig
import com.lemwoodmc.launcher.bridge.GcType
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
 * 职责：
 *  - 组装 [GameLaunchConfig]（来自设置 DataStore）并触发 native 侧启动流程；
 *  - 游戏运行期间，控制层事件（按键 / 指针）经 [sendKey]/[sendPointer] 直达 native 输入中枢；
 *  - 订阅 native 温度预警回调，联动降渲染分辨率（性能保护）。
 *
 * 零开销约定：游戏运行期间本 ViewModel 不持有任何 UI 重绘逻辑，
 * 悬浮控制层可通过 [controlVisible] 整体移出组合，Compose 渲染树为空。
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

    /** ART 侧已成功预加载的 natives（避免重复 dlopen 报错干扰判断） */
    private val loadedNatives = mutableSetOf<String>()

    /** 温度回调取消句柄 */
    private var thermalHandle: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // 启动流程
    // ------------------------------------------------------------------

    /**
     * SurfaceView 路径（Compose AndroidView 内嵌）：surface 就绪后调用。
     * 完整启动链条：
     *   1. nativeApplyRendererEnv —— 布置 Zink/Turnip/gl4es 环境变量（必须在 JVM 创建前）
     *   2. nativeInitAudio        —— AAudio MMAP 探测 + OpenAL-Soft 配置生成
     *   3. nativeSetOomAdj        —— 请求 lowmemorykiller 保护
     *   4. nativeBindGameThreadToBigCores —— 游戏主线程绑大核
     *   5. nativeStartThermalMonitor —— 温度监控与降频预测
     *   6. nativeCreateJvm        —— dlopen libjvm.so + JNI_CreateJavaVM + 调用 main（阻塞至退出）
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

            NativeBridge.nativeApplyRendererEnv(cfg.renderMode.id, filesDir.absolutePath)
            NativeBridge.nativeInitAudio()
            NativeBridge.nativeSetOomAdj(-900) // 尽量低的 oom_score_adj，失败会被忽略
            NativeBridge.nativeStartThermalMonitor(cfg.thermalThresholdC, cfg.thermalHorizonSec)
            registerThermal()

            if (cfg.pinMainThread) {
                NativeBridge.nativeBindGameThreadToBigCores()
            }
            NativeBridge.nativeAttachJavaSurface(surface, width, height)

            // ---- ART 侧预加载 natives（Pojav 生态关键契约）----
            // libpojavexec 等的 JNI_OnLoad 会 RegisterNatives 到 org.lwjgl.glfw.CallbackBridge，
            // 其方法集与 HotSpot 类路径上的声明存在差异；若留到 HotSpot 首次
            // loadLibrary 时执行，NoSuchMethodError 无法被 GLFW <clinit> 的
            // catch(UnsatisfiedLinkError) 捕获而逃逸。必须在 ART 环境（本进程
            // 的默认运行时）先完成加载与注册，HotSpot 随后复用已加载的 DSO。
            val nativesDir = File(filesDir, "versions/${cfg.versionId}/natives")
            // natives 预加载走 JNI（nativePreloadGameNatives）：与 libjvm 同
            // linker namespace，HotSpot 侧 LWJGL 的 dlopen 才能按 SONAME 复用
            // （ART System.load 的库对 HotSpot namespace 不可见——真机踩坑）。
            // 只处理 pojavexec 链；LWJGL/OpenAL natives 由 HotSpot 侧按
            // org.lwjgl.librarypath 自行加载（版本校验在 java 层）。
            NativeBridge.nativePreloadGameNatives(nativesDir.absolutePath)

            // 把游戏 Surface 注入 libpojavexec（FCL 版 GLFW 窗口后端）：
            // CallbackBridge.setupBridgeWindow(Object) → native 侧
            // ANativeWindow_fromSurface 保存。不注入则 glfwCreateWindow 后
            // ANativeWindow_acquire(NULL) SIGSEGV（真机踩坑）。
            // 必须主线程 + 在 HotSpot 启动前（ART 环境 JIT 交互）。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                // Zalith 启动配方：环境变量（env_init 读取 POJAV_* 系列）
                setupZalithEnvironment(nativesDir, width, height)
                // Zalith 窗口注入：native 层 dlsym 直接调用 pojavexec 的
                // ZLBridge_setupBridgeWindow C 符号（绕过 ART 方法解析）
                runCatching { NativeBridge.nativeZalithSetupBridgeWindow(nativesDir.absolutePath, surface) }
                    .onFailure { Log.w("LMC", "窗口注入失败: ${it.message}") }
            }

            // 极致性能模式：跳过 JVM，直接运行 GraalVM Native Image 产物（预留路径）
            if (cfg.graalNativeImage && NativeBridge.nativeNativeImageAvailable()) {
                _state.value = _state.value.copy(phase = Phase.JVM_STARTING)
                val code = NativeBridge.nativeLaunchNativeImage(cfg.gameArgs.toTypedArray())
                _state.value = _state.value.copy(
                    phase = if (code == 0) Phase.EXITED else Phase.CRASHED,
                    exitCode = code,
                )
                return@launch
            }

            _state.value = _state.value.copy(phase = Phase.JVM_STARTING)
            val args = buildJvmArgs(cfg, filesDir, width, height)
            // MC 主类需要最小启动参数（gameDir/version/token/username）；
            // 完整 args（assets、Demangle 等）由后续版本导入流程补全。
            // Mio 包装器（FCL 生态）存在时：主类切为 mio.Wrapper，
            // 真实主类插入 args[0]（其内部 loadClass(args[0]) 后反射调 main）
            val baseArgs = if (cfg.gameArgs.isEmpty()) {
                listOf(
                    "--gameDir", mcHome.absolutePath,
                    "--version", cfg.versionId,
                    "--accessToken", "0",
                    "--username", "Player",
                )
            } else cfg.gameArgs
            val mioJar = File(filesDir, "versions/${cfg.versionId}/libs/mio/MioLaunchWrapper.jar")
            val (finalMainClass, finalArgs) = if (mioJar.isFile) {
                "mio.Wrapper" to (listOf(cfg.mainClass) + baseArgs)
            } else {
                cfg.mainClass to baseArgs
            }
            val code = NativeBridge.nativeCreateJvm(
                javaHome = File(filesDir, "runtime/jre21").absolutePath,
                jvmArgs = args.toTypedArray(),
                mainClass = finalMainClass,
                mainArgs = finalArgs.toTypedArray(),
            )
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
    // 输入注入（悬浮控制层 → native 输入中枢）
    // ------------------------------------------------------------------

    fun sendKey(keyCode: Int, pressed: Boolean) {
        NativeBridge.nativeInjectKey(keyCode, pressed)
    }

    /** action: 0=down 1=up 2=move；button: 0=左 1=右 2=中 */
    fun sendPointer(action: Int, x: Float, y: Float, button: Int = 0) {
        NativeBridge.nativeInjectPointer(action, x, y, button)
    }

    fun toggleControlLayer() { _controlVisible.value = !_controlVisible.value }
    fun setControlOpacity(v: Float) {
        _controlOpacity.value = v
        viewModelScope.launch { repo.update { it.copy(controlOpacity = v) } }
    }

    /** SurfaceView 销毁：通知 native 渲染桥释放 swapchain 与 ANativeWindow */
    fun onSurfaceDestroyed() {
        if (jvmStarted.get()) NativeBridge.nativeDetachSurface()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun buildConfig(): GameLaunchConfig {
        val s = repo.settings.first()
        // version.json（版本下载器写入）记录真实 mainClass，优先于设置值
        val versionJson = File(
            getApplication<Application>().filesDir,
            "versions/${s.selectedVersionId}/version.json"
        )
        val mainClass = if (versionJson.isFile) {
            runCatching {
                org.json.JSONObject(versionJson.readText()).optString("mainClass", s.mainClass)
            }.getOrDefault(s.mainClass)
        } else s.mainClass

        return GameLaunchConfig(
            heapSizeMb = s.heapSizeMb,
            renderMode = RenderMode.fromId(s.renderModeId),
            gcType = GcType.fromId(s.gcId),
            appCds = s.appCds,
            graalNativeImage = s.graalNativeImage,
            pinMainThread = s.pinMainThread,
            thermalThresholdC = s.thermalGuardThresholdC,
            thermalHorizonSec = s.thermalHorizonSec,
            versionId = s.selectedVersionId,
            mainClass = mainClass,
        )
    }

    /**
     * 生成 JVM 参数（与 C++ 侧 jvm_options.cpp 的默认值策略一致；
     * 这里在 Kotlin 侧拼装是为了让设置页实时预览命令行）。
     * width/height：Surface 实际尺寸（Zalith GLFW stub 的窗口属性需要）。
     */
    private fun buildJvmArgs(cfg: GameLaunchConfig, filesDir: File, width: Int, height: Int): List<String> = buildList {
        // 固定堆：Xms == Xmx，避免堆伸缩带来的抖动
        add("-Xms${cfg.heapSizeMb}M"); add("-Xmx${cfg.heapSizeMb}M")
        // GC 策略
        cfg.gcType.flag.split(" ").forEach { add(it) }
        // JIT 编译线程数匹配大核数（若用户未指定，由 C++ 侧按拓扑注入 CICompilerCount）
        cfg.jitThreads?.let {
            add("-XX:CICompilerCount=$it")
            add("-XX:-CICompilerCountPerCPU") // bool 型开关：关闭需用 - 前缀
        }
        // Termux JRE 的 C2 编译器在部分 OEM 内核上会生成崩溃代码（SIGSEGV in JIT cache），
        // 先限定 C1 编译；换 Pojav 补丁版 JRE 后可移除
        add("-XX:TieredStopAtLevel=1")
        // AppCDS：首次运行自动生成共享类存档，后续启动直接 mmap
        if (cfg.appCds) {
            add("-XX:SharedArchiveFile=${File(filesDir, "cache/app.jsa").absolutePath}")
            add("-XX:+AutoCreateSharedArchive")
            add("-Xshare:auto")
        }
        // ---- 类路径组装：client.jar + versions/<id>/libs/ 下全部 jar（递归）----
        // Minecraft 完整 classpath 还包含官方 libraries，由版本导入流程展开到 libs/
        // 注意：JNI_CreateJavaVM 不接受 java 启动器的 -cp 参数，
        // classpath 必须以 java.class.path 系统属性传入
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
        // 运行时路径：natives 目录（LWJGL-android 替换件）加入 java.library.path；
        // Kotlin 侧设置后，native 侧 jvm_options 的同名补充会自动跳过
        val versionDir = File(filesDir, "versions/${cfg.versionId}")
        val nativesDir = File(versionDir, "natives")
        add("-Djava.library.path=" + listOf(
            getApplication<Application>().applicationInfo.nativeLibraryDir,
            nativesDir.absolutePath,
            File(filesDir, "minecraft").absolutePath,
            "/system/lib64",
        ).joinToString(":"))
        // LWJGL 3 优先读自己的路径属性（java.library.path 不一定被其采用）
        add("-Dorg.lwjgl.librarypath=${nativesDir.absolutePath}")
        // Zalith GLFW stub 的窗口尺寸属性（缺省 1280x720，与 Surface 实际尺寸不符会黑屏）
        add("-Dglfwstub.windowWidth=${width}")
        add("-Dglfwstub.windowHeight=${height}")
        add("-Dorg.lwjgl.util.Debug=true")
        add("-Dorg.lwjgl.util.DebugLoader=true")
        add("-Dorg.lwjgl.util.debug=true") // 诊断：LWJGL 库加载路径输出
        add("-Dorg.lwjgl.opengl.libname=libGL.so.1") // Zink 路径由 Mesa 提供 libGL
        add("-Dos.name=Linux")
        add("-Dminecraft.jar=${File(versionDir, "client.jar").absolutePath}")
        // ---- caciocavallo AWT 桥（Zalith/FCL 启动配方，来源 LaunchArgs.kt）----
        // MC 的窗口是 AWT 桥虚拟窗口，实际渲染走 launcher 注入的 Surface
        add("-Djava.awt.headless=false")
        add("-Dcacio.managed.screensize=${width}x${height}")
        add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
        add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
        add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
        add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
        add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
        // cacio agent（JRE21 用 cacio17 版本）
        val cacioAgent = File(versionDir, "libs/cacio/cacio-agent.jar")
        if (cacioAgent.isFile) add("-javaagent:${cacioAgent.absolutePath}")
        // cacio 全部 jar 上 bootclasspath（JDK9+ 用 -Xbootclasspath/a）
        val cacioDir = File(versionDir, "libs/cacio")
        if (cacioDir.isDirectory) {
            val bootCp = cacioDir.listFiles { f -> f.name.endsWith(".jar") }
                ?.joinToString(":") { it.absolutePath } ?: ""
            if (bootCp.isNotEmpty()) add("-Xbootclasspath/a:$bootCp")
        }
        // Mio wrapper 检测（FCL 生态）：主类切为 mio.Wrapper
        val mioJar = File(versionDir, "libs/mio/MioLaunchWrapper.jar")
        if (mioJar.isFile) add("-Dmio.wrapper=${mioJar.absolutePath}")
    }

    private fun registerThermal() {
        thermalHandle = NativeBridge.addThermalListener { predictedC, _, severity ->
            _state.value = _state.value.copy(thermalPredictedC = predictedC, thermalSeverity = severity)
            // severity >= 1 时由渲染桥自动降低分辨率缩放（C++ 侧同步置位）
        }
    }

    override fun onCleared() {
        thermalHandle?.invoke()
        if (jvmStarted.get()) NativeBridge.nativeDestroyJvm()
        super.onCleared()
    }

    /** 供 Compose 调试预览：后台线程取一次设置快照 */
    suspend fun currentSettingsForPreview() = withContext(Dispatchers.Default) { repo.settings.first() }

    /**
     * Zalith 启动配方环境变量（照抄 Zalith JREUtils.setJavaEnv/setRendererEnv）：
     * libpojavexec 的 env_init 读取 POJAV_* 系列决定渲染后端与窗口行为。
     */
    private fun setupZalithEnvironment(nativesDir: File, width: Int, height: Int) {
        val filesDir = getApplication<Application>().filesDir
        val jreHome = File(filesDir, "runtime/jre21").absolutePath
        val e = com.lemwoodmc.launcher.bridge.LmcPojavBridgeHelper.EnvPutter()
        e.put("POJAV_NATIVEDIR", getApplication<Application>().applicationInfo.nativeLibraryDir)
        e.put("DRIVER_PATH", nativesDir.absolutePath)
        e.put("JAVA_HOME", jreHome)
        e.put("HOME", File(filesDir, "minecraft").absolutePath)
        e.put("TMPDIR", filesDir.absolutePath.let { File(filesDir, "../cache").canonicalPath })
        e.put("LD_LIBRARY_PATH", "$nativesDir:${File(jreHome, "lib/server").absolutePath}:$jreHome/lib")
        e.put("AWTSTUB_WIDTH", width.toString())
        e.put("AWTSTUB_HEIGHT", height.toString())
        // 渲染后端：gl4es（LIBGL_ES=2 家族，gl4es 已部署 natives/）
        e.put("POJAV_RENDERER", "opengles2")
        e.put("LIBGL_ES", "2")
        e.put("LIBGL_MIPMAP", "3")
        e.put("LIBGL_NOERROR", "1")
        e.put("LIBGL_NOINTOVLHACK", "1")
        e.put("LIBGL_NORMALIZE", "1")
        e.commit()
    }
}
