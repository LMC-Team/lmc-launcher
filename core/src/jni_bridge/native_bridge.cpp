// JNI 桥：Kotlin（com.lemwoodmc.launcher.bridge.NativeBridge）↔ C++ 核心后端
//
// 命名约定：Kotlin external fun nativeXxx ↔ Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeXxx
// native → Kotlin 回调：NativeBridge.onThermalWarning(FII)V（静态方法，JNI_OnLoad 缓存）
#include "common/log.h"
#include "core_runtime.h"
#include "jni_bridge/jni_globals.h"
#include "jvm/jvm_loader.h"
#include "jvm/jvm_options.h"
#include "graal/graal_runner.h"
#include "render/render_bridge.h"
#include "sched/cpu_topology.h"
#include "sched/thread_affinity.h"
#include "sched/thermal_monitor.h"
#include "input/input_hub.h"
#include "audio/audio_backend.h"
#include "mem/oom_adj.h"
#include "mem/mmap_saves.h"

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

// ------------------------------------------------------------------
// JNI_OnLoad 缓存
// ------------------------------------------------------------------

jclass g_bridgeClass = nullptr;
jmethodID g_thermalCallback = nullptr;

// ART 宿主的 JavaVM：JNI_OnLoad 时缓存，专供 native 后台线程回调 ART 侧 Java。
// 不可用 lmc::globalJvm() 代替——HotSpot JVM 创建后它会覆盖成 HotSpot 的 VM，
// 温度回调若用 HotSpot 的 env 调 ART 的 jobject/methodID 会直接 SEGV
// （真机 2026-10-01：世界生成阶段 DefaultDispatch 线程 SIGSEGV @ libjvm.so）。
JavaVM* g_artHostVm = nullptr;

// mmap 存档句柄表：DirectByteBuffer 地址 → WorldMmap
std::mutex g_mmapMutex;
std::unordered_map<const void*, lmc::WorldMmap> g_mmaps;

