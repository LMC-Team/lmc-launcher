package com.oracle.dalvik;

/**
 * OpenJDK-Android 的 JVM 启动入口（dex 版声明，实现在 libpojavexec.so）。
 *
 * pojavexec 封装了完整的 JVM 启动逻辑：解析 java 命令行（JVM 参数 + 主类 +
 * 游戏参数）、dlopen libjvm、CreateJavaVM、反射调用主类 main——FCL/Zalith
 * 均经此入口启动 JVM（替代手工 JNI_CreateJavaVM）。
 *
 * args 约定（FCL rebaseArgs）：args[0] = java 可执行路径（占位），
 * 其后为标准 java 命令行。
 */
public final class VMLauncher {
    private VMLauncher() {}

    public static native int launchJVM(String[] args);
}
