package org.lwjgl.glfw;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;




import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Choreographer sChoreographer = Choreographer.getInstance();
    /** 游戏线程写、UI 线程读的抓取状态，公开访问器也可能被其它线程调用 */
    private static volatile boolean isGrabbing = false;
    /** 启动器 Activity 引用：由 LmcPojavBridgeHelper 注入（UI 回调用） */
    public static Activity sLauncherActivity;
    /** 剪贴板管理器：由 LmcApplication 注入（accessAndroidClipboard 用） */
    public static android.content.ClipboardManager GLOBAL_CLIPBOARD;
    /** 保护 isGrabbing 的写与「检查+应用」的组合操作，防止应用过期状态 */
    private static final Object grabLock = new Object();

    private static void postFrameCallbackDelayed(Choreographer.FrameCallback callback, long delayMillis) {
        MAIN_HANDLER.post(() -> Choreographer.getInstance().postFrameCallbackDelayed(callback, delayMillis));
    }

    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    // SDL launcher integration. See the AAMC reference implementation:
    // https://github.com/AngelAuraMC/Amethyst-Android
    // Notification types
    public static final int NOTIF_TYPE_SDL = 0;

    // Notification actions
    public static final int ACTION_INIT_LAUNCHER_INTEGRATION = 0;
    public static final int ACTION_SEND_TEXTBOX_RECT = 1;

    // org.lwjgl.sdl.SDLInit 通过这两个常量调用 nativeNotifyLauncher
    public static final int SDL = NOTIF_TYPE_SDL;
    public static final int INIT = ACTION_INIT_LAUNCHER_INTEGRATION;

    /**
     * 由 JRE 侧（sdl_hook JNI）调用的通知入口。
     * @return 通知是否处理成功
     */
    @SuppressWarnings("unused")
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        // 骨架：SDL 通知/手柄集成留待渲染阶段（原实现转发给 SDLActivity）
        android.util.Log.i("LMC-CB", "notifyLauncher type=" + type);
        return false;
    }

    /**
     * org.lwjgl.sdl.SDLInit（LWJGL 3.4.1 的 SDL Java 绑定）调用的入口，转发到 {@link #notifyLauncher}。
     * 注意：LWJGL 组件内声明为 native，运行时以本实现为准（避免依赖额外 C 符号）。
     */
    @SuppressWarnings("unused")
    @Keep
    public static void nativeNotifyLauncher(int type, int... action) {
        notifyLauncher(type, action);
    }

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY, deltaX, deltaY;
    private static int sMouseButtonState = 0;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    // GLFW direct gamepad 共享缓冲
    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;
    public static boolean sGamepadDirectInput = false;
    // Use a weak reference here to avoid possibly statically referencing a Context.

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        postFrameCallbackDelayed(l -> putMouseEventWithCoords(button, false, x, y), 33);
    }

    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y /* , int dz, long nanos */) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }


    public static void sendCursorPos(float x, float y) {
        float dx = x - mouseX;
        float dy = y - mouseY;
        mouseX = x;
        mouseY = y;
        deltaX = dx;
        deltaY = dy;
        nativeSendCursorPos(mouseX, mouseY);
        if (!false) return;
        if (isGrabbing()) {
            // 鼠标被游戏捕获（锁指针转视角）时下发相对增量

        } else {

        }
    }

    public static void sendCursorDelta(float x, float y) {
        deltaX = x;
        deltaY = y;
        mouseX += x;
        mouseY += y;
        nativeSendCursorPos(mouseX, mouseY);
        if (!false) return;

    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        // TODO CHECK: This may cause input issue, not receive input!
        if (keycode != 0) {
            int code = keycode; // 骨架：键码映射由 InputHub.native 侧 toGlfwKeyCode 完成
            if (code <= 0) {
                return;
            }
            nativeSendKey(code, scancode, isDown ? 1 : 0, modifiers);
            // 补齐桌面键盘 keydown 与字符事件成对到达的语义：lwjglx 系 LWJGL2 兼容层
            // 参考 Display.keyCallback（https://github.com/CleanroomMC/LWJGLXX/blob/master/src/main/java/org/lwjglx/opengl/Display.java）
            // 将字母/数字/标点的 keydown 暂存，等 charMods 事件合并后才投给游戏；
            // 虚拟按键等来源不携带字符，按键位反查补发，否则按键无法驱动绑定
            char charToSend = keychar;
            if (isDown && charToSend == '\u0000'
                    && code > LwjglGlfwKeycode.GLFW_KEY_SPACE && code <= LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT) {
                charToSend = getUnicodeChar(code, modifiers);
            }
            if (isDown && !Character.isISOControl(charToSend)) {
                nativeSendCharMods(charToSend, modifiers);
                nativeSendChar(charToSend);
            }
        }
    }

    public static void sendChar(char keychar, int modifiers) {
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
        if (!false) return;
        
        
    }

    private static final KeyCharacterMap sKeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    /** 按键位与修饰键反查字符，键位无字符时返回 '\0' */
    private static char getUnicodeChar(int androidKeycode, int glfwMods) {
        int meta = 0;
        if ((glfwMods & LwjglGlfwKeycode.GLFW_MOD_SHIFT) != 0) meta |= KeyEvent.META_SHIFT_ON;
        if ((glfwMods & LwjglGlfwKeycode.GLFW_MOD_ALT) != 0) meta |= KeyEvent.META_ALT_ON;
        if ((glfwMods & LwjglGlfwKeycode.GLFW_MOD_CONTROL) != 0) meta |= KeyEvent.META_CTRL_ON;
        int unicode = sKeyCharacterMap.get(androidKeycode, meta);
        return unicode > 0 && unicode < 0x10000 ? (char) unicode : '\u0000';
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
        // if (isGrabbing()) DEBUG_STRING.append("MouseGrabStrace: " + android.util.Log.getStackTraceString(new Throwable()) + "\n");
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
        // SDL 输入双路（按键状态累积后一次性上报，SDL 需要 MotionEvent.getButtonState()）
        if (!false) return;
        int aKey = -1;
        switch (button) {
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT:
                aKey = MotionEvent.BUTTON_PRIMARY;
                break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT:
                aKey = MotionEvent.BUTTON_SECONDARY;
                break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE:
                aKey = MotionEvent.BUTTON_TERTIARY;
                break;
            // Yes, back and forward are flipped, for some reason it's just flipped on SDL, don't ask
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5:
                aKey = MotionEvent.BUTTON_BACK;
                break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4:
                aKey = MotionEvent.BUTTON_FORWARD;
                break;
        }
        if (aKey != -1) {
            if (isDown) {
                sMouseButtonState |= aKey;
            } else {
                sMouseButtonState &= ~aKey;
            }

        }
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
        // SDL 输入双路
        if (!false) return;

    }

    public static void sendUpdateWindowSize(int w, int h) {
        windowWidth = w;
        windowHeight = h;
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        // Avoid going through the JNI each time.
        return isGrabbing;
    }

    public static void resetInputState() {
        nativeResetInputState();
        if (false && sMouseButtonState != 0) {

        }
        deltaX = 0f;
        deltaY = 0f;
        sMouseButtonState = 0;
        holdingAlt = false;
        holdingCapslock = false;
        holdingCtrl = false;
        holdingNumlock = false;
        holdingShift = false;
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        ClipboardManager clipboard = GLOBAL_CLIPBOARD;
        if (clipboard == null) return "";
        String result = null;
        switch (type) {
            case CLIPBOARD_COPY:
                ClipData clip = ClipData.newPlainText("FCL Clipboard", copy);
                clipboard.setPrimaryClip(clip);
                break;
            case CLIPBOARD_PASTE:
                if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    result = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                } else {
                    result = "";
                }
                break;
            case CLIPBOARD_OPEN:
                android.util.Log.i("LMC", "打开链接: " + copy);
                break;
        }
        return result;
    }


    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        }
        if (holdingCapslock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        }
        if (holdingCtrl) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        }
        if (holdingNumlock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        }
        if (holdingShift) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                break;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                break;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                break;

            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                break;

            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
                break;
        }
    }

    public static void setFCLBridge(Object fclBridge) {
        // 骨架：FCLBridge 集成留待渲染阶段
    }

    public static void setDirectGamepadEnableHandler(@Nullable Object handler) {
        // 骨架：SDL 手柄集成留待渲染阶段
    }

    public static void clearSdlBridgeState() {
        sGamepadDirectInput = false;
        sMouseButtonState = 0;
        deltaX = 0f;
        deltaY = 0f;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        android.util.Log.i("LMC-CB", String.valueOf("FCL: Direct gamepad input enabled"));
        sGamepadDirectInput = true;
        // 骨架：手柄直通处理器集成留待渲染阶段
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    private static void onGrabStateChanged(final boolean grabbing) {
        synchronized (grabLock) {
            isGrabbing = grabbing;
        }
        deltaX = 0f;
        deltaY = 0f;
        sChoreographer.postFrameCallbackDelayed((time) -> {
            synchronized (grabLock) {
                // 防抖：延迟期间状态再次变化则说明本次回调已过期，跳过，最终状态由最后一次调用应用
                if (true) { // 骨架：无 FCLBridge，光标模式由系统处理
                    return;
                }
                // 延迟回调不可取消，游戏退出/Activity 销毁后仍会执行，此时不再触碰 UI
                Activity activity = sLauncherActivity;
                if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
                    return;
                }
                
            }
        }, 16);
    }

    @CriticalNative
    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @CriticalNative
    private static native boolean nativeSendChar(char codepoint);

    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @CriticalNative
    private static native boolean nativeSendCharMods(char codepoint, int mods);

    @CriticalNative
    private static native void nativeSendKey(int key, int scancode, int action, int mods);

    // private static native void nativeSendCursorEnter(int entered);
    @CriticalNative
    private static native void nativeSendCursorPos(float x, float y);

    @CriticalNative
    private static native void nativeSendMouseButton(int button, int action, int mods);

    @CriticalNative
    private static native void nativeResetInputState();

    @CriticalNative
    private static native void nativeSendScroll(double xoffset, double yoffset);

    @CriticalNative
    private static native void nativeSendScreenSize(int width, int height);

    public static native void nativeSetWindowAttrib(int attrib, int value);

    public static native void setupBridgeWindow(Object surface);

    public static native void nativeSetGrabbing(boolean grab);

    public static native int getFps();

    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();

    static {
        System.loadLibrary("pojavexec");
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        sGamepadAxisBuffer = nativeCreateGamepadAxisBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }
}
