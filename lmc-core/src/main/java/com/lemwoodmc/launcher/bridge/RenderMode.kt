package com.lemwoodmc.launcher.bridge

/**
 * 渲染后端三路径定义（与 C++ core/src/render/render_bridge.cpp 中 RenderMode 枚举一一对应）。
 *
 *  ZINK          —— 主路径：Mesa Zink（GL on Vulkan）+ Turnip/厂商 Vulkan 驱动，自编译 Mesa
 *  VULKAN_NATIVE —— 备选：原生 Vulkan 渲染器（VulkanMod 思路），由 lmc_core 直接建 swapchain
 *  GL4ES         —— 保底：裁剪版 gl4es（仅保留 Minecraft 实际使用的 GL 函数）
 */
enum class RenderMode(val id: Int, val label: String, val description: String) {
    ZINK(0, "Zink (推荐)", "Mesa Zink + Turnip / 厂商 Vulkan，最佳性能"),
    VULKAN_NATIVE(1, "原生 Vulkan", "VulkanMod 思路的原生渲染器，需配套客户端"),
    GL4ES(2, "gl4es 保底", "裁剪版 GL 转译，兼容性最好"),
    ;

    companion object {
        fun fromId(id: Int): RenderMode = entries.first { it.id == id }
    }
}

/** 垃圾回收器选择，影响 JVM 参数生成（见 core/src/jvm/jvm_options.cpp）。 */
enum class GcType(val id: Int, val label: String, val flag: String) {
    /**
     * 分代 ZGC：亚毫秒停顿。
     * 注意：ZGC 初始化的 NUMA 探测会调用 get_mempolicy，被 Android 应用
     * seccomp 白名单拒绝（SIGSYS 直接杀进程）——仅可搭配打了 NUMA 补丁的
     * JRE 构建（scripts/build_openjdk.sh 方案 B 自编译时禁用 NUMA 探测）。
     */
    ZGC(0, "ZGC（分代）", "-XX:+UseZGC -XX:+ZGenerational"),
    /** ParallelGC：总吞吐最高，无 NUMA 依赖，Termux 官方 JRE 可直接使用（默认） */
    PARALLEL(1, "ParallelGC", "-XX:+UseParallelGC"),
    ;

    companion object {
        fun fromId(id: Int): GcType = entries.first { it.id == id }
    }
}

/** 一次游戏启动的完整配置，由设置页 / 主页共同编辑。 */
data class GameLaunchConfig(
    /** 固定堆大小（MB），Xms = Xmx，避免运行期堆伸缩抖动 */
    val heapSizeMb: Int = 3072,
    val renderMode: RenderMode = RenderMode.ZINK,
    val gcType: GcType = GcType.PARALLEL,
    /** JIT 编译线程数；null = 自动匹配大核数量 */
    val jitThreads: Int? = null,
    /** 启用 AppCDS（-XX:+AutoCreateSharedArchive，首次运行自动生成 .jsa） */
    val appCds: Boolean = true,
    /** 极致性能模式：跳过 JVM，直接运行 GraalVM Native Image 产物（预留路径） */
    val graalNativeImage: Boolean = false,
    /** 绑定游戏主线程到大核 */
    val pinMainThread: Boolean = true,
    /** 温度监控：预测阈值（摄氏度）与预测时间窗（秒），超过则触发降频/降分辨率保护 */
    val thermalThresholdC: Float = 78f,
    val thermalHorizonSec: Float = 20f,
    /** 世界目录（存放 region .mca 的根） */
    val worldDir: String = "saves/NewWorld/region",
    /** 游戏版本 id（用于定位 client.jar） */
    val versionId: String = "1.21.4",
    /** 游戏主类（vanilla 启动器主类） */
    val mainClass: String = "net.minecraft.client.main.Main",
    /** 透传给游戏 main 的参数 */
    val gameArgs: List<String> = emptyList(),
)
