package com.movtery.zalithlauncher.game.input;

import androidx.annotation.Keep;
import dalvik.annotation.optimization.CriticalNative;

/**
 * Zalith Launcher 2 同源组件（GPL-3.0，from PojavLauncher CriticalNativeTest）。
 *
 * <p>input_bridge 的 JNI_OnLoad 用它做运行时探测：调用 testCriticalNative(0,0) 并
 * 在 native 侧检查参数是否按 CriticalNative 寄存器约定到达，以此决定注册
 * critical（无 env/jclass 前缀）还是 noncritical（带前缀）函数表。
 *
 * <p>真机教训（2026-10-01，断点 #49 根因）：此类缺失 → 探测失败 → 注册 noncritical
 * 表，但 CallbackBridge 的 native 方法保留 @CriticalNative 注解 → ABI 错位：
 * int 参数函数（mouse button）整体错位两个寄存器（act/mods 读到寄存器垃圾，
 * 事件值变成 8/负数被 MC 丢弃 → 菜单 click 不触发）；而 float 参数函数
 * （cursor pos）走 s0/s1 浮点寄存器不受影响 → hover 正常。二者组合即
 * "hover 有效、click 无效"的怪象。
 */
@Keep
public class CriticalNativeTest {
    @Keep
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);

    @Keep
    public static void invokeTest() {
        testCriticalNative(0, 0);
    }
}
