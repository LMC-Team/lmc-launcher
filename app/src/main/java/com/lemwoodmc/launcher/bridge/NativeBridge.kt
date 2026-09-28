package com.lemwoodmc.launcher.bridge

import android.view.Surface
import java.nio.ByteBuffer

/**
 * Kotlin ↔ C++ 核心后端 JNI 状态桥。
 *
 * 约定：
 *  - UI 层（Compose/ViewModel）只通过本对象与 native 后端通信，不直接接触 native 细节；
 *  - 所有 native 方法在 [Core] 单例上声明，JNI 侧以 RegisterNatives 之外的
 *    标准 JNI 命名（Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeXxx）解析；
 *  - native → Kotlin 回调统一走 [onThermalWarning] 等静态方法（@JvmStatic）。
 *
 * 线程模型：
 *  - JVM 创建 / 游戏主循环运行在调用方专用线程（GameViewModel 会切到独立线程）；
 *  - 输入注入、温度回调等可来自任意 native 线程，native 内部已做同步。
 */
object NativeBridge {

    /** 加载 C++ 核心后端。包含 JVM 加载器、渲染桥、调度器、输入/音频/内存模块。 */
    init {
        System.loadLibrary("lmc_core")
    }

    // ------------------------------------------------------------------
    // 核心 & CPU 调度
    // ------------------------------------------------------------------

    /** 初始化核心运行时（记录目录、准备 mimalloc、装载拓扑信息）。返回 false 表示环境不可用。 */
    external fun nativeInitCore(nativeLibDir: String, filesDir: String, cacheDir: String): Boolean

    /**
     * 读取 /sys/devices/system/cpu/ 拓扑，识别 big.LITTLE 集群。
     * 返回 JSON 字符串：
     * {"clusters":[{"cpus":[0,1,2,3],"maxFreqKHz":1804800,"big":false}, ...],
     *  "bigCpus":[4,5,6,7], "littleCpus":[0,1,2,3], "coreCount":8}
     */
    external fun nativeDetectCpuTopology(): String

    /** 将当前线程（游戏主线程）绑定到大核。成功返回 true。 */
    external fun nativeBindGameThreadToBigCores(): Boolean

    /** JVM 创建后调用：把 GC / JIT / VM 服务线程迁移到小核，避免与大核争抢。 */
    external fun nativeBindJvmServiceThreads(): Boolean

    /** 启动温度监控线程，predictedTemp 超过阈值（摄氏度）时回调 [onThermalWarning]。 */
    external fun nativeStartThermalMonitor(thresholdC: Float, horizonSec: Float): Boolean

    external fun nativeStopThermalMonitor()

    /** 调整 oom_score_adj，尽量让 lowmemorykiller 不杀游戏进程。返回是否成功。 */
    external fun nativeSetOomAdj(adj: Int): Boolean

    // ------------------------------------------------------------------
    // JVM 运行时层
    // ------------------------------------------------------------------

    /**
     * dlopen 加载 OpenJDK 21（libjvm.so，非 proot），调用 JNI_CreateJavaVM 创建 JVM，
     * 并在当前线程上调用 mainClass.main(mainArgs)。阻塞直到游戏退出。
     *
     * @param javaHome JDK 解压目录（如 <files>/runtime/jre21）
     * @param jvmArgs  JVM 参数（由 [buildJvmArgs] 的 native 侧生成逻辑也可在 Kotlin 拼装后传入）
     * @return 游戏主线程退出码；负数表示 JVM 创建失败
     */
    external fun nativeCreateJvm(javaHome: String, jvmArgs: Array<String>, mainClass: String, mainArgs: Array<String>): Int

    external fun nativeDestroyJvm(): Boolean

    /** GraalVM Native Image 极致模式产物是否已部署（<files>/runtime/graal/lmc-native） */
    external fun nativeNativeImageAvailable(): Boolean

    /** 极致性能模式：fork+exec native image 产物，阻塞至游戏退出。返回退出码。 */
    external fun nativeLaunchNativeImage(args: Array<String>): Int

