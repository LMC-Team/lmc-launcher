#include "thread_affinity.h"
#include "common/log.h"
#include "core_runtime.h"
#include "cpu_topology.h" // joinCpus

#include <sched.h>
#include <unistd.h>
#include <dirent.h>
#include <cstdio>
#include <cstring>
#include <atomic>

namespace lmc {

namespace {
// 记录被绑核的游戏主线程 tid（bindGameThreadToBigCores 时写入），供 UI 查询
std::atomic<int> g_gameThreadTid{0};
}

bool bindThreadToCpus(int tid, const std::vector<int>& cpus) {
    if (cpus.empty()) return false;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : cpus) CPU_SET(c, &set);
    return sched_setaffinity(static_cast<pid_t>(tid), sizeof(set), &set) == 0;
}

bool bindGameThreadToBigCores() {
    const CpuTopology& topo = runtime().topo;
    if (!topo.valid || topo.bigCpus.empty()) {
        LOGI("拓扑不可用，跳过主线程绑核");
        return true;
    }
    // 防呆护栏:大核簇只有 1 颗核 = 大概率误判(如旧频率阈值把 SM8250
    // 中核漏判,游戏线程被绑死单核,热节流时帧率崩塌 1fps vs 130fps)。
    // 真实 SoC 的性能簇至少 2 颗(2+6/1+3+4/4+4),单核一律拒绝绑核。
    if (topo.bigCpus.size() < 2) {
        LOGW("大核簇仅 %d 颗(%s),疑似误判,跳过主线程绑核",
             static_cast<int>(topo.bigCpus.size()), joinCpus(topo.bigCpus).c_str());
        return true;
    }
    // 0 表示调用线程自身
    const bool ok = bindThreadToCpus(0, topo.bigCpus);
    if (ok) g_gameThreadTid.store(static_cast<int>(gettid()), std::memory_order_relaxed);
    LOGI("游戏主线程 -> 大核 [%s]: %s",
         joinCpus(topo.bigCpus).c_str(), ok ? "成功" : "失败");
    return ok;
}

bool bindJvmServiceThreadsToLittleCores() {
    const CpuTopology& topo = runtime().topo;
    if (!topo.valid || topo.littleCpus.empty()) return true;

    DIR* dir = opendir("/proc/self/task");
    if (!dir) return false;

    int rebound = 0, total = 0;
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        int tid;
        if (sscanf(ent->d_name, "%d", &tid) != 1) continue;
        ++total;

        // 读取线程名 /proc/self/task/<tid>/comm
        char commPath[64], comm[64] = {0};
        snprintf(commPath, sizeof(commPath), "/proc/self/task/%d/comm", tid);
        FILE* f = fopen(commPath, "re");
        if (!f) continue;
        const size_t n = fread(comm, 1, sizeof(comm) - 1, f);
        fclose(f);
        comm[n > 0 ? n : 0] = '\0';
        // 去掉换行
        for (char* p = comm; *p; ++p) if (*p == '\n') { *p = '\0'; break; }

        // HotSpot 线程匹配表 —— 只迁移"纯后台"线程。
        // 注意:GC(GC Thread#/G1 )与 JIT 编译线程(C1/C2 CompilerThread)不迁移!
        // 它们参与 STW 停顿与后台编译,放到 A55 小核会显著拉长停顿
        // (真机 A/B 实测:全量迁移使帧率下降 ~12%),由调度器自行安排更优。
        const bool isService =
            strcmp(comm, "VM Periodic Task") == 0 ||
            strcmp(comm, "Service Thread") == 0 ||
            strcmp(comm, "Common-Cleaner") == 0 ||
            strcmp(comm, "Notification Thread") == 0;

        if (isService && bindThreadToCpus(tid, topo.littleCpus)) ++rebound;
    }
    closedir(dir);

    LOGI("JVM 服务线程迁核: %d/%d -> 小核 [%s]",
         rebound, total, joinCpus(topo.littleCpus).c_str());
    return true;
}

std::vector<int> getThreadAffinityCpus(int tid) {
    std::vector<int> out;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(static_cast<pid_t>(tid), sizeof(set), &set) != 0) return out;
    const long n = sysconf(_SC_NPROCESSORS_CONF);
    const int limit = static_cast<int>(n > 0 ? n : CPU_SETSIZE);
    for (int i = 0; i < limit && i < CPU_SETSIZE; ++i) {
        if (CPU_ISSET(i, &set)) out.push_back(i);
    }
    return out;
}

std::vector<int> getGameThreadCpus() {
    const int tid = g_gameThreadTid.load(std::memory_order_relaxed);
    if (tid <= 0) return {};
    return getThreadAffinityCpus(tid);
}

} // namespace lmc
