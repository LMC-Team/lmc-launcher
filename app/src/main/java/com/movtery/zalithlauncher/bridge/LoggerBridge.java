package com.movtery.zalithlauncher.bridge;

/**
 * Zalith 日志桥（对齐 ZalithLauncher LoggerBridge）。
 *
 * libpojavexec 的 stdio_is.c 实现约定：
 *  - start(logPath)：redirect stdout/stderr → pipe → 日志线程写文件并回调监听
 *  - FindClass "LoggerBridge$EventLogListener" + GetMethodID
 *    "onEventLogged(Ljava/lang/String;)V" —— 方法名必须精确（真机踩坑：
 *    onEventLog 少两个字母 → GetMethodID null → CheckJNI abort）
 */
public class LoggerBridge {

    public interface EventLogListener {
        void onEventLogged(String log);
    }

    private static volatile EventLogListener listener;

    /** 启动日志转发（native 侧 redirect stdio + 后台线程回调监听） */
    public static native void start(String logFilePath);

    /** native 侧日志回调 */
    public static native void append(String log);

    public static void appendTitle(String title) {
        append("==================== " + title + " ====================");
    }

    public static native void setListener(EventLogListener l);

    /** 供 launcher 侧注册监听（收游戏日志到 UI） */
    public static void setListenerSafe(EventLogListener l) {
        listener = l;
    }
}
