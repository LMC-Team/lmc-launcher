#include "core_runtime.h"
#include "common/log.h"
#include "sched/cpu_topology.h"
#include "audio/audio_backend.h"

namespace lmc {

CoreRuntime& runtime() {
    static CoreRuntime rt;
    return rt;
}

bool initRuntime(const std::string& nativeLibDir,
                 const std::string& filesDir,
                 const std::string& cacheDir) {
    std::lock_guard<std::mutex> lock(runtime().mutex);
    if (runtime().initialized) return true;

    runtime().nativeLibDir = nativeLibDir;
    runtime().filesDir = filesDir;
    runtime().cacheDir = cacheDir;

    // 读取 big.LITTLE 拓扑，后续绑核与 JIT 线程数都依赖它
    runtime().topo = detectCpuTopology();
    if (runtime().topo.valid) {
        LOGI("CPU 拓扑: %d 核, 大核 [%s], 小核 [%s]",
             runtime().topo.coreCount,
             joinCpus(runtime().topo.bigCpus).c_str(),
             joinCpus(runtime().topo.littleCpus).c_str());
    } else {
        LOGW("CPU 拓扑解析失败，退化为不绑核策略");
    }

    // 预探测 AAudio MMAP 低延迟能力（结果缓存，游戏启动时直接生成 OpenAL 配置）
    probeAudioCapabilities(&runtime().aaudioMmapAvailable,
                           &runtime().aaudioSampleRate,
                           &runtime().aaudioBurstFrames);
    LOGI("AAudio MMAP: %s, 采样率 %d, burst %d 帧",
         runtime().aaudioMmapAvailable ? "支持" : "不支持(回落 OpenSL)",
         runtime().aaudioSampleRate, runtime().aaudioBurstFrames);

    runtime().initialized = true;
    return true;
}

} // namespace lmc
