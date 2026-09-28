#include "render_bridge.h"
#include "common/log.h"
#include "core_runtime.h"
#include "vulkan_swapchain.h"
#include "gl4es_loader.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstdlib>
#include <memory>
#include <mutex>

namespace lmc {

namespace {

struct RenderContext {
    std::mutex mutex;
    int mode = 0;                       // RenderMode
    ANativeWindow* window = nullptr;    // 持有的窗口引用
    std::unique_ptr<VulkanSwapchain> swapchain; // VulkanNative 模式使用
    float renderScale = 1.0f;           // 热保护降分辨率
};

RenderContext& ctx() {
    static RenderContext c;
    return c;
}

// 向已有 LD_LIBRARY_PATH 前置追加目录（后加入的目录优先查找）
void prependLdLibraryPath(const std::string& dir) {
    const char* existing = getenv("LD_LIBRARY_PATH");
    std::string next = dir;
    if (existing && *existing) next += ":" + std::string(existing);
    setenv("LD_LIBRARY_PATH", next.c_str(), 1);
}

} // namespace

bool applyRendererEnvironment(int mode, const std::string& runtimeDir) {
    ctx().mode = mode;
    const std::string mesaDir = runtimeDir + "/runtime/mesa";
    const std::string gl4esDir = runtimeDir + "/runtime/gl4es";

    switch (static_cast<RenderMode>(mode)) {
        case RenderMode::Zink: {
            // ---- 主路径：Mesa Zink + Turnip ----
            prependLdLibraryPath(mesaDir);
            // Gallium 驱动选择：Zink（GL on Vulkan）
            setenv("GALLIUM_DRIVER", "zink", 1);
            // Turnip ICD：让 Vulkan loader 加载我们的开源驱动而不是系统闭源驱动
            setenv("VK_ICD_FILENAMES", (mesaDir + "/turnip_icd.aarch64.json").c_str(), 1);
            setenv("MESA_VK_ICD_FILENAMES", (mesaDir + "/turnip_icd.aarch64.json").c_str(), 1);
            // Zink 已知调优开关（社区验证集，见 mesa/src/gallium/drivers/zink）
            setenv("ZINK_DESCRIPTORS", "lazy", 1);      // 描述符惰性分配，降低 CPU 开销
            setenv("ZINK_DEBUG", "notexrect", 0);       // MC 贴图矩形兼容
            setenv("MESA_GL_VERSION_OVERRIDE", "4.6FC", 1); // 上报 4.6 兼容 profile，解锁 MC 完整特性
            setenv("mesa_no_error", "1", 1);            // 关闭 GL 错误检查（性能优先）
            LOGI("渲染环境: Zink(%s) + Turnip", mesaDir.c_str());
            break;
        }
        case RenderMode::VulkanNative: {
            // ---- 备选：原生 Vulkan 渲染器（VulkanMod 思路）----
            // 游戏侧渲染器为原生 Vulkan；swapchain 由 attach*Surface 建立
            setenv("LMC_RENDERER", "vulkan_native", 1);
            LOGI("渲染环境: 原生 Vulkan（swapchain 由 lmc_core 托管）");
            break;
        }
        case RenderMode::GL4ES: {
            // ---- 保底：裁剪版 gl4es ----
            prependLdLibraryPath(gl4esDir);
            setenv("LIBGL_ES", "2", 1);        // GLES2 后端
            setenv("LIBGL_GL", "21", 1);       // 上报 GL 2.1
            setenv("LIBGL_MIPMAP", "3", 1);    // 自动 mipmap
            setenv("LIBGL_NOINTOVLHACK", "1", 1);
            if (!ensureGl4esLoaded(gl4esDir)) {
                LOGW("gl4es 预加载失败，运行时由 LWJGL 侧按 java.library.path 解析");
            }
            LOGI("渲染环境: gl4es(%s)", gl4esDir.c_str());
            break;
        }
        default:
            LOGE("未知渲染模式 %d", mode);
            return false;
    }
    return true;
}

bool attachJavaSurface(JNIEnv* env, jobject surface, int width, int height) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) {
        LOGE("ANativeWindow_fromSurface 失败");
        return false;
    }
    const bool ok = attachNativeSurface(win, width, height);
    ANativeWindow_release(win); // attachNativeSurface 内部 Acquire 持有自己的引用
    return ok;
}

bool attachNativeSurface(void* anativeWindow, int width, int height) {
    std::lock_guard<std::mutex> lock(ctx().mutex);
    auto* win = static_cast<ANativeWindow*>(anativeWindow);
    if (ctx().window) ANativeWindow_release(ctx().window);
    ctx().window = win; // attachNativeSurface 持有一份引用
    ANativeWindow_acquire(win);

    if (static_cast<RenderMode>(ctx().mode) == RenderMode::VulkanNative) {
        // 原生 Vulkan 路径：建立完整 swapchain
        if (!ctx().swapchain) ctx().swapchain = std::make_unique<VulkanSwapchain>();
        if (!ctx().swapchain->instance()) {
            const std::string cachePath = runtime().cacheDir + "/vulkan_pipeline_cache.bin";
            if (!ctx().swapchain->init(win, cachePath)) {
                LOGE("Vulkan swapchain 初始化失败");
                return false;
            }
        } else {
            ctx().swapchain->recreate(win);
        }
    }
    LOGI("渲染窗口已挂载: %dx%d", width, height);
    return true;
}

void detachSurface() {
    std::lock_guard<std::mutex> lock(ctx().mutex);
    if (ctx().swapchain) {
        ctx().swapchain->destroy();
        ctx().swapchain.reset();
    }
    if (ctx().window) {
        ANativeWindow_release(ctx().window);
        ctx().window = nullptr;
    }
    LOGI("渲染窗口已卸载");
}

int currentRenderMode() { return ctx().mode; }

void notifyThermalThrottle(int severity) {
    std::lock_guard<std::mutex> lock(ctx().mutex);
    // severity: 1 = 降一档；2 = 降两档
    ctx().renderScale = (severity >= 2) ? 0.5f : 0.75f;
    LOGI("热保护: 渲染分辨率缩放 -> %.2f", ctx().renderScale);
}

float currentRenderScale() {
    std::lock_guard<std::mutex> lock(ctx().mutex);
    return ctx().renderScale;
}

} // namespace lmc
