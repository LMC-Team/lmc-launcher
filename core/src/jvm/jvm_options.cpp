#include "jvm_options.h"
#include "common/log.h"
#include "core_runtime.h"

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>

namespace lmc {

bool hasArgWithPrefix(const std::vector<std::string>& args, const char* prefix) {
    for (const auto& a : args) {
        if (a.rfind(prefix, 0) == 0) return true;
    }
    return false;
}

namespace {

// 收集一个选项字符串（先入 store，最后统一生成 JavaVMOption）
void addStr(JvmOptionsStore& store, std::string opt) {
    store.strings.push_back(std::move(opt));
}

} // namespace

bool buildJvmOptions(const std::string& javaHome,
                     const std::vector<std::string>& userArgs,
                     std::vector<JavaVMOption>& out,
                     JvmOptionsStore& store) {
    const CoreRuntime& rt = runtime();

    // ---- 0. 实验性开关总闸（放最前） ----
    if (!hasArgWithPrefix(userArgs, "-XX:+UnlockExperimentalVMOptions")) {
        addStr(store, "-XX:+UnlockExperimentalVMOptions");
    }

    // ---- 1. JIT 编译线程数匹配大核（用户显式给定时尊重用户）----
    if (!hasArgWithPrefix(userArgs, "-XX:CICompilerCount")) {
        // HotSpot 要求分层编译 CICompilerCount >= 2（C1+C2 各一组），取大核数并夹到 [2,8]
        int bigCount = static_cast<int>(rt.topo.bigCpus.size());
        if (bigCount <= 0) bigCount = 2;
        bigCount = std::min(8, std::max(2, bigCount));
        addStr(store, "-XX:CICompilerCount=" + std::to_string(bigCount));
        addStr(store, "-XX:-CICompilerCountPerCPU"); // bool 型开关：关闭需用 - 前缀
    }

    // ---- 2. AppCDS 类数据共享（用户未指定归档文件时启用默认位置）----
    // 注意：CDS dumping 与 -javaagent 冲突（需 AllowArchivingWithJavaAgent），
    // 侧载了 agent（如 cacio）时跳过 CDS
    if (!hasArgWithPrefix(userArgs, "-XX:SharedArchiveFile") &&
        !hasArgWithPrefix(userArgs, "-Xshare:off") &&
        !hasArgWithPrefix(userArgs, "-javaagent:")) {
        const std::string jsaPath = rt.cacheDir + "/app.jsa";
        addStr(store, "-XX:SharedArchiveFile=" + jsaPath);
        addStr(store, "-XX:+AutoCreateSharedArchive"); // 首次运行自动生成 .jsa
        addStr(store, "-Xshare:auto");
        LOGI("AppCDS: %s（不存在则首次运行自动生成）", jsaPath.c_str());
    }

    // ---- 3. 基础系统属性（用户未指定时补充）----
    const std::string mcHome = rt.filesDir + "/minecraft";
    mkdir(mcHome.c_str(), 0755);

    if (!hasArgWithPrefix(userArgs, "-Djava.home="))
        addStr(store, "-Djava.home=" + javaHome);
    if (!hasArgWithPrefix(userArgs, "-Djava.io.tmpdir="))
        addStr(store, "-Djava.io.tmpdir=" + rt.cacheDir);
    if (!hasArgWithPrefix(userArgs, "-Duser.home="))
        addStr(store, "-Duser.home=" + mcHome);
    if (!hasArgWithPrefix(userArgs, "-Duser.dir="))
        addStr(store, "-Duser.dir=" + mcHome);
    if (!hasArgWithPrefix(userArgs, "-Djava.library.path="))
        addStr(store, "-Djava.library.path=" + rt.nativeLibDir +
                  ":" + mcHome + ":/system/lib64");
    if (!hasArgWithPrefix(userArgs, "-Dos.name="))
        addStr(store, "-Dos.name=Linux");
    // LWJGL 3：走 Mesa 提供的 libGL（Zink 路径）；Vulkan 路径由 libvulkan.so 解析
    if (!hasArgWithPrefix(userArgs, "-Dorg.lwjgl.opengl.libname="))
        addStr(store, "-Dorg.lwjgl.opengl.libname=libGL.so.1");
    // 关闭 AWT 头依赖（无 X11）
    addStr(store, "-Dawt.toolkit=nonexistent");

    // ---- 4. 用户参数原样透传（Xms/Xmx、GC 选择、classpath 等由 Kotlin 侧拼装）----
    for (const auto& a : userArgs) addStr(store, a);

    // ---- 5. 字符串全部收集完毕后统一生成 JavaVMOption ----
    // 关键：不能边 push_back 边取 data()——vector 扩容会使先前的
    // optionString 指针悬垂，HotSpot 会读到空/垃圾选项（真机踩坑：
    // 表现为 "Unrecognized option: " 空名，JNI_CreateJavaVM 返回 -1）。
    out.reserve(store.strings.size());
    for (const auto& s : store.strings) {
        JavaVMOption o{};
        o.optionString = const_cast<char*>(s.c_str());
        o.extraInfo = nullptr;
        out.push_back(o);
    }

    LOGI("JVM 参数共 %zu 项", out.size());
    return true;
}

void setupJvmProcessEnvironment(const std::string& javaHome) {
    const CoreRuntime& rt = runtime();

    // 进程 CWD 切到 gameDir：Minecraft 的 log4j2.xml 用相对路径 logs/latest.log，
    // Java IO 的相对路径基于内核 CWD（-Duser.dir 属性不影响解析），
    // Android app 进程默认 CWD 是 / —— 必须显式 chdir（PC 上由 java 启动器完成）
    const std::string mcHome = rt.filesDir + "/minecraft";
    if (chdir(mcHome.c_str()) != 0)
        LOGW("chdir %s 失败: %s", mcHome.c_str(), strerror(errno));

    setenv("JAVA_HOME", javaHome.c_str(), 1);
    setenv("HOME", (rt.filesDir + "/minecraft").c_str(), 1);
    setenv("TMPDIR", rt.cacheDir.c_str(), 1);
    setenv("PATH", (javaHome + "/bin:/system/bin").c_str(), 1);
    // 告知 JVM 线程栈较小的运行环境
    setenv("LC_ALL", "C", 0);

    // LD_LIBRARY_PATH：JDK 自身库 + APK native 目录 + Mesa 驱动目录
    // （渲染模式的驱动目录由 render_bridge::applyRendererEnvironment 前置注入）
    std::string ld = javaHome + "/lib/server:" + javaHome + "/lib:" + rt.nativeLibDir;
    const char* existing = getenv("LD_LIBRARY_PATH");
    if (existing && *existing) ld = std::string(existing) + ":" + ld;
    setenv("LD_LIBRARY_PATH", ld.c_str(), 1);

    LOGI("进程环境就绪: JAVA_HOME=%s", javaHome.c_str());
}

} // namespace lmc
