#include "graal_runner.h"
#include "common/log.h"
#include "core_runtime.h"

#include <sys/wait.h>
#include <unistd.h>

namespace lmc {

std::string nativeImagePath() {
    return runtime().filesDir + "/runtime/graal/lmc-native";
}

bool nativeImageAvailable() {
    return access(nativeImagePath().c_str(), X_OK) == 0;
}

JvmLaunchResult launchNativeImage(const std::vector<std::string>& gameArgs) {
    JvmLaunchResult result;
    if (!nativeImageAvailable()) {
        result.errorMessage = "未部署 native image 产物（scripts/build_graal_native_image.sh 预留）";
        LOGW("%s", result.errorMessage.c_str());
        return result;
    }

    // 组装 argv 并 fork+exec。子进程继承大核亲和性（父线程已绑核）。
    std::vector<char*> argv;
    argv.push_back(const_cast<char*>(nativeImagePath().c_str()));
    for (const auto& a : gameArgs) argv.push_back(const_cast<char*>(a.c_str()));
    argv.push_back(nullptr);

    const pid_t pid = fork();
    if (pid == 0) {
        execv(nativeImagePath().c_str(), argv.data());
        _exit(127); // exec 失败
    }
    int status = 0;
    waitpid(pid, &status, 0);
    result.exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    LOGI("native image 退出: %d", result.exitCode);
    return result;
}

} // namespace lmc
