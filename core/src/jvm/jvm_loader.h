// JVM 运行时加载器：dlopen 直载 OpenJDK 21 (aarch64) 的 libjvm.so，
// 无 proot、无 chroot —— 进程内直接 JNI_CreateJavaVM。
#pragma once

#include <string>
#include <vector>

namespace lmc {

/** JVM 创建结果 */
struct JvmLaunchResult {
    int exitCode = -1;        // 游戏 main 的返回值；<0 表示 JVM 创建失败
    std::string errorMessage; // 失败原因（日志已打，此处供 UI 展示）
};

/**
 * 创建 JVM 并在当前线程调用 mainClass.main(mainArgs)，阻塞至游戏退出。
 *
 * 执行序列：
 *  1. dlopen("<javaHome>/lib/server/libjvm.so", RTLD_NOW | RTLD_GLOBAL)
 *  2. dlsym 取 JNI_GetDefaultJavaVMInitArgs / JNI_CreateJavaVM
 *  3. JNI_CreateJavaVM（参数由 jvm_options::buildJvmOptions 提供）
 *  4. 设置上下文类加载器（Minecraft 启动依赖）
 *  5. FindClass + GetStaticMethodID("main") + CallStaticVoidMethod
 *  6. DestroyJavaVM
 *
 * 调用线程应先绑定大核（sched/thread_affinity），它就是游戏主线程。
 */
JvmLaunchResult createJvmAndRunMain(const std::string& javaHome,
                                    const std::vector<std::string>& jvmArgs,
                                    const std::string& mainClass,
                                    const std::vector<std::string>& mainArgs);

/** 请求销毁 JVM（游戏异常退出后的清理路径） */
bool destroyJvm();

/** JVM 是否存活 */
bool isJvmAlive();

} // namespace lmc
