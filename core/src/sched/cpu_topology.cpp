#include "cpu_topology.h"
#include "common/log.h"

#include <dirent.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>

namespace lmc {

namespace {

// 读取单个小文本文件（sysfs 节点），失败返回空串
std::string readFileTrim(const std::string& path) {
    FILE* f = fopen(path.c_str(), "re");
    if (!f) return "";
    char buf[128] = {0};
    const size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    // 去掉换行与空白
    std::string s(buf, n);
    while (!s.empty() && (s.back() == '\n' || s.back() == ' ' || s.back() == '\r')) s.pop_back();
    return s;
}

} // namespace

CpuTopology detectCpuTopology() {
    CpuTopology topo;

    // 遍历 /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq
    // freq 表：maxFreq -> 归属该频率档位的核
    std::map<uint32_t, std::vector<int>> freqGroups;
    std::map<int, uint32_t> capacities; // cpuId -> cpu_capacity(0=未读到)
    DIR* dir = opendir("/sys/devices/system/cpu");
    if (!dir) {
        LOGE("无法打开 /sys/devices/system/cpu");
        return topo;
    }
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        int cpuId;
        if (sscanf(ent->d_name, "cpu%d", &cpuId) != 1) continue; // 跳过 cpuidle 等目录
        // online 检查（热插拔核不参与调度规划）
        const std::string online =
            readFileTrim("/sys/devices/system/cpu/cpu" + std::to_string(cpuId) + "/online");
        if (online == "0" && cpuId != 0) continue;

        const std::string maxFreqStr = readFileTrim(
            "/sys/devices/system/cpu/cpu" + std::to_string(cpuId) + "/cpufreq/cpuinfo_max_freq");
        if (maxFreqStr.empty()) continue;
        uint32_t maxFreqKHz = 0;
        sscanf(maxFreqStr.c_str(), "%u", &maxFreqKHz);
        if (maxFreqKHz == 0) continue;
        freqGroups[maxFreqKHz].push_back(cpuId);
        topo.coreCount++;

        // cpu_capacity(ARM 标准节点,每核调度容量):大核判定的首选信号
        const std::string capStr = readFileTrim(
            "/sys/devices/system/cpu/cpu" + std::to_string(cpuId) + "/cpu_capacity");
        uint32_t cap = 0;
        if (!capStr.empty()) sscanf(capStr.c_str(), "%u", &cap);
        if (cap > 0) capacities[cpuId] = cap;
    }
    closedir(dir);

    if (freqGroups.empty() || topo.coreCount == 0) {
        LOGW("未读取到任何 cpufreq 频率信息");
        return topo;
    }

    // 集群按频率升序排列
    bool capacityUsable = true;
    for (auto& [freq, cpus] : freqGroups) {
        std::sort(cpus.begin(), cpus.end());
        CpuCluster c;
        c.cpus = cpus;
        c.maxFreqKHz = freq;
        // 最小频率读取失败不影响主流程
        c.minFreqKHz = freq / 4;
        for (int cpu : cpus) {
            const std::string minStr = readFileTrim(
                "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_min_freq");
            if (!minStr.empty()) sscanf(minStr.c_str(), "%u", &c.minFreqKHz);
        }
        topo.clusters.push_back(c);
    }

    // 大核判定：**容量优先,最低簇=小核,其余全部=大核**。
    // Android 语义：异构 SoC 中非 LITTLE 的核统称大核,绑核目标就是
    // "所有性能核"——所以不需要也不应该用"≥最高簇 X%"这类相对阈值。
    //
    // 旧方案(频率 ≥ 最高簇 80%)已在真机翻车(SM8250):
    //   中核 2.419GHz / 超大核 3.187GHz = 75.9% < 80% → 中核三颗被判
    //   小核 → 游戏主线程只绑 [7] 单核;热节流时 cpu7 降频最狠且线程
    //   无法迁移 → 帧率 1fps(同热态 lmc 关闭为 130fps,差 130 倍,
    //   详见 docs/BENCHMARK-2026-10-13.md §7)
    //
    // 实测容量信号(两台真机一致):SM7635/SM8250 = 313/777/1024
    //   → 小核 [0-3], 大核 [4-7] ✓
    // 容量节点不可读时回退频率,同样用"严格大于最低簇"规则:
    //   1.8/2.4/3.2GHz → 小核[0-3] 大核[4-7] ✓
    //   1.8/2.8GHz     → 小核[0-3] 大核[4-7] ✓
    // 同构(所有簇 rank 相等)→ 无小核,valid=false,调用方跳过绑核。

