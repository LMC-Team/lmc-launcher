// CPU 亲和性绑定：基于 sched_setaffinity 的大小核调度策略
#pragma once

#include <vector>

namespace lmc {

/** 将指定线程（tid = gettid() 可用 0 表示当前线程）绑定到给定核集合 */
bool bindThreadToCpus(int tid, const std::vector<int>& cpus);

/**
 * 将当前线程（游戏主线程）绑定到大核。
 * 拓扑不可用或同构时为 no-op 并返回 true（不视为错误）。
 */
bool bindGameThreadToBigCores();

/**
 * JVM 启动完成后调用：扫描 /proc/self/task/ 下各线程的 comm 文件，
 * 把 GC / JIT 编译 / VM 服务线程迁移到小核，让大核专注于游戏主线程与渲染线程。
 *
 * 匹配的线程名（OpenJDK HotSpot 命名）：
 *   "GC Thread#N"、"G1 ..."、"C1 CompilerThreadN"、"C2 CompilerThreadN"、
 *   "VM Thread"、"VM Periodic Task"、"Service Thread"、"Common-Cleaner"
 */
bool bindJvmServiceThreadsToLittleCores();

} // namespace lmc
