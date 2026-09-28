// 核心运行时上下文：全后端共享的路径与状态单例
#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <mutex>

namespace lmc {

/** CPU 集群（同一频率档位的核） */
struct CpuCluster {
    std::vector<int> cpus;      // 集群内的逻辑核编号
    uint32_t maxFreqKHz;        // 集群最高频率
    uint32_t minFreqKHz;
    bool isBig = false;         // 是否归入大核（频率 ≥ 最高簇 70%）
};

/** big.LITTLE 拓扑（由 sched/cpu_topology.cpp 解析） */
struct CpuTopology {
    std::vector<CpuCluster> clusters; // 按最高频率升序排列
    std::vector<int> bigCpus;         // 最高频集群（游戏主线程绑定目标）
    std::vector<int> littleCpus;      // 其余集群（GC/JIT/IO 线程绑定目标）
    int coreCount = 0;
    bool valid = false;
};

/** 渲染后端模式，与 Kotlin 侧 RenderMode 一一对应 */
enum class RenderMode {
    Zink = 0,          // 主路径：Mesa Zink（GL on Vulkan）+ Turnip/厂商驱动
    VulkanNative = 1,  // 备选：原生 Vulkan 渲染器（VulkanMod 思路）
    GL4ES = 2,         // 保底：裁剪版 gl4es
};

/**
 * 全局运行时上下文。
 * 由 nativeInitCore 初始化一次，之后所有模块只读访问。
 */
struct CoreRuntime {
    // ---- 目录（由 Kotlin 侧传入） ----
    std::string nativeLibDir; // APK 解包的 so 目录（libjvm 之外的 LWJGL 等）
    std::string filesDir;     // /data/data/<pkg>/files
    std::string cacheDir;     // /data/data/<pkg>/cache

    // ---- CPU 拓扑 ----
    CpuTopology topo;

    // ---- 音频探测结果 ----
    bool aaudioMmapAvailable = false;
    int  aaudioSampleRate = 48000;
    int  aaudioBurstFrames = 0;

    bool initialized = false;

    std::mutex mutex; // 保护可变字段
};

/** 全局单例访问 */
CoreRuntime& runtime();

/** 初始化（nativeInitCore 调用） */
bool initRuntime(const std::string& nativeLibDir,
                 const std::string& filesDir,
                 const std::string& cacheDir);

} // namespace lmc
