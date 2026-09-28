// NativeActivity 胶水层：ANativeActivity_onCreate 入口与生命周期回调。
// GameActivity（Kotlin, extends NativeActivity）在 Manifest 中声明
// android.app.lib_name = "lmc_core"，框架将 dlopen 本库并调用此入口。
#include "common/log.h"
#include "input/input_queue.h"
#include "render/render_bridge.h"

#include <android/native_activity.h>
#include <android/native_window.h>

namespace lmc {

namespace na {

void onStart(ANativeActivity*) { LOGD("NativeActivity onStart"); }

void onResume(ANativeActivity*) { LOGD("NativeActivity onResume"); }

void onPause(ANativeActivity*) { LOGD("NativeActivity onPause"); }

void onStop(ANativeActivity*) { LOGD("NativeActivity onStop"); }

void onDestroy(ANativeActivity*) {
    detachSurface();
    setNativeActivity(nullptr);
    LOGI("NativeActivity onDestroy");
}

void onNativeWindowCreated(ANativeActivity*, ANativeWindow* window) {
    const int w = ANativeWindow_getWidth(window);
    const int h = ANativeWindow_getHeight(window);
    attachNativeSurface(window, w, h);
}

void onNativeWindowResized(ANativeActivity*, ANativeWindow* window) {
    // swapchain 重建走 VulkanSwapchain::recreate
    const int w = ANativeWindow_getWidth(window);
    const int h = ANativeWindow_getHeight(window);
    attachNativeSurface(window, w, h);
}

void onNativeWindowRedrawNeeded(ANativeActivity*, ANativeWindow*) {}

void onNativeWindowDestroyed(ANativeActivity*, ANativeWindow*) {
    detachSurface();
}

void onInputQueueCreated(ANativeActivity*, AInputQueue* queue) {
    lmc::onInputQueueCreated(queue); // native 输入直读路径
}

void onInputQueueDestroyed(ANativeActivity*, AInputQueue* queue) {
    lmc::onInputQueueDestroyed(queue);
}

void* onSaveInstanceState(ANativeActivity*, size_t* outLen) {
    *outLen = 0;
    return nullptr;
}

void onConfigurationChanged(ANativeActivity*) {}
void onLowMemory(ANativeActivity*) {}
void onContentRectChanged(ANativeActivity*, const ARect*) {}

} // namespace na

} // namespace lmc

// 框架入口：NativeActivity 通过 dlsym 查找该符号
extern "C" JNIEXPORT void ANativeActivity_onCreate(ANativeActivity* activity,
                                                   void* /*savedState*/,
                                                   size_t /*savedStateSize*/) {
    lmc::setNativeActivity(activity);
    activity->callbacks->onStart = lmc::na::onStart;
    activity->callbacks->onResume = lmc::na::onResume;
    activity->callbacks->onPause = lmc::na::onPause;
    activity->callbacks->onStop = lmc::na::onStop;
    activity->callbacks->onDestroy = lmc::na::onDestroy;
    activity->callbacks->onNativeWindowCreated = lmc::na::onNativeWindowCreated;
    activity->callbacks->onNativeWindowResized = lmc::na::onNativeWindowResized;
    activity->callbacks->onNativeWindowRedrawNeeded = lmc::na::onNativeWindowRedrawNeeded;
    activity->callbacks->onNativeWindowDestroyed = lmc::na::onNativeWindowDestroyed;
    activity->callbacks->onInputQueueCreated = lmc::na::onInputQueueCreated;
    activity->callbacks->onInputQueueDestroyed = lmc::na::onInputQueueDestroyed;
    activity->callbacks->onSaveInstanceState = lmc::na::onSaveInstanceState;
    activity->callbacks->onConfigurationChanged = lmc::na::onConfigurationChanged;
    activity->callbacks->onLowMemory = lmc::na::onLowMemory;
    activity->callbacks->onContentRectChanged = lmc::na::onContentRectChanged;
    LOGI("ANativeActivity_onCreate 完成（liblmc_core）");
}
