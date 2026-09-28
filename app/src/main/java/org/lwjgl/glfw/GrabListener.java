package org.lwjgl.glfw;

/** 鼠标抓取状态监听（Pojav 生态 CallbackBridge 依赖的接口） */
public interface GrabListener {
    void onGrabState(boolean grabbing);
}
