package com.movtery.zalithlauncher.bridge;

import android.content.Context;

/**
 * Zalith 生态桥（dex 版 stub）。
 *
 * Zalith 版 libpojavexec.so 的 JNI 导出指向本类：
 *   Java_com_movtery_zalithlauncher_bridge_ZLBridge_{chdir,dlopen,
 *   setLdLibraryPath,setupBridgeWindow,releaseBridgeWindow,setupExitMethod}
 * 方法签名与 Zalith dex 完全一致（dexdump 提取）。
 *
 * 关键方法 setupBridgeWindow(Object surface)：把游戏 Surface 交给
 * libpojavexec（ANativeWindow_fromSurface 保存为 GLFW 窗口后端）。
 */
public class ZLBridge {

    /** 进程 CWD 切换（MC 日志相对路径依赖；返回 0=成功） */
    public static native int chdir(String path);

    /** 在 HotSpot linker namespace 里 dlopen 一个 so（返回 true=成功） */
    public static native boolean dlopen(String soPath);

    /** 设置 HotSpot 侧的库搜索路径（供 LWJGL natives 解析） */
    public static native void setLdLibraryPath(String path);

    /** 把游戏 Surface 注入 libpojavexec（GLFW 窗口后端） */
    public static native void setupBridgeWindow(Object surface);

    /** 释放桥窗口（surface 销毁时） */
    public static native void releaseBridgeWindow();

    /** 注册游戏退出回调（native 侧退出时回调 launcher） */
    public static native void setupExitMethod(Context context);
}
