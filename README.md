# LMC Launcher —— 高性能 Android Minecraft Java 版启动器

以**性能为唯一最高优先级**的 Android Minecraft Java 版启动器与运行时后端。
参考 PojavLauncher / FCL / ZalithLauncher 的设计思路并吸取各自优点,完全不考虑向后兼容性。

## 当前状态(2026-10)

**MC 1.21.4 已在真机(Poco F3 / 骁龙 870 / Android 13)抵达主菜单**:

```
✅ 版本下载(piston-meta → client.jar + 官方 libraries + assets 索引)
✅ JVM 创建(dlopen 链 + dex 契约 + 纯 native 线程,Termux JRE 21)
✅ MC 主类执行 → Datafixer → Render thread
✅ LWJGL 3.3.6-snapshot 全模块加载(与 Zalith 2 natives 同源配对)
✅ MobileGlues(OpenGL 4.0 on GLES 3.2)渲染 —— 主菜单完整呈现
✅ 触摸桥(hover 已通)、横屏锁定、AppCDS、绑核调度、温度监控
⏳ 进行中:菜单 click 事件链(断点 #49)、assets 部署、OpenAL 声音
```

完整的断点记录(49 项)与根因分析见 [docs/PROGRESS.md](docs/PROGRESS.md)。

- **JVM 直载**:OpenJDK 21 aarch64(Termux 补丁版)通过 `dlopen` 直接加载,**无 proot**
- **渲染主路径**:MobileGlues(OpenGL 4.0 on GLES 3.2)插件渲染,内置 Mesa Turnip 回落
- **CPU 调度**:big.LITTLE 拓扑识别,游戏主线程绑大核、GC/JIT 绑小核、温度降频预测
- **输入直读**:NativeActivity 路径下 AInputQueue 在 native 层直接处理,零 Java 参与
- **音频**:OpenAL-Soft + AAudio MMAP 低延迟模式
- **UI**:Kotlin + Jetpack Compose(Material 3),游戏内控制层可整体隐藏实现 UI 零开销

## 架构

```
lmc launcher/
├── app/                        # 启动器 UI(Kotlin + Compose Material 3)
│   └── src/main/java/com/lemwoodmc/launcher/
│       ├── bridge/             #   JNI 状态桥(NativeBridge / LmcPojavBridgeHelper)
│       ├── game/               #   RendererPluginLoader(FCL/Zalith 渲染器插件协议)
│       ├── viewmodel/          #   GameViewModel(启动序列:Zalith 三段式 + lmc 性能层)
│       └── ...
│   └── com.movtery.zalithlauncher.bridge 等   # dex 契约桥(ZLBridge/CallbackBridge)
├── core/                       # C++ 核心后端(NDK + CMake → liblmc_core.so)
│   └── src/
│       ├── jvm/                #   JVM 加载器(JNI_CreateJavaVM 于纯 native 线程)
│       ├── render/             #   渲染桥接(Vulkan swapchain / pipeline cache / gl4es)
│       ├── sched/              #   CPU 拓扑 / 亲和性绑定 / 温度监控
│       ├── input/              #   输入中枢 / AInputQueue 原生处理 / NativeActivity 胶水
│       └── ...
├── scripts/                    # 构建与部署脚本
└── docs/                       # 编译说明与架构文档
```

### 组件栈(同源原则)

全部游戏侧组件收敛为 **Zalith 2 单源**,消除跨 APK 配对问题:

| 组件 | 来源 | 位置 |
|---|---|---|
| libpojavexec(_awt) / exithook 等 | ZalithLauncher 2 jni/ 自研 | APK jniLibs |
| LWJGL 3.3.6-snapshot(java + natives) | Zalith 2 LWJGL 模块 | assets 释放 + 设备 natives/ |
| 渲染栈(gl4es / Mesa Turnip / OSMesa) | Zalith 2 构建产物 | 设备 natives/ |
| JRE 21(Termux bionic 补丁版) | Termux 官方 | 设备 runtime/ |
| **MobileGlues(OpenGL 4.0)** | MobileGL-Dev 插件 APK | 独立安装,协议自动发现 |
| cacio AWT / Mio patcher | Zalith 2 assets | 设备 versions/<id>/libs/ |