std::string toStdString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(toStdString(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

// 温度预警 → Java 回调（native 监控线程，需临时 Attach ART 宿主 JVM）
void thermalCallbackToJava(float predictedC, int horizonSec, lmc::ThermalSeverity severity) {
    JavaVM* vm = g_artHostVm;
    if (!vm || !g_thermalCallback) return;
    JNIEnv* env = nullptr;
    const bool needDetach = (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK);
    if (needDetach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;

    env->CallStaticVoidMethod(g_bridgeClass, g_thermalCallback,
                              static_cast<jfloat>(predictedC),
                              static_cast<jint>(horizonSec),
                              static_cast<jint>(severity));

    // 渲染桥联动：降分辨率（原生 Vulkan 渲染器消费 renderScale）
    lmc::notifyThermalThrottle(static_cast<int>(severity));

    if (env->ExceptionCheck()) env->ExceptionClear();
    if (needDetach) vm->DetachCurrentThread();
}

} // namespace

extern "C" {

// ------------------------------------------------------------------
// 库加载：缓存 JavaVM 与回调方法
// ------------------------------------------------------------------

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    lmc::setGlobalJvm(vm);
    g_artHostVm = vm; // ART 宿主 VM：温度回调专用（HotSpot 创建后会覆盖 globalJvm）
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cls = env->FindClass("com/lemwoodmc/launcher/bridge/NativeBridge");
    if (!cls) return JNI_ERR;
    g_bridgeClass = static_cast<jclass>(env->NewGlobalRef(cls));
    env->DeleteLocalRef(cls);

    // static fun onThermalWarning(predictedTempC: Float, horizonSec: Int, severity: Int)
    g_thermalCallback = env->GetStaticMethodID(g_bridgeClass, "onThermalWarning", "(FII)V");
    if (!g_thermalCallback) {
        LOGE("找不到 onThermalWarning 回调，温度预警将无法上报 UI");
    }
    LOGI("liblmc_core JNI_OnLoad 完成");
    return JNI_VERSION_1_6;
}

// ------------------------------------------------------------------
// 核心 & CPU 调度
// ------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeInitCore(
        JNIEnv* env, jobject, jstring nativeLibDir, jstring filesDir, jstring cacheDir) {
    return lmc::initRuntime(toStdString(env, nativeLibDir),
                            toStdString(env, filesDir),
                            toStdString(env, cacheDir)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeDetectCpuTopology(JNIEnv* env, jobject) {
    return env->NewStringUTF(lmc::topologyToJson(lmc::runtime().topo).c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeBindGameThreadToBigCores(JNIEnv*, jobject) {
    return lmc::bindGameThreadToBigCores() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeBindJvmServiceThreads(JNIEnv*, jobject) {
    return lmc::bindJvmServiceThreadsToLittleCores() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeStartThermalMonitor(
        JNIEnv*, jobject, jfloat thresholdC, jfloat horizonSec) {
    return lmc::startThermalMonitor(thresholdC, horizonSec, thermalCallbackToJava) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeStopThermalMonitor(JNIEnv*, jobject) {
    lmc::stopThermalMonitor();
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeSetOomAdj(JNIEnv*, jobject, jint adj) {
    return lmc::setOomScoreAdj(adj) ? JNI_TRUE : JNI_FALSE;
}

// ------------------------------------------------------------------
// JVM 运行时层
// ------------------------------------------------------------------

// 纯 native 线程跑 JVM（pthread）：HotSpot 与 MC main 都在该线程上运行，
// 该线程不属于 ART —— pojavexec calculateFPS 的 Attach→Call→Detach
// （onGraphicOutput 首帧回调）对其是合法序列。若 JVM 跑在 ART 线程
// （协程 worker）上，Detach 会命中 ART 的 "detach while still running
// code" abort（真机 2026-10-01，debug/release 均崩）。
static std::thread g_jvmThread;
static std::atomic<jint> g_jvmExitCode{-1};
static std::atomic<bool> g_jvmThreadDone{false};

JNIEXPORT void JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeCreateJvmOnNewThread(
        JNIEnv* env, jobject, jstring javaHome, jobjectArray jvmArgs,
        jstring mainClass, jobjectArray mainArgs) {
    // 参数在当前线程取成值拷贝（GetStringUTFChars 的指针跨线程无效）
    auto* a = new lmc::JvmRunArgs{
        toStdString(env, javaHome), toStringVector(env, jvmArgs),
        toStdString(env, mainClass), toStringVector(env, mainArgs)};
    g_jvmThreadDone.store(false);
    g_jvmThread = std::thread([a] {
        lmc::setupJvmProcessEnvironment(a->javaHome);
        const lmc::JvmLaunchResult r =
            lmc::createJvmAndRunMain(a->javaHome, a->jvmArgs, a->mainClass, a->mainArgs);
        if (!r.errorMessage.empty()) LOGE("JVM 启动失败: %s", r.errorMessage.c_str());
        g_jvmExitCode.store(r.exitCode);
        g_jvmThreadDone.store(true);
        delete a;
    });
    g_jvmThread.detach();
    // JVM 线程就绪后，把 GC/JIT 服务线程迁到小核（原 nativeCreateJvm 的行为）
    if (lmc::isJvmAlive()) lmc::bindJvmServiceThreadsToLittleCores();
}

JNIEXPORT jint JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeWaitJvmExit(JNIEnv*, jobject) {
    while (!g_jvmThreadDone.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    return g_jvmExitCode.load();
}

JNIEXPORT jint JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeCreateJvm(
        JNIEnv* env, jobject, jstring javaHome, jobjectArray jvmArgs,
        jstring mainClass, jobjectArray mainArgs) {
    const std::string home = toStdString(env, javaHome);
    const std::vector<std::string> args = toStringVector(env, jvmArgs);
    const std::string cls = toStdString(env, mainClass);
    const std::vector<std::string> margs = toStringVector(env, mainArgs);

    // 环境变量必须先于 CreateJavaVM 布置（影响 JVM 内部库解析）
    lmc::setupJvmProcessEnvironment(home);

    const lmc::JvmLaunchResult r = lmc::createJvmAndRunMain(home, args, cls, margs);
    if (!r.errorMessage.empty()) LOGE("JVM 启动失败: %s", r.errorMessage.c_str());

    // JVM 线程全部就绪后，把 GC/JIT 服务线程迁到小核
    if (lmc::isJvmAlive()) lmc::bindJvmServiceThreadsToLittleCores();
    return r.exitCode;
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeDestroyJvm(JNIEnv*, jobject) {
    return lmc::destroyJvm() ? JNI_TRUE : JNI_FALSE;
}

// ------------------------------------------------------------------
// 渲染层
// ------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeApplyRendererEnv(
        JNIEnv* env, jobject, jint mode, jstring runtimeDir) {
    return lmc::applyRendererEnvironment(mode, toStdString(env, runtimeDir)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeAttachNativeSurface(
        JNIEnv*, jobject, jlong winPtr, jint width, jint height) {
    return lmc::attachNativeSurface(reinterpret_cast<void*>(winPtr), width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeAttachJavaSurface(
        JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    return lmc::attachJavaSurface(env, surface, width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeDetachSurface(JNIEnv*, jobject) {
    lmc::detachSurface();
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativePipelineCacheWarm(JNIEnv*, jobject, jint mode) {
    // VulkanNative 模式：swapchain cache 文件存在即命中
    if (mode == 1) {
        const std::string path = lmc::runtime().cacheDir + "/vulkan_pipeline_cache.bin";
        struct stat st{};
        return stat(path.c_str(), &st) == 0 && st.st_size > 0 ? JNI_TRUE : JNI_FALSE;
    }
    // Zink 模式：Mesa 自带 shader cache（MESA_SHADER_CACHE_DIR），存在即命中
    const std::string mesaCache = lmc::runtime().cacheDir + "/mesa_shader_cache";
    struct stat st{};
    return stat(mesaCache.c_str(), &st) == 0 ? JNI_TRUE : JNI_FALSE;
}

// ------------------------------------------------------------------
// 输入层
// ------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeInjectKey(JNIEnv*, jobject, jint keyCode, jboolean pressed) {
    lmc::inputHub().injectKey(keyCode, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeInjectPointer(
        JNIEnv*, jobject, jint action, jfloat x, jfloat y, jint button) {
    lmc::inputHub().injectPointer(action, x, y, button);
}

// ------------------------------------------------------------------
// 音频层
// ------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeInitAudio(JNIEnv*, jobject) {
    return lmc::initAudioBackend() ? JNI_TRUE : JNI_FALSE;
}

// ------------------------------------------------------------------
// 世界存档 mmap
// ------------------------------------------------------------------

JNIEXPORT jobject JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeOpenWorldMmap(JNIEnv* env, jobject, jstring path) {
    const std::string p = toStdString(env, path);
    lmc::WorldMmap mmap{};
    if (!lmc::openWorldMmap(p.c_str(), &mmap)) return nullptr;

    jobject buf = env->NewDirectByteBuffer(mmap.addr, static_cast<jlong>(mmap.size));
    if (!buf) {
        lmc::closeWorldMmap(&mmap);
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_mmapMutex);
    g_mmaps[mmap.addr] = mmap;
    return buf;
}

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeCloseWorldMmap(JNIEnv* env, jobject, jobject buffer) {
    if (!buffer) return JNI_FALSE;
    const void* addr = env->GetDirectBufferAddress(buffer);
    if (!addr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mmapMutex);
    auto it = g_mmaps.find(addr);
    if (it == g_mmaps.end()) return JNI_FALSE;
    lmc::closeWorldMmap(&it->second);
    g_mmaps.erase(it);
    return JNI_TRUE;
}

// ------------------------------------------------------------------
// GraalVM Native Image（预留路径）
// ------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeNativeImageAvailable(JNIEnv*, jobject) {
    return lmc::nativeImageAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeLaunchNativeImage(
        JNIEnv* env, jobject, jobjectArray args) {
    const std::vector<std::string> gameArgs = toStringVector(env, args);
    const lmc::JvmLaunchResult r = lmc::launchNativeImage(gameArgs);
    if (!r.errorMessage.empty()) LOGE("native image 启动失败: %s", r.errorMessage.c_str());
    return r.exitCode;
}

// ------------------------------------------------------------------
// 游戏 natives 预加载（必须经 JNI 调用栈：与 libjvm 同 namespace）
// ------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativePreloadGameNatives(
        JNIEnv* env, jobject, jstring nativesDir) {
    const std::string dir = toStdString(env, nativesDir);
    // 依赖序显式表（Zalith/FCL 组件）；遗漏的依赖会以 dlopen 报错形式出现在日志
    const std::vector<std::string> order = {
        "libbytehook.so", "libandroidnsbypass.so", "libfcl.so", "libpojavexec.so",
        "liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so",
        "liblwjgl_tinyfd.so", "liblwjgl_nanovg.so", "liblwjgl_vma.so",
        "libshaderc.so", "libfreetype.so", "libopenal.so", "libgl4es_114.so",
    };
    int ok = 0, fail = 0;
    for (const auto& name : order) {
        if (dlopen((dir + "/" + name).c_str(), RTLD_NOW | RTLD_GLOBAL)) {
            ++ok;
        } else {
            ++fail;
            LOGW("游戏 natives 预加载 %s 失败: %s", name.c_str(), dlerror());
        }
    }
    LOGI("游戏 natives 预加载完成: 成功 %d, 失败 %d（本 namespace 对 HotSpot 可见）", ok, fail);
    return fail == 0 ? JNI_TRUE : JNI_FALSE;
}

// ------------------------------------------------------------------
// Zalith 窗口注入：直接 dlsym 调用 pojavexec 的 C 符号
// （绕过 ART 的 native 方法惰性解析——ZLBridge 类的方法绑定链
//   在 launcher 进程的 dex/JVM 混合环境下不可靠，真机踩坑）
// ------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeZalithSetupBridgeWindow(
        JNIEnv* env, jobject, jstring nativesDir, jobject surface) {
    const std::string dir = toStdString(env, nativesDir);
    // 绝对路径 dlopen：与 nativePreloadGameNatives 返回同一 handle（文件级复用）
    void* handle = dlopen((dir + "/libpojavexec.so").c_str(), RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        LOGE("Zalith 窗口注入: libpojavexec 未预加载（RTLD_NOLOAD 空）");
        return;
    }
    auto fn = reinterpret_cast<void (*)(JNIEnv*, jclass, jobject)>(
        dlsym(handle, "Java_com_movtery_zalithlauncher_bridge_ZLBridge_setupBridgeWindow"));
    if (!fn) {
        LOGE("Zalith 窗口注入: setupBridgeWindow 符号未找到: %s", dlerror());
        return;
    }
    jclass clazz = env->FindClass("com/movtery/zalithlauncher/bridge/ZLBridge");
    if (!clazz) {
        env->ExceptionClear();
        LOGE("Zalith 窗口注入: FindClass ZLBridge 失败");
        return;
    }
    fn(env, clazz, surface);
    env->DeleteLocalRef(clazz);
    LOGI("Zalith 窗口注入: setupBridgeWindow 调用完成");
}

} // extern "C"
