#include "jvm_loader.h"
#include "common/log.h"
#include "core_runtime.h"
#include "jni_bridge/jni_globals.h"
#include "jvm_options.h"

#include <dlfcn.h>
#include <dirent.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <string>

namespace lmc {

namespace {

// NDK 自带的 jni.h 最高只定义到 JNI_VERSION_1_6，OpenJDK 21 接受 1.8 请求
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

JavaVM* g_jvm = nullptr;
JNIEnv* g_env = nullptr;
void* g_jvmLibHandle = nullptr;

using JNI_CreateJavaVM_t = jint(JNICALL*)(JavaVM**, JNIEnv**, void*);

/**
 * 预加载 JDK 库链（dlopen libjvm 的前置条件）。
 *
 * bionic linker 规则：dlopen 的依赖解析只搜索 linker namespace 固化路径
 * （APK lib 目录 + 系统路径），运行期 setenv(LD_LIBRARY_PATH) 无效，
 * classloader-namespace 也不包含应用私有目录——因此 JRE 的库必须
 * 按"绝对路径 + RTLD_GLOBAL"显式加载（Pojav 同款策略）。
 * 顺序：shim 库 → libjvm(server) → HotSpot 核心库；其余 lib/*.so best-effort
 * （AWT/alsa 等缺失依赖允许失败，核心启动路径用不到）。
 */
void preloadJdkLibraries(const std::string& javaHome) {
    const std::string libDir = javaHome + "/lib";

    // 1) Termux 构建的 shim 库 + zlib（libjli 依赖 libz.so.1，Termux 命名）
    for (const char* shim : {"libandroid-shmem.so", "libandroid-spawn.so", "libz.so.1"}) {
        if (dlopen((libDir + "/" + shim).c_str(), RTLD_NOW | RTLD_GLOBAL))
            LOGI("预加载 %s 成功", shim);
        else
            LOGW("预加载 %s 失败: %s", shim, dlerror());
    }

    // 2) libjvm（Termux 打包的 libjvm 依赖链里挂着 libjli → libz）
    g_jvmLibHandle = dlopen((libDir + "/server/libjvm.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g_jvmLibHandle)
        LOGE("预加载 server/libjvm.so 失败: %s", dlerror());

    // 3) HotSpot 核心库（libjava 依赖 libjvm，必须在其后；libjli 在 libjvm 链中已加载）
    for (const char* core : {"libjli.so", "libverify.so", "libjava.so", "libzip.so",
                             "libnet.so", "libnio.so", "libprefs.so"}) {
        if (!dlopen((libDir + "/" + core).c_str(), RTLD_NOW | RTLD_GLOBAL))
            LOGW("预加载 %s 失败: %s", core, dlerror());
    }

    // 4) lib/ 下其余库 best-effort（已加载的会直接命中返回，无副作用）
    if (DIR* d = opendir(libDir.c_str())) {
        struct dirent* ent;
        while ((ent = readdir(d)) != nullptr) {
            const std::string name = ent->d_name;
            if (name.length() > 3 && name.compare(name.size() - 3, 3, ".so") == 0)
                dlopen((libDir + "/" + name).c_str(), RTLD_NOW | RTLD_GLOBAL); // 失败静默
        }
        closedir(d);
    }
}

// 把 mainArgs 组装为 java.lang.String[]
jobjectArray makeStringArray(JNIEnv* env, const std::vector<std::string>& args) {
    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(args.size()), strClass, nullptr);
    for (size_t i = 0; i < args.size(); ++i) {
        jstring s = env->NewStringUTF(args[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(strClass);
    return arr;
}

// 设置当前线程的上下文类加载器（Minecraft 的 ServiceLoader 依赖它）
void setContextClassLoader(JNIEnv* env) {
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (!threadClass) return;
    jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jmethodID getContextCl = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    if (!currentThread || !getContextCl) return;
    jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
    if (!thread) return;

    // 用应用 ClassLoader 作为 TCCL：保证能加载 app classpath 与 LWJGL
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystemCl = env->GetStaticMethodID(classLoaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject systemCl = env->CallStaticObjectMethod(classLoaderClass, getSystemCl);
    jmethodID setContextCl = env->GetMethodID(threadClass, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
    if (systemCl && setContextCl) env->CallVoidMethod(thread, setContextCl, systemCl);

    env->DeleteLocalRef(thread);
    env->DeleteLocalRef(classLoaderClass);
    env->DeleteLocalRef(systemCl);
    env->DeleteLocalRef(threadClass);
}

} // namespace

JvmLaunchResult createJvmAndRunMain(const std::string& javaHome,
                                    const std::vector<std::string>& jvmArgs,
                                    const std::string& mainClass,
                                    const std::vector<std::string>& mainArgs) {
    JvmLaunchResult result;
    if (g_jvm) {
        result.errorMessage = "JVM 已在运行";
        return result;
    }

    // ---- 1. 预加载 JDK 库链（含 dlopen libjvm.so 本身）----
    // Termux 补丁版 OpenJDK 21 aarch64，布局 <javaHome>/lib/server/libjvm.so
    preloadJdkLibraries(javaHome);
    if (!g_jvmLibHandle) {
        LOGE("dlopen libjvm.so 失败: %s", dlerror());
        result.errorMessage = "无法加载 libjvm.so（检查 runtime 是否已由 scripts/build_openjdk.sh 部署）";
        return result;
    }

    auto createJvmFn = reinterpret_cast<JNI_CreateJavaVM_t>(dlsym(g_jvmLibHandle, "JNI_CreateJavaVM"));
    if (!createJvmFn) {
        result.errorMessage = "libjvm.so 中找不到 JNI_CreateJavaVM 符号";
        LOGE("%s", result.errorMessage.c_str());
        return result;
    }

    // ---- 2. 组装 JVM 参数（AppCDS / GC / JIT 调优见 jvm_options.cpp）----
    // storage 保证 optionString 的内存在 CreateJavaVM 调用期间有效
    auto store = std::make_unique<JvmOptionsStore>();
    std::vector<JavaVMOption> options;
    if (!buildJvmOptions(javaHome, jvmArgs, options, *store)) {
        result.errorMessage = "JVM 参数组装失败";
        return result;
    }
    JavaVMInitArgs vmArgs{};
    vmArgs.version = JNI_VERSION_1_8;
    vmArgs.nOptions = static_cast<jint>(options.size());
    vmArgs.options = options.data();
    vmArgs.ignoreUnrecognized = JNI_FALSE; // 拼错的参数直接报错，尽早暴露问题

    // ---- 3. 创建 JVM（当前线程成为游戏主线程，之前应已绑大核）----
    // HotSpot 的启动错误（参数错误 / 初始化失败详情）全部写到 stderr，
    // 而 Android 应用进程的 stderr 默认指向 /dev/null —— 重定向到文件以便诊断。
    const std::string errLogPath = runtime().cacheDir + "/jvm_stderr.log";
    if (!freopen(errLogPath.c_str(), "w", stderr)) {
        LOGW("stderr 重定向到 %s 失败", errLogPath.c_str());
    } else {
        LOGI("HotSpot 启动输出 → %s", errLogPath.c_str());
    }
    // stdout 同样捕获（-verbose:class 等诊断输出走 stdout）
    const std::string outLogPath = runtime().cacheDir + "/jvm_stdout.log";
    if (!freopen(outLogPath.c_str(), "w", stdout)) {
        LOGW("stdout 重定向到 %s 失败", outLogPath.c_str());
    }

    const jint rc = createJvmFn(&g_jvm, &g_env, &vmArgs);
    if (rc != JNI_OK || !g_jvm) {
        LOGE("JNI_CreateJavaVM 失败: %d", rc);
        result.errorMessage = "JNI_CreateJavaVM 失败: " + std::to_string(rc);
        return result;
    }
    setGlobalJvm(g_jvm);
    LOGI("JVM 已创建（OpenJDK 21 aarch64, %zu 项参数）", options.size());

    // ---- 4. 调用 main ----
    JNIEnv* env = g_env;
    // mainClass 形如 "net.minecraft.client.main.Main" -> "net/minecraft/client/main/Main"
    std::string slashed = mainClass;
    std::replace(slashed.begin(), slashed.end(), '.', '/');
    jclass mainClazz = env->FindClass(slashed.c_str());
    if (!mainClazz) {
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        result.errorMessage = "找不到主类 " + mainClass;
        return result;
    }
    jmethodID mainMethod = env->GetStaticMethodID(mainClazz, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        result.errorMessage = "主类缺少 main(String[]) 方法";
        return result;
    }
    jobjectArray argsArray = makeStringArray(env, mainArgs);
    if (!argsArray) {
        result.errorMessage = "构造 main 参数数组失败";
        return result;
    }

    setContextClassLoader(env);
    LOGI("启动游戏主类 %s", mainClass.c_str());
    env->CallStaticVoidMethod(mainClazz, mainMethod, argsArray);

    // ---- 5. 收尾 ----
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        result.exitCode = 1;
    } else {
        result.exitCode = 0;
    }
    LOGI("游戏主线程退出，exit=%d，开始 DestroyJavaVM", result.exitCode);

    // 等待所有非守护线程结束后销毁 JVM
    if (g_jvm->DestroyJavaVM() == JNI_OK) {
        LOGI("JVM 已销毁");
    }
    g_jvm = nullptr;
    setGlobalJvm(nullptr);
    return result;
}

bool destroyJvm() {
    if (!g_jvm) return true;
    const jint rc = g_jvm->DestroyJavaVM();
    g_jvm = nullptr;
    setGlobalJvm(nullptr);
    return rc == JNI_OK;
}

bool isJvmAlive() { return g_jvm != nullptr; }

} // namespace lmc
