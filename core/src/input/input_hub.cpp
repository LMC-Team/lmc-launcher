#include "input_hub.h"

namespace lmc {

void InputHub::injectKey(int keyCode, bool pressed) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (keyCode >= 0 && keyCode < kKeyStateSize) keyState_[keyCode] = pressed;
    queue_.push_back({InputEvent::Type::Key, keyCode, pressed});
}

void InputHub::injectPointer(int action, float x, float y, int button) {
    std::lock_guard<std::mutex> lock(mutex_);
    switch (action) {
        case 0: // down
            queue_.push_back({InputEvent::Type::MouseButton, 0, true, x, y, button});
            break;
        case 1: // up
            queue_.push_back({InputEvent::Type::MouseButton, 0, false, x, y, button});
            break;
        case 2: // move（相对位移，由虚拟鼠标区/触摸板产生）
            queue_.push_back({InputEvent::Type::PointerMove, 0, false, x, y, button});
            break;
        default:
            break;
    }
}

std::optional<InputEvent> InputHub::pollEvent() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (queue_.empty()) return std::nullopt;
    InputEvent ev = queue_.front();
    queue_.pop_front();
    return ev;
}

bool InputHub::isKeyDown(int keyCode) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return keyCode >= 0 && keyCode < kKeyStateSize && keyState_[keyCode];
}

void InputHub::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    queue_.clear();
}

InputHub& inputHub() {
    static InputHub hub;
    return hub;
}

} // namespace lmc
