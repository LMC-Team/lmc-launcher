package com.lemwoodmc.launcher.bridge;

import android.app.Activity;
import android.content.Context;

/**
 * Pojav/Zalith 生态桥接辅助（Zalith 2 路线收敛版）。
 *
 * 旧 FCL 混合生态（libfcl 长依赖链 + FCLBridge + 混装 so）已废弃：
 * 现全部组件与 Zalith 2 同源——libpojavexec(_awt)/exithook 打进 APK
 * jniLibs（ZLBridge 静态块 System.loadLibrary 加载），渲染栈 so
 * （gl4es/Mesa/LWJGL natives）部署于设备 natives/ 目录。
 */
public final class LmcPojavBridgeHelper {
    private LmcPojavBridgeHelper() {}

    /** 注入游戏宿主 Activity（ZLNativeInvoker 的剪贴板/退出回调使用） */
    public static void injectActivity(Activity activity) {
        com.movtery.zalithlauncher.bridge.ZLNativeInvoker.setGameActivity(activity);
    }

    /** 注入应用上下文（CallbackBridge.accessAndroidClipboard 使用） */
    public static void injectAppContext(Context context) {
        org.lwjgl.glfw.CallbackBridge.appContext = context;
    }

    /**
     * 把游戏 Surface 注入 libpojavexec（egl_bridge 的窗口后端）：
     * ANativeWindow_fromSurface 保存，pojavInit 时 acquire。
     * 必须在 HotSpot 启动前、主线程调用（Surface 由 UI 生命周期产生）。
     */
    public static void setupBridgeWindow(android.view.Surface surface, int width, int height) {
        com.movtery.zalithlauncher.bridge.ZLBridge.setupBridgeWindow(surface);
        org.lwjgl.glfw.CallbackBridge.windowWidth = width;
        org.lwjgl.glfw.CallbackBridge.windowHeight = height;
        org.lwjgl.glfw.CallbackBridge.physicalWidth = width;
        org.lwjgl.glfw.CallbackBridge.physicalHeight = height;
        org.lwjgl.glfw.CallbackBridge.sendUpdateWindowSize(width, height);
    }

    /**
     * 首帧输出监听：pojavexec 的 calculateFPS 在第一帧时回调 dex 侧
     * CallbackBridge.onGraphicOutput → 本监听（UI 层切换“渲染中”状态）。
     */
    public static void setGraphicOutputListener(
            org.lwjgl.glfw.CallbackBridge.GraphicOutputListener listener) {
        org.lwjgl.glfw.CallbackBridge.setGraphicOutputListener(listener);
    }

    /** JVM 启动：VMLauncher.launchJVM（pojavexec 的 JVM 封装入口） */
    public static int launchJVM(String[] args) {
        return com.oracle.dalvik.VMLauncher.launchJVM(args);
    }

    // ------------------------------------------------------------------
    // Kotlin 侧统一入口（GameViewModel 只调用本类，不直接触碰桥类：
    //   规避 Kotlin→Java 混编在本机的解析问题 + 单点收口便于诊断）
    // ------------------------------------------------------------------

    /** Android 键码 → GLFW 键码 → 事件流（对齐 Zalith GameHandler 输入路径） */
    public static void sendKeyByAndroidCode(int androidKeyCode) {
        int index = com.movtery.zalithlauncher.game.input.EfficientAndroidLWJGLKeycode
                .getIndexByKey(androidKeyCode);
        if (index >= 0) {
            com.movtery.zalithlauncher.game.input.EfficientAndroidLWJGLKeycode
                    .execKeyIndex(index);
        }
    }

    /**
     * 按键按下/抬起（虚拟按键长按走此路径）：MC 侧以 down/up 状态机维持移动，
     * 不依赖 key repeat。androidKeyCode 需在 GLFW 映射表内（否则忽略）。
     */
    public static void sendKeyEvent(int androidKeyCode, boolean pressed) {
        int index = com.movtery.zalithlauncher.game.input.EfficientAndroidLWJGLKeycode
                .getIndexByKey(androidKeyCode);
        if (index < 0) return;
        short glfwKey = com.movtery.zalithlauncher.game.input.EfficientAndroidLWJGLKeycode
                .getValueByIndex(index);
        org.lwjgl.glfw.CallbackBridge.sendKeyPress(
                glfwKey, 0, org.lwjgl.glfw.CallbackBridge.getCurrentMods(), pressed);
    }

    /** 鼠标抓取状态(grab 时游戏隐藏光标,虚拟光标同步隐藏) */
    public static boolean isGrabbing() {
        return org.lwjgl.glfw.CallbackBridge.isGrabbing();
    }

    /** GLFW 键码直传（控制层按钮的 keyCode 即 GLFW 码：87=W、65=A、68=D、83=S、32=空格…） */
    public static void sendGlfwKeyEvent(int glfwKeyCode, boolean pressed) {
        org.lwjgl.glfw.CallbackBridge.sendKeyPress(
                (short) glfwKeyCode, 0,
                org.lwjgl.glfw.CallbackBridge.getCurrentMods(), pressed);
    }

    /** 指针事件：action 0=down 1=up 2=move */
    public static void sendPointerEvent(int action, float x, float y, int button) {
        switch (action) {
            case 0 -> org.lwjgl.glfw.CallbackBridge.sendMouseButton(button, true);
            case 1 -> org.lwjgl.glfw.CallbackBridge.sendMouseButton(button, false);
            default -> org.lwjgl.glfw.CallbackBridge.sendCursorPos(x, y);
        }
    }

    /** 触摸桥：光标位置 + 左键状态（触摸桥专用，坐标 1:1） */
    public static void sendCursorPos(float x, float y) {
        org.lwjgl.glfw.CallbackBridge.sendCursorPos(x, y);
    }

    public static void sendMouseButtonEvent(int button, boolean pressed) {
        org.lwjgl.glfw.CallbackBridge.sendMouseButton(button, pressed);
    }

    public static void sendScrollEvent(float xOffset, float yOffset) {
        org.lwjgl.glfw.CallbackBridge.sendScroll(xOffset, yOffset);
    }

    /** HotSpot 侧库搜索路径（pojavexec native 侧记录） */
    public static void zalithSetLdLibraryPath(String path) {
        com.movtery.zalithlauncher.bridge.ZLBridge.setLdLibraryPath(path);
    }

    /** pojavexec 的 native dlopen（namespace 正确的 JRE/引擎库加载通道） */
    public static boolean zalithDlopen(String soPath) {
        return com.movtery.zalithlauncher.bridge.ZLBridge.dlopen(soPath);
    }

    public static void zalithSetupExitMethod(Context context) {
        com.movtery.zalithlauncher.bridge.ZLBridge.setupExitMethod(context);
    }

    public static void zalithInitializeGameExitHook() {
        com.movtery.zalithlauncher.bridge.ZLBridge.initializeGameExitHook();
    }

    /** 进程 CWD 切换（MC 日志相对路径依赖；返回 0=成功） */
    public static int zalithChdir(String path) {
        return com.movtery.zalithlauncher.bridge.ZLBridge.chdir(path);
    }

    /**
     * 环境变量注入器（Os.setenv 的异常安全封装）。
     * libpojavexec 的 env_init 读取 POJAV_* 系列决定渲染后端与窗口行为。
     */
    public static final class EnvPutter {
        public void put(String key, String value) {
            try {
                android.system.Os.setenv(key, value, true);
            } catch (Throwable t) {
                android.util.Log.w("LMC", "setenv " + key + " 失败: " + t.getMessage());
            }
        }

        public void commit() {}
    }
}