## 渲染路径

| 路径 | 链路 | 状态 |
|---|---|---|
| **MobileGlues(插件)** | MC(GL 4.0) → MobileGlues → 宿主 GLES 3.2 | **推荐**,1.17+,主菜单已验证 |
| **Freedreno/Turnip** | MC(GL 3.x) → Mesa OSMesa → Turnip/kgsl | 内置回落,Adreno 机型 |
| **gl4es** | MC(GL 2.1) → gl4es → 系统 EGL/GLES | 兼容兜底(GL 入口不全,新 MC 不适用) |
| **原生 Vulkan** | MC(VulkanMod) → lmc_core swapchain | 备选(swapchain 已实现) |

渲染器插件遵循 FCL/Zalith 插件协议:安装插件 APK 后启动器自动发现
(manifest meta-data `fclPlugin` + `renderer` + `pojavEnv`),零配置切换。

## 快速开始

环境要求:JDK 17+(Temurin 21 验证)、Android SDK(compileSdk 35)、
NDK r27b、CMake 3.22.1、aarch64 真机(Android 10+)。

```bash
# 1. 构建 APK(C++ + Kotlin 全量)
JAVA_HOME=<jdk21> ./gradlew assembleDebug

# 2. 安装并部署运行时组件到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
scripts/deploy_zalith2_stack.sh deploy 1.21.4   # Zalith 2 natives + LWJGL jar
#   另需:Termux JRE 21 → 设备 files/runtime/jre21
#        MC 版本数据 → 设备 files/versions/1.21.4

# 3. (推荐)安装 MobileGlues 渲染器插件
adb install MobileGlues_2.0.0.apk   # github.com/MobileGL-Dev/MobileGlues-release
```

## 技术要点(真机踩坑结晶)

- **纯 native 线程跑 JVM**:pojavexec 的输入回调对"带活跃 JNI 栈的 ART 线程"
  做 Detach 会 abort——JVM 必须由 pthread 创建(lmc_core nativeCreateJvmOnNewThread)
- **dex 契约双空间**:桥类需同时存在于 launcher dex(ART RegisterNatives)
  与 HotSpot classpath(jar),方法签名逐字符对齐
- **targetSdk=28 铁律**:Android 10+ 的 SELinux 禁止 targetSdk≥29 应用
  dlopen 应用私有目录 so,而 JRE 就部署在那里(与 Pojav 系一致)
- **组件同源三角**:JRE(jrt+natives) ↔ pojavexec(_awt) ↔ LWJGL jar
  必须同源同 commit,跨 APK 混装必然踩配对断点

## 许可与声明

本项目基于 [GPL-3.0](LICENSE) 许可开源(与 PojavLauncher/ZalithLauncher 保持一致)。

本项目为学习/研究用途的启动器框架代码,不含、不分发任何 Minecraft 游戏资源;
使用本启动器游玩 Minecraft 需自行持有正版账户。Minecraft 是 Mojang Studios 的商标。

第三方组件(由 `scripts/` 构建或随仓库/设备分发):

| 组件 | 许可 | 说明 |
|---|---|---|
| OpenJDK 21 (Termux 补丁版) | GPL-2.0 + Classpath Exception | Termux 官方源 |
| ZalithLauncher 2 组件栈 | GPL-3.0 | github.com/ZalithLauncher/ZalithLauncher |
| MobileGlues | LGPL-2.1 | github.com/MobileGL-Dev |
| Mesa (Zink/Turnip) | MIT 等多重许可 | scripts/build_mesa.sh |
| gl4es | MIT | scripts/build_gl4es.sh |
| OpenAL-Soft | LGPL-2.1 | scripts/build_openal.sh |
| PojavLauncher glfw-classes | GPL-3.0 | github.com/PojavLauncherTeam/PojavLauncher |
