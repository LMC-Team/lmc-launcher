package com.tungsten.fclauncher.bridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Surface;

import org.lwjgl.glfw.CallbackBridge;

/**
 * FCL 生态的启动桥（dex 版，裁剪自 FCL-release 1.3.3.5 的 FCLBridge.java）。
 *
 * 核心职责：
 *  1. libfcl.so / libpojavexec_awt.so 的 native 声明所在（实现由
 *     LmcPojavBridgeHelper.loadFclLibs 用绝对路径加载）；
 *  2. execute(surface)：窗口注入序列——redirectStdio →
 *     CallbackBridge.setupBridgeWindow(surface)（ANativeWindow 注入）。
 *
 * 裁剪说明（相对 FCL 原版）：FCLApp/FCLActivity/OpenFolderDialog/SDL 等
 * FCL 内部依赖替换为注入或 no-op；AWT 软渲染线程保留结构。
 */
public class FCLBridge {
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    public static final int CursorEnabled = 1;
    public static final int CursorDisabled = 0;

    public interface FCLBridgeCallback {
        void onCursorModeChange(int mode);
        void onLog(String log);
        void onExit(int code);
    }

    private FCLBridgeCallback callback;
    private Surface surface;
    private String logPath;

    /** 剪贴板与 Activity 由 LmcPojavBridgeHelper 注入（复用 CallbackBridge 的字段） */
    public static ClipboardManager clipboard() {
        return org.lwjgl.glfw.CallbackBridge.GLOBAL_CLIPBOARD;
    }

    public FCLBridge() {}

    // ------------------------------------------------------------------
    // native 接口（实现在 libfcl.so / libpojavexec_awt.so，签名与 FCL 版一致）
    // ------------------------------------------------------------------

    public static native void nativeClipboardReceived(String data, String mimeTypeSub);
    public native int[] renderAWTScreenFrame();
    public native void nativeSendData(int type, int i1, int i2, int i3, int i4);
    public native void nativeMoveWindow(int x, int y);
    public native int redirectStdio(String path);
    public native int chdir(String path);
    public native void setenv(String key, String value);
    public native long dlopen(String path);
    public native void setLdLibraryPath(String path);
    public native void setupExitTrap(FCLBridge bridge);
    public static native void initializeHooks();
    public static native long getJavaVMPointer();
    public static native String jObjectToString(Object object);

    // ------------------------------------------------------------------
    // java 回调（libpojavexec_awt 的 JNI_OnLoad GetStaticMethodID 目标）
    // ------------------------------------------------------------------

    /** AWT 剪贴板写入（native 侧调用）：转发到注入的系统剪贴板 */
    public static void putClipboardData(String data, String mimeType) {
        ClipboardManager cm = org.lwjgl.glfw.CallbackBridge.GLOBAL_CLIPBOARD;
        if (cm == null || data == null) return;
        ClipData clip = "text/html".equals(mimeType)
                ? ClipData.newHtmlText("AWT Paste", data, data)
                : ClipData.newPlainText("AWT Paste", data);
        cm.setPrimaryClip(clip);
    }

    /** AWT 剪贴板读取（native 侧调用） */
    public static void querySystemClipboard() {
        // pojavexec_awt 的 JNI_OnLoad 要求此签名 ()V（符号配对）；
        // 实际数据由 accessAndroidClipboard 通道交换
        ClipboardManager cm = org.lwjgl.glfw.CallbackBridge.GLOBAL_CLIPBOARD;
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return;
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        String text = item.getText() != null ? item.getText().toString() : null;
        nativeClipboardReceived(text, "plain");
    }

    // ------------------------------------------------------------------
    // 窗口启动序列（FCL execute 的裁剪版）
    // ------------------------------------------------------------------

    public void execute(Surface surface, FCLBridgeCallback callback) {
        this.callback = callback;
        this.surface = surface;
        receiveLog("invoke redirectStdio\n");
        redirectStdio(getLogPath());
        handleWindow();
    }

    private void handleWindow() {
        receiveLog("invoke setFCLNativeWindow\n");
        // ANativeWindow 注入（Pojav 主线路径：保存进 pojavexec 供 GLFW 后端 acquire）
        CallbackBridge.setupBridgeWindow(surface);
    }

    // ------------------------------------------------------------------
    // native → java 回调（libfcl.so 内部调用）
    // ------------------------------------------------------------------

    public void onExit(int code) {
        if (callback != null) {
            callback.onExit(code);
        }
    }

    public void setCursorMode(int mode) {
        if (callback != null) {
            callback.onCursorModeChange(mode);
        }
    }

    public void setPrimaryClipString(String string) {
        ClipboardManager cm = clipboard();
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("FCL Clipboard", string));
        }
    }

    public String getPrimaryClipString() {
        ClipboardManager cm = clipboard();
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemAt(0).getText() == null) {
            return null;
        }
        return cm.getPrimaryClip().getItemAt(0).getText().toString();
    }

    public static void openLink(String link) {
        android.util.Log.i("LMC", "打开链接: " + link);
    }

    public void receiveLog(String log) {
        if (callback != null) {
            callback.onLog(log);
        }
    }

    public String getLogPath() {
        return logPath != null ? logPath : "/data/local/tmp/fcl_bridge.log";
    }

    public void setLogPath(String path) {
        this.logPath = path;
    }
}
