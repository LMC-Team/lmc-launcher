// 输入中枢：所有输入事件（Compose 悬浮层 JNI 注入 / AInputQueue native 直读）的汇聚点。
// 下游消费者：
//  - LWJGL/GLFW 适配层（Zink 与 gl4es 路径的键鼠注入）
//  - 原生 Vulkan 渲染器（直接拉取事件结构体，零 Java 参与）
#pragma once

#include <cstdint>
#include <deque>
#include <mutex>
#include <optional>

namespace lmc {

/** 统一输入事件（GLFW 语义） */
struct InputEvent {
    enum class Type : int {
        Key = 0,        // keyCode / pressed
        PointerMove,    // dx / dy（相对位移，虚拟鼠标区产生）
        MouseButton,    // button: 0=左 1=右 2=中 / pressed
    };

    Type type;
    int keyCode = 0;
    bool pressed = false;
    float dx = 0.0f, dy = 0.0f; // PointerMove 用
    int button = 0;
};

class InputHub {
public:
    /** GLFW 键码容量上限（keyState 数组大小） */
    static constexpr int kKeyStateSize = 512;

    /** 按键注入（GLFW 键码） */
    void injectKey(int keyCode, bool pressed);

    /** 指针事件注入。action: 0=down 1=up 2=move；button: 0=左 1=右 2=中 */
    void injectPointer(int action, float x, float y, int button);

    /** 拉取一个事件（原生消费者线程调用；无事件返回 nullopt） */
    std::optional<InputEvent> pollEvent();

    /** 键是否处于按下状态（GLFW 适配层查询窗口） */
    bool isKeyDown(int keyCode) const;

    /** 清空队列（surface 重建等场景） */
    void clear();

private:
    mutable std::mutex mutex_;
    std::deque<InputEvent> queue_;
    bool keyState_[kKeyStateSize] = {};
};

InputHub& inputHub();

} // namespace lmc
