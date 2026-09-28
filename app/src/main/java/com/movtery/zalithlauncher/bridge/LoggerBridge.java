package com.movtery.zalithlauncher.bridge;

import android.content.Context;

/**
 * Zalith 日志桥（dex 版 stub）：
 * libpojavexec 的 JNI_OnLoad 会 FindClass 本类并绑定 native 方法
 * （游戏 stdout/stderr 转发到 launcher 日志视图）。
 * 骨架阶段实现为 logcat 直通。
 */
public class LoggerBridge {

    public interface EventLogListener {
        void onEventLog(String log);
    }

    private static volatile EventLogListener listener;

    /** 启动日志转发（native 侧把游戏输出回调到 append） */
    public static native void start(String logFilePath);

    /** native 侧日志回调（PUBLIC STATIC NATIVE） */
    public static native void append(String log);

    public static void appendTitle(String title) {
        android.util.Log.i("LMC-Game", "==== " + title + " ====");
    }

    public static native void setListener(EventLogListener l);

    /** 供 launcher 侧注册监听（收游戏日志到 UI） */
    public static void setListenerSafe(EventLogListener l) {
        listener = l;
    }
}
