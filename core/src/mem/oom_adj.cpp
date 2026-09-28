#include "oom_adj.h"
#include "common/log.h"

#include <cstdio>
#include <cstring>

namespace lmc {

bool setOomScoreAdj(int adj) {
    FILE* f = fopen("/proc/self/oom_score_adj", "we");
    if (!f) {
        LOGW("打开 oom_score_adj 失败（SELinux 限制）");
        return false;
    }
    const int rc = fprintf(f, "%d", adj);
    fclose(f);
    if (rc < 0) return false;

    // 读回实际生效值（内核可能夹紧）
    const int actual = getOomScoreAdj();
    LOGI("oom_score_adj: 请求 %d, 实际 %d", adj, actual);
    return true;
}

int getOomScoreAdj() {
    FILE* f = fopen("/proc/self/oom_score_adj", "re");
    if (!f) return -1;
    int v = 0;
    if (fscanf(f, "%d", &v) != 1) v = -1;
    fclose(f);
    return v;
}

} // namespace lmc