    /**
     * 在 JNI 调用栈（ART 上下文）里预加载游戏 natives 目录的全部 so。
     * 关键：此调用与 preloadJdkLibraries 同处 ART JNI 栈——这里 dlopen 的库
     * 与 libjvm 同 linker namespace，HotSpot 后续的 dlopen 才能按 SONAME
     * 复用它们（HotSpot 自己线程的 dlopen 在独立 namespace，互相不可见）。
     */
    external fun nativePreloadGameNatives(nativesDir: String): Boolean

    /**
     * Zalith 窗口注入：直接 dlsym 调用 libpojavexec 的
     * Java_com_movtery_zalithlauncher_bridge_ZLBridge_setupBridgeWindow C 符号
     * （绕过 ART native 方法惰性解析）。
     */
    external fun nativeZalithSetupBridgeWindow(nativesDir: String, surface: android.view.Surface)

    // ------------------------------------------------------------------
    // 渲染层
    // ------------------------------------------------------------------

    /**
     * 按渲染模式布置环境变量（LD_LIBRARY_PATH、GALLIUM_DRIVER 等），必须在创建 JVM 前调用。
     * mode: 0 = Mesa Zink(+Turnip) 主路径；1 = 原生 Vulkan（VulkanMod 思路）备选；2 = gl4es 裁剪保底
     */
    external fun nativeApplyRendererEnv(mode: Int, runtimeDir: String): Boolean

    /** NativeActivity 路径：把框架下发的 ANativeWindow*（作为地址）交给渲染桥，创建 Vulkan swapchain。 */
    external fun nativeAttachNativeSurface(winPtr: Long, width: Int, height: Int): Boolean

    /** SurfaceView 路径（Compose AndroidView 嵌入）：从 java Surface 取 ANativeWindow。 */
    external fun nativeAttachJavaSurface(surface: Surface, width: Int, height: Int): Boolean

    external fun nativeDetachSurface()

    /** 查询 Vulkan pipeline cache 是否已持久化命中（false = 首次运行，需预编译着色器）。 */
    external fun nativePipelineCacheWarm(mode: Int): Boolean

    // ------------------------------------------------------------------
    // 输入层（Compose 悬浮控制层 → native 输入中枢 → 游戏）
    // ------------------------------------------------------------------

    external fun nativeInjectKey(keyCode: Int, pressed: Boolean)
    external fun nativeInjectPointer(action: Int, x: Float, y: Float, button: Int)

    // ------------------------------------------------------------------
    // 音频层
    // ------------------------------------------------------------------

    /**
     * 初始化低延迟音频：探测 AAudio MMAP 可用性并生成 OpenAL-Soft 配置
     * （backend=aaudio, 低延迟模式）。应在创建 JVM 前调用。
     */
    external fun nativeInitAudio(): Boolean

    // ------------------------------------------------------------------
    // 世界存档 mmap 读取
    // ------------------------------------------------------------------

    /** 将 region 文件（.mca）mmap 进地址空间，返回可直接被 JVM 侧使用的 DirectByteBuffer。 */
    external fun nativeOpenWorldMmap(path: String): ByteBuffer?

    external fun nativeCloseWorldMmap(buffer: ByteBuffer): Boolean

    // ------------------------------------------------------------------
    // native → Kotlin 回调（C++ 侧通过 GetStaticMethodID 调用，勿改名）
    // ------------------------------------------------------------------

    /**
     * 温度监控回调：预测温度将在 horizon 秒内越过阈值。
     * severity: 0=提醒 1=建议降渲染分辨率 2=强制降频预测
     */
    @JvmStatic
    fun onThermalWarning(predictedTempC: Float, horizonSec: Int, severity: Int) {
        _thermalListeners.forEach { it(predictedTempC, horizonSec, severity) }
    }

    private val _thermalListeners = mutableListOf<(Float, Int, Int) -> Unit>()

    /** ViewModel 注册热预警监听；返回取消注册的句柄。 */
    fun addThermalListener(listener: (predictedC: Float, horizonSec: Int, severity: Int) -> Unit): () -> Unit {
        synchronized(_thermalListeners) { _thermalListeners.add(listener) }
        return { synchronized(_thermalListeners) { _thermalListeners.remove(listener) } }
    }
}