    // 每簇取 rank:全簇 cpu_capacity 可用且一致 → 用容量;否则整体回退频率
    uint32_t minRank = UINT32_MAX, maxRank = 0;
    std::vector<uint32_t> ranks;
    for (auto& [freq, cpus] : freqGroups) {
        (void)freq;
        uint32_t rank = 0;
        if (capacityUsable) {
            const uint32_t first = capacities.count(cpus.front()) ? capacities[cpus.front()] : 0;
            if (first == 0) { capacityUsable = false; }
            else {
                for (int cpu : cpus) {
                    const uint32_t cap = capacities.count(cpu) ? capacities[cpu] : 0;
                    if (cap != first) { capacityUsable = false; break; }
                }
                if (capacityUsable) rank = first;
            }
        }
        ranks.push_back(rank);
    }
    if (!capacityUsable) {
        // 频率兜底:rank 直接用簇 maxFreqKHz
        size_t i = 0;
        for (auto& [freq, cpus] : freqGroups) {
            (void)cpus;
            ranks[i++] = freq;
        }
        LOGI("cpu_capacity 不可用,回退频率判定");
    }
    for (uint32_t r : ranks) {
        if (r < minRank) minRank = r;
        if (r > maxRank) maxRank = r;
    }
    LOGI("大核判定: %s, 各簇 rank = %s, min=%u",
         capacityUsable ? "cpu_capacity" : "频率",
         [&] {
             std::string s;
             for (size_t i = 0; i < ranks.size(); ++i) {
                 s += std::to_string(ranks[i]);
                 if (i + 1 < ranks.size()) s += "/";
             }
             return s;
         }().c_str(),
         minRank);

    size_t idx = 0;
    for (auto& c : topo.clusters) {
        c.isBig = (maxRank > minRank) && (ranks[idx] > minRank);
        if (c.isBig) {
            topo.bigCpus.insert(topo.bigCpus.end(), c.cpus.begin(), c.cpus.end());
        } else {
            topo.littleCpus.insert(topo.littleCpus.end(), c.cpus.begin(), c.cpus.end());
        }
        ++idx;
    }
    // 大小核无法区分（同构 / 全部大核）时标记无效，调用方不绑核
    topo.valid = !topo.littleCpus.empty() &&
                 topo.bigCpus.size() < static_cast<size_t>(topo.coreCount);
    return topo;
}

std::string topologyToJson(const CpuTopology& topo) {
    std::string json = "{\"clusters\":[";
    for (size_t i = 0; i < topo.clusters.size(); ++i) {
        const CpuCluster& c = topo.clusters[i];
        json += "{\"cpus\":[";
        for (size_t j = 0; j < c.cpus.size(); ++j) {
            json += std::to_string(c.cpus[j]);
            if (j + 1 < c.cpus.size()) json += ",";
        }
        json += "],\"maxFreqKHz\":" + std::to_string(c.maxFreqKHz);
        json += ",\"big\":";
        json += c.isBig ? "true" : "false";
        json += "}";
        if (i + 1 < topo.clusters.size()) json += ",";
    }
    json += "],\"bigCpus\":[";
    for (size_t i = 0; i < topo.bigCpus.size(); ++i) {
        json += std::to_string(topo.bigCpus[i]);
        if (i + 1 < topo.bigCpus.size()) json += ",";
    }
    json += "],\"littleCpus\":[";
    for (size_t i = 0; i < topo.littleCpus.size(); ++i) {
        json += std::to_string(topo.littleCpus[i]);
        if (i + 1 < topo.littleCpus.size()) json += ",";
    }
    json += "],\"coreCount\":" + std::to_string(topo.coreCount) + "}";
    return json;
}

std::string joinCpus(const std::vector<int>& cpus) {
    std::string s;
    for (size_t i = 0; i < cpus.size(); ++i) {
        s += std::to_string(cpus[i]);
        if (i + 1 < cpus.size()) s += ",";
    }
    return s;
}

} // namespace lmc
