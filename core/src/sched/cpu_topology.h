// CPU 拓扑识别：读取 /sys/devices/system/cpu/ 下的 cpufreq 信息，
// 按“最高频率”聚类识别 big.LITTLE（DynamIQ）架构。
#pragma once

#include <string>
#include <vector>
#include "core_runtime.h" // CpuTopology / CpuCluster

namespace lmc {

/**
 * 扫描 /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq，
 * 将相同最高频率的核归为一个集群；频率 ≥ 最高簇 70% 的集群视为“大核”
 * （覆盖骁龙 870 这类 1+3+4 三簇 SoC：超大核与中核同属大核）。
 *
 * 已覆盖：骁龙（3/5/7/8 系大小核或三簇）、天玑、麒麟、Exynos。
 * 若全部核频率一致（同构），bigCpus 为全核、littleCpus 为空 —— 调用方应跳过绑核。
 */
CpuTopology detectCpuTopology();

/** 拓扑转 JSON（Kotlin 设置页展示用） */
std::string topologyToJson(const CpuTopology& topo);

/** 将核编号列表格式化为 "0,1,2,3" 形式（日志用） */
std::string joinCpus(const std::vector<int>& cpus);

} // namespace lmc
