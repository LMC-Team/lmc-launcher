package com.lemwoodmc.launcher.bridge;

import android.content.ClipboardManager;

/**
 * Pojav 生态桥接辅助（Java 实现，规避 Kotlin 对新增 java 包的编译顺序问题）：
 * libpojavexec 的 JNI_OnLoad 需要 ART dex classpath 上存在
 * org.lwjgl.glfw.CallbackBridge（由 CallbackBridge.java 源码提供），
 * 其剪贴板回调依赖该静态剪贴板管理器引用。
 */
public final class LmcPojavBridgeHelper {
    private LmcPojavBridgeHelper() {}

    public static void injectClipboard(ClipboardManager cm) {
        org.lwjgl.glfw.CallbackBridge.GLOBAL_CLIPBOARD = cm;
    }

    /** 注入启动器 Activity 引用（CallbackBridge 的 UI 回调使用） */
    public static void injectActivity(android.app.Activity activity) {
        org.lwjgl.glfw.CallbackBridge.sLauncherActivity = activity;
    }

    /**
     * FCL 窗口启动序列：加载 libfcl/pojavexec_awt/pojavexec（ART 注册）→
     * FCLBridge.execute → redirectStdio → CallbackBridge.setupBridgeWindow
     * （ANativeWindow 注入 pojavexec，GLFW 后端 acquire 用）。
     */

    /**
     * 把游戏 Surface 注入 libpojavexec（GLFW 窗口后端）：
     * CallbackBridge.setupBridgeWindow(Object) → native 侧
     * ANativeWindow_fromSurface 保存。不注入则 glfwCreateWindow 后
     * ANativeWindow_acquire(NULL) SIGSEGV（真机踩坑）。
     */
    public static void setupBridgeWindow(android.view.Surface surface, int width, int height) {
        org.lwjgl.glfw.CallbackBridge.windowWidth = width;
        org.lwjgl.glfw.CallbackBridge.windowHeight = height;
        org.lwjgl.glfw.CallbackBridge.setupBridgeWindow(surface);
    }

    /**
     * FCL 窗口启动序列：加载 libfcl/pojavexec_awt/pojavexec（ART 注册）→
     * FCLBridge.execute → redirectStdio → CallbackBridge.setupBridgeWindow
     * （ANativeWindow 注入 pojavexec，GLFW 后端 acquire 用）。
     */
    public static void fclWindowSequence(String nativesDir, android.view.Surface surface, String logPath) {
        loadFclLibs(nativesDir);
        com.tungsten.fclauncher.bridge.FCLBridge bridge = new com.tungsten.fclauncher.bridge.FCLBridge();
        bridge.setLogPath(logPath);
        bridge.execute(surface, new com.tungsten.fclauncher.bridge.FCLBridge.FCLBridgeCallback() {
            @Override public void onCursorModeChange(int mode) {}
            @Override public void onLog(String log) {}
            @Override public void onExit(int code) {}
        });
    }

        /** JVM 启动：VMLauncher.launchJVM（pojavexec 封装的 JVM 入口） */
    public static int launchJVM(String[] args) {
        return com.oracle.dalvik.VMLauncher.launchJVM(args);
    }

    /** Zalith 原生窗口注入路径：ZLBridge.setupBridgeWindow + 窗口尺寸同步 */
    public static void zalithSetupBridgeWindow(android.view.Surface surface, int width, int height) {
        com.movtery.zalithlauncher.bridge.ZLBridge.setupBridgeWindow(surface);
        org.lwjgl.glfw.CallbackBridge.sendUpdateWindowSize(width, height);
    }

    /**
     * Zalith 启动配方的环境变量注入器（Os.setenv 的异常安全封装）。
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

    /**
     * ART 侧加载 libpojavexec.so（绝对路径）：
     * 其 JNI_OnLoad 会在本进程默认运行时（ART）里向 dex classpath 上的
     * org.lwjgl.glfw.CallbackBridge 注册桥方法，必须先于 HotSpot 启动完成。
     */
    public static void loadPojavExec(String nativesDir) {
        System.load(nativesDir + "/libpojavexec.so");
    }

    /** CallbackBridge 类初始化 + Activity 注入（需在 libpojavexec 加载后、HotSpot 启动前） */
    public static void initCallbackBridge(android.app.Activity activity) {
        // 触发 CallbackBridge.<clinit>（此时 loadLibrary 已被裁剪为 no-op，无异常）
        Class<?> cls = org.lwjgl.glfw.CallbackBridge.class;
        android.util.Log.i("LMC", "CallbackBridge 初始化完成: " + cls.getName());
    }

    /** ART 侧加载 libfcl.so + libpojavexec_awt.so + libpojavexec.so（FCLBridge 的 native 实现；
     *  pojavexec 必须经 System.load 注册进 ART libraries 列表，
     *  否则 CallbackBridge.setupBridgeWindow 的 native 解析失败） */
    public static void loadFclLibs(String nativesDir) {
        System.load(nativesDir + "/libfcl.so");
        System.load(nativesDir + "/libpojavexec_awt.so");
        System.load(nativesDir + "/libpojavexec.so");
    }

    /**
     * 把游戏 Surface 交给 libpojavexec（GLFW 窗口后端）：
     * MC 调 glfwCreateWindow 时，pojavexec 用此窗口做 ANativeWindow_acquire。
     * 不注入则 glfwInit 后第一次 acquire(NULL) 直接 SIGSEGV（真机踩坑）。
     */
    public static void setupBridgeWindow(android.view.Surface surface,
                                         int windowWidth, int windowHeight,
                                         int physicalWidth, int physicalHeight) {
        org.lwjgl.glfw.CallbackBridge.windowWidth = windowWidth;
        org.lwjgl.glfw.CallbackBridge.windowHeight = windowHeight;
        org.lwjgl.glfw.CallbackBridge.physicalWidth = physicalWidth;
        org.lwjgl.glfw.CallbackBridge.physicalHeight = physicalHeight;
        org.lwjgl.glfw.CallbackBridge.setupBridgeWindow(surface);
    }
}
