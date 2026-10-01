package org.lwjgl.glfw;

import static com.movtery.zalithlauncher.bridge.ZLBridgeStatesKt.CURSOR_DISABLED;
import static com.movtery.zalithlauncher.bridge.ZLBridgeStatesKt.CURSOR_ENABLED;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.movtery.zalithlauncher.bridge.CursorShape;
import com.movtery.zalithlauncher.bridge.NativeLibraryLoader;
import com.movtery.zalithlauncher.bridge.ZLBridgeStates;
import com.movtery.zalithlauncher.bridge.ZLNativeInvoker;

import java.util.function.Consumer;

import dalvik.annotation.optimization.CriticalNative;

/**
 * Zalith 2 dex 版 GLFW 桥（对齐 ZalithLauncher 模块同名类，GPL-3.0）。
 *
 * 必须打进 launcher dex：Zalith 版 libpojavexec 的 JNI_OnLoad 在 ART 侧
 * RegisterNatives 本类的 nativeSendXXX 系列（input_bridge_v3.c 导出），
 * 并在 HotSpot/JRE 侧回调 accessAndroidClipboard/onGrabStateChanged/
 * onCursorShapeChanged/onGraphicOutput。
 *
 * 注意与 lwjgl-glfw-classes.jar（HotSpot classpath，LWJGL 模块精简版）
 * 同名不同空间：dex 服务 ART 桥接，jar 服务 MC 的 GLFW 绑定，互不冲突。
 *
 * 裁剪说明（相对 zl2 原版）：
 *  - LwjglGlfwKeycode 用 net.kdt.pojavlaunch 包（常量值与 GLFW 标准一致）
 *  - BuildKeys.LAUNCHER_IDENTIFIER → "lmc"
 *  - ContextsKt.getGlobalContext() → 注入的 applicationContext
 */
@Keep
public class CallbackBridge {
    private static final int GLFW_IBEAM_CURSOR = 0x36002;
    private static final int GLFW_HAND_CURSOR = 0x36004;
    private static final int GLFW_CROSSHAIR_CURSOR = 0x36003;
    private static final int GLFW_RESIZE_NS_CURSOR = 0x36006;
    private static final int GLFW_RESIZE_EW_CURSOR = 0x36005;
    private static final int GLFW_RESIZE_ALL_CURSOR = 0x36009;
    private static final int GLFW_NOT_ALLOWED_CURSOR = 0x3600A;
    private static final int GLFW_ARROW_CURSOR = 0x36001;

    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static boolean isGrabbing = false;
    private static final Consumer<Boolean> grabListener = grabbing ->
            ZLBridgeStates.changeCursorMode(grabbing ? CURSOR_DISABLED : CURSOR_ENABLED);

    private static int cursorShape = GLFW_ARROW_CURSOR;
    private static final Consumer<CursorShape> cursorShapeListener = ZLBridgeStates::changeCursorShape;

    /** 由 LmcApplication 注入（剪贴板服务获取用） */
    @Nullable
    public static volatile Context appContext;

    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    /** 游戏画面首帧回调（pojavexec 的 calculateFPS 触发 onGraphicOutput） */
    public interface GraphicOutputListener {
        void onGraphicOutput();
    }

    private static volatile GraphicOutputListener sGraphicOutputListener;

    public static void setGraphicOutputListener(GraphicOutputListener listener) {
        sGraphicOutputListener = listener;
    }

    public static void putMouseEventWithCoords(int button, float x, float y) {
        sendCursorPos(x, y);
        putMouseEvent(button);
    }

    public static void putMouseEvent(int button) {
        putMouseEvent(button, true);
        sChoreographer.postFrameCallbackDelayed(l -> putMouseEvent(button, false), 33);
    }

    public static void putMouseEvent(int button, boolean isDown) {
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(mouseX, mouseY);
    }

    public static void sendCursorDelta(float x, float y) {
        sendCursorPos(mouseX + x, mouseY + y);
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        if (keycode != 0) nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        if (isDown && !Character.isISOControl(keychar)) {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    public static void sendChar(char keychar, int modifiers) {
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        CallbackBridge.sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        // Avoid going through the JNI each time.
        return isGrabbing;
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        ClipboardManager clipboard = appContext == null
                ? null
                : (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return "";
        String result = null;
        switch (type) {
            case CLIPBOARD_COPY: {
                ClipData clip = ClipData.newPlainText("lmc", copy);
                clipboard.setPrimaryClip(clip);
                break;
            }
            case CLIPBOARD_PASTE:
                if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    result = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                } else {
                    result = "";
                }
                break;
            case CLIPBOARD_OPEN:
                ZLNativeInvoker.openLink(copy);
                break;
        }
        return result;
    }

    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_MOD_ALT;
        }
        if (holdingCapslock) {
            currMods |= net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        }
        if (holdingCtrl) {
            currMods |= net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        }
        if (holdingNumlock) {
            currMods |= net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        }
        if (holdingShift) {
            currMods |= net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                return;

            case net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                return;

            case net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                return;

            case net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                return;

            case net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
        }
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            // If the grab re-changed, skip notify process
            if (isGrabbing != grabbing) return;

            synchronized (grabListener) {
                grabListener.accept(isGrabbing);
            }
        }, 16);
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onCursorShapeChanged(final int shape) {
        cursorShape = shape;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            if (cursorShape != shape) return;

            synchronized (cursorShapeListener) {
                CursorShape shape1;
                switch (cursorShape) {
                    case GLFW_IBEAM_CURSOR:
                        shape1 = CursorShape.IBeam;
                        break;
                    case GLFW_HAND_CURSOR:
                        shape1 = CursorShape.Hand;
                        break;
                    case GLFW_CROSSHAIR_CURSOR:
                        shape1 = CursorShape.CrossHair;
                        break;
                    case GLFW_RESIZE_NS_CURSOR:
                        shape1 = CursorShape.ResizeNS;
                        break;
                    case GLFW_RESIZE_EW_CURSOR:
                        shape1 = CursorShape.ResizeEW;
                        break;
                    case GLFW_RESIZE_ALL_CURSOR:
                        shape1 = CursorShape.ResizeAll;
                        break;
                    case GLFW_NOT_ALLOWED_CURSOR:
                        shape1 = CursorShape.NotAllowed;
                        break;
                    case GLFW_ARROW_CURSOR:
                    default:
                        shape1 = CursorShape.Arrow;
                }

                cursorShapeListener.accept(shape1);
            }
        }, 16);
    }

    // Called from JRE side（游戏首帧输出）
    // 方法名必须保持 onGraphicOutput：pojavexec input_bridge 的 JNI_OnLoad 会
    // GetStaticMethodID 查找它，缺失会 pending NoSuchMethodError 导致后续
    // FindClass abort（真机 2026-10-01）。回调内容保持 no-op 安全。
    @SuppressWarnings("unused")
    @Keep
    private static void onGraphicOutput() {
        GraphicOutputListener listener = sGraphicOutputListener;
        if (listener != null) {
            listener.onGraphicOutput();
        }
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native int getCurrentFps();

    static {
        NativeLibraryLoader.loadPojavLib();
    }
}
