#include "gl4es_loader.h"
#include "common/log.h"

#include <dlfcn.h>
#include <mutex>

namespace lmc {

bool ensureGl4esLoaded(const std::string& gl4esDir) {
    static std::once_flag once;
    static bool ok = false;
    // 注意：静态存储期变量不能被 lambda 捕获，直接按名访问
    std::call_once(once, [&gl4esDir] {
        // PojavLauncherTeam/gl4es 产物名：libgl4es_114.so（GL 1.4/2.1 子集）
        // 按优先级尝试常见命名
        for (const char* name : {"libgl4es_114.so", "libgl4es.so", "libGL.so"}) {
            const std::string p = gl4esDir + "/" + name;
            if (void* h = dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL)) {
                LOGI("gl4es 已加载: %s", p.c_str());
                ok = true;
                return;
            }
        }
        LOGW("gl4es 未找到于 %s（保底路径不可用）", gl4esDir.c_str());
    });
    return ok;
}

} // namespace lmc
