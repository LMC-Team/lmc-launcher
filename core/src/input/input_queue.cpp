#include "input_queue.h"
#include "common/log.h"
#include "input_hub.h"

#include <android/input.h>
#include <android/looper.h>
#include <android/native_activity.h>
#include <jni.h>

namespace lmc {

namespace {

constexpr int kLooperIdent = 0x4C4D43; // 'LMC'

ANativeActivity* g_activity = nullptr;
AInputQueue* g_queue = nullptr;

// GLFW 键码与 Android 键码不同；此处映射高频游戏键，其余按 AKEYCODE 数值透传
int toGlfwKeyCode(int androidKeyCode) {
    switch (androidKeyCode) {
        case AKEYCODE_SPACE: return 32;       // GLFW_KEY_SPACE
        case AKEYCODE_SHIFT_LEFT:
        case AKEYCODE_SHIFT_RIGHT: return 340; // GLFW_KEY_LEFT_SHIFT
        case AKEYCODE_ESCAPE: return 256;      // GLFW_KEY_ESCAPE
        case AKEYCODE_ENTER: return 257;       // GLFW_KEY_ENTER
        case AKEYCODE_TAB: return 258;         // GLFW_KEY_TAB
        case AKEYCODE_DEL: return 259;         // GLFW_KEY_BACKSPACE（Android 键码 DEL）
        case AKEYCODE_W: return 87;
        case AKEYCODE_A: return 65;
        case AKEYCODE_S: return 83;
        case AKEYCODE_D: return 68;
        case AKEYCODE_E: return 69;
        case AKEYCODE_Q: return 81;
        case AKEYCODE_F: return 70;
        case AKEYCODE_T: return 84;
        case AKEYCODE_1: return 49;
        case AKEYCODE_2: return 50;
        case AKEYCODE_3: return 51;
        default: return androidKeyCode; // 透传（GLFW 适配层再做一次完整映射）
    }
}

// BACK 键：回调 GameActivity.onNativeToggleOverlay() 呼出/隐藏悬浮控制层
void handleBackKey() {
    ANativeActivity* activity = nativeActivity();
    if (!activity || !activity->vm || !activity->clazz) return;
    JavaVM* vm = activity->vm;
    JNIEnv* env = nullptr;
    const bool needDetach = (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK);
    if (needDetach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;

    jclass clazz = env->GetObjectClass(activity->clazz);
    jmethodID toggle = env->GetMethodID(clazz, "onNativeToggleOverlay", "()V");
    if (toggle) env->CallVoidMethod(activity->clazz, toggle);
    env->DeleteLocalRef(clazz);

    if (needDetach) vm->DetachCurrentThread();
}

// 处理单个 AInputEvent → InputHub
bool processEvent(const AInputEvent* event) {
    const int32_t type = AInputEvent_getType(event);

    if (type == AINPUT_EVENT_TYPE_KEY) {
        const int32_t action = AKeyEvent_getAction(event);
        const int32_t code = AKeyEvent_getKeyCode(event);

        // BACK 键属于 UI 控制语义，直接回调 Java，不进游戏输入流
        if (code == AKEYCODE_BACK && action == AKEY_EVENT_ACTION_DOWN) {
            handleBackKey();
            return true;
        }
        inputHub().injectKey(toGlfwKeyCode(code), action == AKEY_EVENT_ACTION_DOWN);
        return true;
    }

    if (type == AINPUT_EVENT_TYPE_MOTION) {
        const int32_t action = AMotionEvent_getAction(event) & AMOTION_EVENT_ACTION_MASK;
        const float x = AMotionEvent_getX(event, 0);
        const float y = AMotionEvent_getY(event, 0);
        switch (action) {
            case AMOTION_EVENT_ACTION_DOWN:
                inputHub().injectPointer(0, x, y, 0);
                break;
            case AMOTION_EVENT_ACTION_UP:
                inputHub().injectPointer(1, x, y, 0);
                break;
            case AMOTION_EVENT_ACTION_MOVE: {
                // 多点触控取主指针历史，逐段换算位移（精确对齐 MC 鼠标灵敏度）
                const size_t history = AMotionEvent_getHistorySize(event);
                float prevX = AMotionEvent_getHistoricalX(event, 0, 0);
                float prevY = AMotionEvent_getHistoricalY(event, 0, 0);
                for (size_t h = 0; h < history; ++h) {
                    const float hx = AMotionEvent_getHistoricalX(event, 0, h);
                    const float hy = AMotionEvent_getHistoricalY(event, 0, h);
                    inputHub().injectPointer(2, hx - prevX, hy - prevY, 0);
                    prevX = hx; prevY = hy;
                }
                inputHub().injectPointer(2, x - prevX, y - prevY, 0);
                break;
            }
            default:
                break;
        }
        return true;
    }
    return false;
}

// ALooper 回调：排空 AInputQueue
int looperCallback(int /*fd*/, int /*events*/, void* data) {
    AInputQueue* queue = static_cast<AInputQueue*>(data);
    while (AInputQueue_hasEvents(queue) > 0) {
        AInputEvent* event = nullptr;
        if (AInputQueue_getEvent(queue, &event) < 0) continue;
        const bool handled = processEvent(event);
        AInputQueue_finishEvent(queue, event, handled ? 1 : 0);
    }
    return 1; // 继续监听
}

} // namespace

void setNativeActivity(ANativeActivity* activity) { g_activity = activity; }
ANativeActivity* nativeActivity() { return g_activity; }

void onInputQueueCreated(AInputQueue* queue) {
    g_queue = queue;
    // 在当前线程（主线程）的 looper 上注册；事件到达即触发 looperCallback
    AInputQueue_attachLooper(queue, ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS),
                             kLooperIdent, looperCallback, queue);
    LOGI("AInputQueue 已挂载（native 直读路径）");
}

void onInputQueueDestroyed(AInputQueue* queue) {
    AInputQueue_detachLooper(queue);
    g_queue = nullptr;
    inputHub().clear();
    LOGI("AInputQueue 已卸载");
}

} // namespace lmc
