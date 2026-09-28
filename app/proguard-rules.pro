# LMC Launcher ProGuard 规则
# JNI 桥：native 层通过 RegisterNatives 绑定 + 反射回调 onThermalWarning，不可混淆
-keep class com.lemwoodmc.launcher.bridge.NativeBridge { *; }

# Compose 悬浮控制层由反射创建（GameComposeOverlayHost），保留构造器
-keep class com.lemwoodmc.launcher.game.GameComposeOverlayHost { *; }

# Minecraft 相关反射类名（若后续内置启动工具类）
-keep class com.lemwoodmc.launcher.launch.** { *; }
