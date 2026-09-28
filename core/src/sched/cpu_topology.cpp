#include "cpu_topology.h"
#include "common/log.h"

#include <dirent.h>
#include <algorithm>
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
    }
    closedir(dir);

    if (freqGroups.empty() || topo.coreCount == 0) {
        LOGW("未读取到任何 cpufreq 频率信息");
        return topo;
    }

    // 集群按频率升序排列
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

    // 大核判定：频率 ≥ 最高簇的 70% 的簇都归入大核。
    // 覆盖三簇 SoC（骁龙 870 = 1+3+4）：超大核与中核（A78 @2.42GHz ≈ 超大核 85%）
    // 同属大核，游戏主线程绑定 4 核而非 1 核，避免渲染/GC 线程与主线程挤在单核上；
    // 典型 4+4 双簇（A55 1.8GHz / A78 2.8GHz，比值 63%）仍正确区分大小核。
    // 同构系统（单簇）：全部归大核，littleCpus 为空，调用方跳过绑核。
    const uint64_t topFreq = topo.clusters.back().maxFreqKHz;
    for (auto& c : topo.clusters) {
        c.isBig = (static_cast<uint64_t>(c.maxFreqKHz) * 10 >= topFreq * 7);
        if (c.isBig) {
            topo.bigCpus.insert(topo.bigCpus.end(), c.cpus.begin(), c.cpus.end());
        } else {
            topo.littleCpus.insert(topo.littleCpus.end(), c.cpus.begin(), c.cpus.end());
        }
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
