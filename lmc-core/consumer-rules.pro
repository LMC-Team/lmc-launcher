# lmc-core 的 JNI 契约类。
#
# liblmc_core.so 以「标准 JNI 命名」解析 native 方法
# （Java_com_lemwoodmc_launcher_bridge_NativeBridge_nativeXxx），
# 并用静态方法 onThermalWarning 回传温度预警。因此：
#   - 类名/包名/方法名一旦被 R8 混淆，JNI 查找即失败（UnsatisfiedLinkError）；
#   - native 回调依赖方法签名不变。
# 这里 keep 全部成员，禁止重命名与移除。

-keep class com.lemwoodmc.launcher.bridge.NativeBridge { *; }
-keep class com.lemwoodmc.launcher.bridge.RenderMode { *; }

# native 回调入口（@JvmStatic → NativeBridge.onThermalWarning / onThermalWarningImpl）
-keepclassmembers class com.lemwoodmc.launcher.bridge.NativeBridge {
    public static *;
}
