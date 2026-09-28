#include "thread_affinity.h"
#include "common/log.h"
#include "core_runtime.h"
#include "cpu_topology.h" // joinCpus

#include <sched.h>
#include <unistd.h>
#include <dirent.h>
#include <cstdio>
#include <cstring>

namespace lmc {

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
    // 0 表示调用线程自身
    const bool ok = bindThreadToCpus(0, topo.bigCpus);
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

        // HotSpot 服务线程匹配表
        const bool isService =
            strncmp(comm, "GC Thread", 9) == 0 ||      // Parallel/ZGC GC 工作线程
            strncmp(comm, "G1 ", 3) == 0 ||            // G1 服务线程（若使用）
            strncmp(comm, "C1 CompilerThread", 17) == 0 || // C1 JIT 编译线程
            strncmp(comm, "C2 CompilerThread", 17) == 0 || // C2 JIT 编译线程
            strcmp(comm, "VM Thread") == 0 ||
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

} // namespace lmc
