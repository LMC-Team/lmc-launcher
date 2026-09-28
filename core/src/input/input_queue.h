// AInputQueue 原生输入处理（NativeActivity 路径）：
// 框架把 AInputQueue 直接交给 native 层，事件处理全程不经过 Java 事件管线。
#pragma once

struct ANativeActivity;
struct AInputQueue;

namespace lmc {

/** ANativeActivity 回调注册时保存实例（BACK 键等需要回调 Java 的场景使用） */
void setNativeActivity(ANativeActivity* activity);
ANativeActivity* nativeActivity();

/** AInputQueue 生命周期（由 native_activity_glue.cpp 的 ANativeActivity 回调驱动） */
void onInputQueueCreated(AInputQueue* queue);
void onInputQueueDestroyed(AInputQueue* queue);

} // namespace lmc
