// 渲染桥接：三种渲染后端路径的统一入口
//
//  ZINK（主路径）   : MC(GL) → Mesa Zink → Turnip/厂商 Vulkan → ANativeWindow
//                     本模块只布置环境变量与 ICD，GL 语义由 Mesa 内部完成；
//  VULKAN_NATIVE    : MC(原生 Vulkan 渲染器) → 本模块的 VulkanSwapchain → ANativeWindow
//  GL4ES（保底）    : MC(GL) → 裁剪版 gl4es → 系统 EGL/GLES → ANativeWindow
#pragma once

#include <jni.h>
#include <string>

namespace lmc {

/**
 * 布置渲染环境变量。必须在创建 JVM 之前调用（LD_LIBRARY_PATH 影响后续所有 dlopen）。
 * @param mode RenderMode 枚举值（0/1/2）
 * @param runtimeDir 应用 files 目录（内含 runtime/mesa、runtime/gl4es 等）
 */
bool applyRendererEnvironment(int mode, const std::string& runtimeDir);

/** SurfaceView 路径：从 java Surface 获取 ANativeWindow 并初始化显示链路 */
bool attachJavaSurface(JNIEnv* env, jobject surface, int width, int height);

/** NativeActivity 路径：直接使用框架下发的 ANativeWindow* */
bool attachNativeSurface(void* anativeWindow, int width, int height);

/** surface 销毁：释放 swapchain 与窗口引用 */
void detachSurface();

/** 当前渲染模式（枚举值） */
int currentRenderMode();

/** 热保护联动：温度预警回调调用，降低渲染分辨率缩放（原生 Vulkan 渲染器消费） */
void notifyThermalThrottle(int severity);

/** 当前渲染分辨率缩放（1.0 = 全分辨率） */
float currentRenderScale();

} // namespace lmc
