// GraalVM Native Image AOT 路径（预留接口，“极致性能模式”）
//
// 目标形态：
//   用 GraalVM native-image 把“客户端 + LMC 原生渲染适配层”提前 AOT 编译为
//   独立可执行文件 / 共享库，游戏启动 = 直接 exec / dlopen，完全跳过
//   JVM 启动、类加载与 JIT 预热，冷启动 < 200ms。
//
// 前置条件（scripts/ 与 CI 承担）：
//   1. GraalVM for JDK 21（https://github.com/graalvm/graalvm-ce-builds）
//   2. 客户端闭源依赖通过 -H:+ReportUnsupportedElementsAtRuntime 或
//      reachability metadata（https://github.com/oracle/graalvm-reachability-metadata）解决
//   3. LWJGL 需替换为 lmc 原生渲染适配层（VulkanMod 思路），避免 JNI 动态注册
#pragma once

#include <string>
#include <vector>
#include "jvm/jvm_loader.h"

namespace lmc {

/** 探测已部署的 native image 产物（<filesDir>/runtime/graal/lmc-native） */
bool nativeImageAvailable();

/** native image 产物路径 */
std::string nativeImagePath();

/**
 * 以 AOT 模式启动游戏：fork + exec native image 产物，等待退出。
 * 未部署产物时返回 false（UI 层回落到 JVM 路径）。
 */
JvmLaunchResult launchNativeImage(const std::vector<std::string>& gameArgs);

} // namespace lmc
