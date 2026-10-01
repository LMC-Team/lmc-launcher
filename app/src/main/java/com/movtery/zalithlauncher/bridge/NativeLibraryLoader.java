package com.movtery.zalithlauncher.bridge;

/**
 * Zalith 2 桥库加载序列（对齐 ZalithLauncher NativeLibraryLoader）。
 *
 * 顺序契约：exithook → pojavexec → pojavexec_awt。
 * libpojavexec（Zalith 2 版）的 NEEDED 仅 libdriver_helper + 系统库，
 * 无 FCL 的 libfcl/bytehook 长链——三个 System.load 足以触发各自
 * JNI_OnLoad 在 ART 侧完成 RegisterNatives。
 */
public class NativeLibraryLoader {
    public static void loadPojavLib() {
        System.loadLibrary("pojavexec");
    }

    public static void loadExitHookLib() {
        System.loadLibrary("exithook");
    }

    public static void loadPojavAWTLib() {
        System.loadLibrary("pojavexec_awt");
    }
}
