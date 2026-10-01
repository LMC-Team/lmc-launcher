package com.movtery.zalithlauncher.bridge;

import android.content.Context;

import androidx.annotation.Keep;

/**
 * Zalith 2 完整版原生桥（dex 契约，签名从 ZalithLauncher 源码对齐）。
 *
 * Zalith 版 libpojavexec.so 的 JNI 导出指向本类：
 *   Java_com_movtery_zalithlauncher_bridge_ZLBridge_*
 *
 * 分组：
 *  - AWT 输入事件：sendInputData 统一事件流（EVENT_TYPE_* 与 CallbackBridge 一致）
 *  - Launch：setLdLibraryPath / dlopen（pojavexec 的 native dlopen——其 namespace
 *    被 HotSpot 继承，JRE 链与引擎 so 必须经此加载）
 *  - Render：setupBridgeWindow / releaseBridgeWindow / moveWindow / renderAWTScreenFrame
 *  - Input：sendInputData / clipboardReceived（awt 桥回调目标）
 *  - Utils：chdir
 */
@Keep
public final class ZLBridge {
    // AWT 事件类型（与 org.lwjgl.glfw.CallbackBridge 一致）
    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_CURSOR_POS = 1003;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;

    public static void sendKey(char keychar, int keycode) {
        // TODO: Android -> AWT keycode mapping
        sendInputData(EVENT_TYPE_KEY, (int) keychar, keycode, 1, 0);
        sendInputData(EVENT_TYPE_KEY, (int) keychar, keycode, 0, 0);
    }

    public static void sendKey(char keychar, int keycode, int state) {
        sendInputData(EVENT_TYPE_KEY, (int) keychar, keycode, state, 0);
    }

    public static void sendChar(char keychar) {
        sendInputData(EVENT_TYPE_CHAR, (int) keychar, 0, 0, 0);
    }

    public static void sendMousePress(int awtButtons, boolean isDown) {
        sendInputData(EVENT_TYPE_MOUSE_BUTTON, awtButtons, isDown ? 1 : 0, 0, 0);
    }

    public static void sendMousePress(int awtButtons) {
        sendMousePress(awtButtons, true);
        sendMousePress(awtButtons, false);
    }

    public static void sendMousePos(int x, int y) {
        sendInputData(EVENT_TYPE_CURSOR_POS, x, y, 0, 0);
    }

    // Game
    @Keep public static native void initializeGameExitHook();

    @Keep public static native void setupExitMethod(Context context);

    // Launch
    @Keep public static native void setLdLibraryPath(String ldLibraryPath);

    @Keep public static native boolean dlopen(String libPath);

    // Render
    @Keep public static native void setupBridgeWindow(Object surface);

    @Keep public static native void releaseBridgeWindow();

    @Keep public static native void moveWindow(int xOffset, int yOffset);

    @Keep public static native int[] renderAWTScreenFrame();

    // Input
    @Keep public static native void sendInputData(int type, int i1, int i2, int i3, int i4);

    @Keep public static native void clipboardReceived(String data, String mimeTypeSub);

    // Utils
    @Keep public static native int chdir(String path);

    static {
        NativeLibraryLoader.loadExitHookLib();
        NativeLibraryLoader.loadPojavLib();
        NativeLibraryLoader.loadPojavAWTLib();
    }
}
