# LMC Launcher —— 高性能 Android Minecraft Java 版后端

以**性能为唯一最高优先级**的 Android Minecraft Java 版运行后端与启动器。
参考 PojavLauncher 与 Boat 的设计思路并吸取各自优点，完全不考虑向后兼容性。

- **JVM 直载**：OpenJDK 21 aarch64（Termux 补丁版）通过 `dlopen` 直接加载，**无 proot**
- **渲染主路径**：自编译 Mesa Zink + Turnip（骁龙开源 Vulkan 驱动），直连 ANativeWindow
- **CPU 调度**：big.LITTLE 拓扑识别，游戏主线程绑大核、GC/JIT 绑小核、温度降频预测
- **输入直读**：NativeActivity 路径下 AInputQueue 在 native 层直接处理，零 Java 参与
- **音频**：OpenAL-Soft + AAudio MMAP 低延迟模式
- **UI**：Kotlin + Jetpack Compose（Material 3），游戏内控制层可整体隐藏实现 UI 零开销

## 项目结构

```
lmc launcher/
├── app/                        # 启动器 UI（Kotlin + Compose Material 3）
│   └── src/main/java/com/lemwoodmc/launcher/
│       ├── bridge/             #   JNI 状态桥（NativeBridge / RenderMode / GameLaunchConfig）
│       ├── data/               #   DataStore 设置仓库
│       ├── game/               #   NativeActivity 游戏路径 + Compose 悬浮层注入宿主
│       ├── ui/                 #   主页 / 设置 / 版本 / 账户 / 游戏画面 / 悬浮控制层
│       ├── viewmodel/          #   LauncherViewModel / GameViewModel（状态桥）
│       └── ...
├── core/                       # C++ 核心后端（NDK + CMake → liblmc_core.so）
│   └── src/
│       ├── jvm/                #   JVM 加载器（dlopen libjvm / AppCDS / 参数调优）
│       ├── graal/              #   GraalVM Native Image AOT 路径（预留）
│       ├── render/             #   渲染桥接（Vulkan swapchain / pipeline cache / gl4es）
│       ├── sched/              #   CPU 拓扑 / 亲和性绑定 / 温度监控
│       ├── input/              #   输入中枢 / AInputQueue 原生处理 / NativeActivity 胶水
│       ├── audio/              #   AAudio MMAP 探测 + OpenAL-Soft 配置
│       ├── mem/                #   oom_score_adj / 世界存档 mmap
│       └── jni_bridge/         #   JNI 入口
├── scripts/                    # 构建脚本（OpenJDK / Mesa / gl4es / OpenAL / AppCDS）
└── docs/                       # 编译说明与架构文档
```

## 快速开始

环境要求：Android Studio（AGP 8.7+）、NDK r27、CMake 3.22+、aarch64 真机（Android 10+）。

```bash
# 1. 构建 APK（内含 liblmc_core.so）
./scripts/build_all.sh

# 2. 构建 / 部署渲染与 JVM 运行时（可选，默认构建 APK 已够 UI 验证）
./scripts/build_all.sh --with-runtime
```

详细步骤见 [docs/BUILD.md](docs/BUILD.md)，架构与性能设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 三条渲染路径

| 路径 | 链路 | 状态 |
|---|---|---|
| **Zink 主路径** | MC(GL) → Mesa Zink → Turnip/厂商 Vulkan → ANativeWindow | 默认，骁龙设备优先 Turnip |
| **原生 Vulkan** | MC(原生渲染器) → lmc_core VulkanSwapchain → ANativeWindow | 备选（VulkanMod 思路），swapchain/pipeline cache 已完整实现 |
| **gl4es 保底** | MC(GL) → 裁剪版 gl4es → 系统 EGL/GLES → ANativeWindow | 兼容兜底 |

## 许可与声明

本项目基于 [GPL-3.0](LICENSE) 许可开源（与 PojavLauncher 保持一致，因部分构建依赖来自其生态）。

本项目为学习/研究用途的启动器框架代码，不含、不分发任何 Minecraft 游戏资源；
使用本启动器游玩 Minecraft 需自行持有正版账户。Minecraft 是 Mojang Studios 的商标。

第三方组件（由 `scripts/` 构建或随仓库分发）：

| 组件 | 许可 | 说明 |
|---|---|---|
| OpenJDK 21 (Termux 补丁版) | GPL-2.0 + Classpath Exception | `scripts/build_openjdk.sh` 构建 |
| Mesa (Zink) / Turnip | MIT 等多重许可 | `scripts/build_mesa.sh` 构建 |
| gl4es | MIT | `scripts/build_gl4es.sh` 构建 |
| OpenAL-Soft | LGPL-2.1 | `scripts/build_openal.sh` 构建 |
| LWJGL 3 (`app/libs/lwjgl-3.3.3-merged-modules.jar`) | BSD-3-Clause | 官方上游 jar |
| PojavLauncher glfw-classes (`app/libs/pojav-lwjgl-glfw-classes.jar`) | GPL-3.0 | 来自 [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) |
