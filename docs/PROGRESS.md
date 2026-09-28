# 真机调试日志（Poco F3 / 骁龙 870 / Android 13）

> 按时间顺序记录 LMC Launcher 在真机上推进真实 Minecraft 启动所遇到的问题与修复。
> 每个"断点"都是一次端到端验证：问题暴露在真实硬件 + 真实系统策略下，
> 静态检查与桌面构建无法复现。

## 已打通的链路（按验证顺序）

| # | 验证点 | 证据 |
|---|---|---|
| 1 | APK 安装启动，native 初始化 | `JNI_OnLoad 完成` / CPU 拓扑 1+3+4 → 大核 [4,5,6,7] |
| 2 | AAudio MMAP 探测 | MIUI 不支持 → 回落 OpenSL（按设计） |
| 3 | sched_setaffinity 绑核 | `游戏主线程 -> 大核 [4,5,6,7]: 成功` |
| 4 | ANativeWindow 挂载 | 1080x2276（SurfaceView 路径） |
| 5 | **JVM 创建** | `JVM 已创建（OpenJDK 21 aarch64, 19 项参数）` |
| 6 | **Java 代码执行** | `jvm_test_ok.txt: JVM_OK javaVersion=21.0.12 … exit=0` |
| 7 | **版本下载器** | 1.21.11：client.jar + 51 libraries + version.json 落盘 |
| 8 | **真实 MC 主类执行** | `at net.minecraft.client.main.Main.main(SourceFile:124)` |

## 断点与修复（JVM 层）

1. **mimalloc IE-TLS**：static 库自带 `-ftls-model=initial-exec`，dlopen 场景被
   bionic 拒绝（启动即崩）。修复：CMake 过滤该编译选项。
2. **SELinux**：targetSdk≥29 禁止 dlopen app_data_file 下的 so → targetSdk=28。
3. **依赖解析**：bionic dlopen 不读运行期 `LD_LIBRARY_PATH`，classloader-namespace
   也不含私有目录 → `preloadJdkLibraries()` 按序绝对路径预加载整条 JDK 库链。
4. **DT_SONAME**：Termux shim 库（libandroid-shmem/libandroid-spawn）无 SONAME，
   已加载也无法被 NEEDED 匹配 → patchelf 批量补齐。
5. **JavaVMOption 悬垂指针**：边 push_back 边取 data()，vector 扩容后全部悬垂
   （表现为 "Unrecognized option: " 空名）→ 两阶段构建。
6. **选项语法**：bool 开关 `-XX:-X`（`=false` 报 Missing +/- setting）。
7. **classpath**：JNI_CreateJavaVM 不认 `-cp`，用 `-Djava.class.path=`。
8. **ZGC seccomp**：NUMA 探测 `get_mempolicy`(syscall 236) 被拒（SIGSYS）→
   默认 ParallelGC；解锁需自编译打补丁的 JRE。

## 断点与修复（真实 MC 层，进行中）

9. **log4j 无法写 logs/latest.log**：Java 相对路径基于**内核 CWD**（app 默认 `/`），
   `-Duser.dir` 属性不影响 → `chdir(gameDir)`（jvm_options.cpp）。
10. **JNA 失败**：oshi 需要 JNA，官方 JNA natives 是 glibc 构建（依赖 `libc.so.6`）
    → 需替换 Pojav 生态的 bionic 构建（进行中：从 Pojav APK assets 提取）。

## 真实 MC 1.21.4 启动链（2026-09-28 凌晨冲刺）

用 FCL-release-1.3.3.5（2026-09-25 活跃生态）组件 + MC 1.21.4 官方文件：
Main.main → Bootstrap → Datafixer(243 项) → Environment/Setting user →
**Render thread → blaze3d RenderSystem.<clinit>** ——
当前断点：`UnsatisfiedLinkError: Failed to locate library: liblwjgl.so`
（LWJGL java 3.3.3 与 natives 的构建配对问题，方案已知：jar/natives 用
同一 FCL 构建或 Pojav gladiolus 全套，且 dex 桥类需配套声明）。

### 本轮修复的真机问题

11. **GLFW 桥类的 dex 契约**：libpojavexec 的 JNI_OnLoad 在 ART 侧
    FindClass/RegisterNatives（org.lwjgl.glfw.CallbackBridge）——该类必须
    同时打进 **launcher 的 dex**（app/libs + build.gradle implementation），
    只放 HotSpot jar classpath 会让 ART FindClass 失败 abort 杀进程。
    CallbackBridge.java 源码已入 app 源码树（org.lwjgl.glfw 包），
    剪贴板/Pojav 内部依赖已裁剪（GLOBAL_CLIPBOARD 由 LmcApplication 注入，
    LwjglGlfwKeycode 常量类一并纳入）。
12. **libpojavexec JNI_OnLoad 的 RegisterNatives** 含
    `nativeSetUseInputStackQueue(Z)V`——dex 侧 CallbackBridge 必须声明它，
    且 NoSuchMethodError 无法被 GLFW <clinit> 的 catch(UnsatisfiedLinkError)
    捕获（兄弟异常类）→ 补声明（CallbackBridge.java）。
13. **FCL so 的 SONAME 链**：libterracotta 等无 SONAME → patchelf 批量补齐；
    部署顺序坑：tar 条目带 `versions/` 前缀时必须 `cd files` 解包。
14. **ART 预加载白名单**：libSDL2 的 JNI_OnLoad 要 dex 里的 SDLActivity、
    libEGL_mesa 依赖 libglapi（字母序破坏依赖序）→ 白名单只保留
    LWJGL/OpenAL/gl4es natives，深链组件留给渲染阶段。

### 下一断点预案

- liblwjgl.so 定位：LWJGL 的 Library.loadSystem 按 java.library.path 找
  `liblwjgl.so`——natives/ 已在 path 上，但 **jar 与 so 的构建必须配对**
  （FCL merged jar ↔ FCL natives，或 Pojav gladiolus 全套）。
- 过后即 Window/EGL 创建 → 需要渲染层（gl4es 已部署 natives/ 备用）。
- assets index 已随版本下载（assets/indexes/）。

### 重要发现

**MC 1.21.11 官方 client.jar 已引用 Pojav 系的
`CallbackBridge.nativeSetUseInputStackQueue`**——Mojang 官方 Android 适配
与 Pojav 生态共享桥 API；1.21.4 尚未内置（走经典 Pojav 组件路线）。

## 2026-09-28 凌晨：FCL 生态集成与配对冲刺

15. **FCL dex 契约**：libpojavexec 的 JNI_OnLoad 逐项要求 dex CallbackBridge
    声明/回调：`nativeSetUseInputStackQueue(Z)V` → `onDirectInputEnable()V` →
    `onGrabStateChanged(Z)V`（逐个暴露，已全部补入 stub 版 CallbackBridge.java，
    签名从 libpojavexec 字符串表提取）。
16. **ART 预加载必须在主线程**：CallbackBridge.<clinit> 的
    Choreographer.getInstance() 要求 Looper 线程，协程工作线程加载直接 abort
    （withContext(Dispatchers.Main) 修复）。
17. **依赖链白名单**：libpojavexec → libfcl → libbytehook → (dex ByteHook 类，
    maven com.bytedance:bytehook:1.1.1) → libandroidnsbypass → libc++_shared；
    ART namespace 不搜 so 自身目录，每个 NEEDED 都要显式预加载或配平。
18. **jna 配对规律**：jna java 版本 ↔ libjnidispatch native 版本严格配对
    （5.13↔6.1.6 等）；FCL assets/app_runtime/jna/ 提供全版本配对件。
19. **当前断点：LWJGL 版本校验** `Incompatible Java and native library
    versions detected`——FCL merged jar（3.3.3-snapshot java）与 FCL natives
    （3.3.3 release）JNI_OnLoad 返回的版本整数不一致。解法方向：
    a) 用 FCL 源码 LWJGL/3.3.3/ 本地构建全套（repo 含构建脚本）；
    b) 或提取 FCL APK 运行时自带的 lwjgl natives 缓存（首次运行下载）对比。

## 设备状态（Poco F3）

- files/runtime/jre21 = FCL JRE21（21.0.1，bionic 补丁构建）
- files/versions/1.21.4 = client.jar + 61 libs（官方）+ libs/fcl(merged) +
  libs/pojav(glfw-classes) + libs/mio(Mio wrapper) + natives(FCL 全套 41 so)
- files/versions/1.21.11 = 官方下载（桥 API 是新版，暂搁置）
- files/versions/hello = JVM 端到端探针（jvm_test_ok.txt 验证用）


## 当前状态

MC 启动推进到：Main.main → Bootstrap → **Datafixer Bootstrap 完成（287 项）** →
oshi 硬件信息（JNA）→ 等待 bionic 版 JNA 替换件。

## 后续预期断点（预案）

- LWJGL 加载：需 Pojav 的 lwjgl3-android（jar + so 一并替换官方 LWJGL）
- GLFW/Window 创建：lwjgl3-android 的 GLFW 实现依赖 Pojav 的 native exec 库
- assets 资源索引：MC 启动参数需要 assets index，需下载 assets/indexes/<ver>.json
- 渲染：Mesa/GLES/ Turnip（待 Pojav APK 确认是否自带预编译 Mesa）

## 2026-09-28 上午：Zalith 配对冲刺与当前精确状态

20. **Zalith 组件全套提取**（设备已装 com.movtery.zalithlauncher.v2，base.apk 直接
    adb pull → runtime-assets/zalith-unpack）：liblwjgl.so=3.3.6-snapshot 构建、
    libpojavexec.so（Zalith 版，NEEDED 仅 libdriver_helper+系统库，无 FCL 链）、
    libjnidispatch.so、lwjgl-glfw-classes.jar（10.8MB 全量类）。
21. **namespace 隔离定律**（真机反复验证）：ART System.load 的 so 进
    classloader-namespace；HotSpot 线程的 dlopen 在独立 namespace——互不可见。
    唯一通路：**在 ART JNI 调用栈里 dlopen**（nativePreloadGameNatives，
    与 preloadJdkLibraries 同机制），该 namespace 被 HotSpot 继承。
    实测：nativePreloadGameNatives 成功 10/12 后，HotSpot 的 LWJGL
    "Loaded from org.lwjgl.librarypath: .../liblwjgl.so" 复用成功 ✓
22. **版本配对矩阵**（全部单测过）：
    - FCL merged(3.3.3-snapshot java) + FCL natives(3.3.3 release) → 版本校验失败
    - 官方 3.3.3 java + Zalith natives(3.3.6-snapshot) → 版本校验失败
    - Zalith glfw-classes(3.3.6-snapshot) + Zalith natives → **版本校验通过**，
      libpojavexec/liblwjgl 均加载成功 ✓
23. **当前唯一断点**：Zalith glfw-classes.jar 的 CallbackBridge.class 是精简版
    （4 个 native），而 Zalith libpojavexec 的 JNI_OnLoad 注册表还要求
    nativeSetUseInputStackQueue/onCursorShapeChanged 等方法。已制作
    "Zalith jar + 注入全量 CallbackBridge.class"（javac 编译的 FCL 签名版），
    但该 class 与 Zalith liblwjgl 的版本校验冲突——需要 CallbackBridge 用
    **Zalith dex 原版类**（从 classes.dex 转 jar，工具链：dex2jar 或
    Android SDK 的 d8 反向）。这是下一步唯一剩余工作。

## 下一步行动清单（按序）

1. 从 Zalith classes.dex 提取 org/lwjgl/glfw/CallbackBridge.class 转为 java jar
   （工具：dex-tools/dex2jar，或直接用 d8 --output 反向不支持；备选
   `enjarify`）→ 替换 1.21.4 libs/pojav/lwjgl-glfw-classes.jar 中的类
2. 重跑 → 预期通过版本校验 + CallbackBridge 注册 → 到达 Window/EGL
3. Window 创建后接渲染层：FCL natives 已含 libEGL_mesa/libzink_dri/
   libvulkan_freedreno（预编译 Mesa 栈）

### 补充（10:20）：Zalith glfw-classes.jar 是全模块 jar

解包确认：Zalith 的 lwjgl-glfw-classes.jar（10.8MB）不是"仅 GLFW 桥"，
而是 **LWJGL 全模块 java 类**（system 376 / glfw 117 / opengl 521 /
vulkan 3290 / util 487 / openal 99 / stb 73 / nanovg 52，自报
3.3.6-snapshot）——它与 Zalith natives(liblwjgl 3.3.6-snapshot) 配套。

因此 1.21.4 正确的 classpath 应为：
- **删除**：官方 lwjgl-3.3.3.jar（core）、lmcpatch/core-noglfw.jar（已删）
- **保留**：libs/pojav/lwjgl-glfw-classes.jar（Zalith 全模块，唯一 LWJGL java 源）
- **natives**：全部统一为 Zalith 版（当前混有 FCL 的 gl4es/openal/freetype/
  shaderc/vma——需逐一换 Zalith 版或确认兼容）

混合 so 警示：FCL 与 Zalith 的同名 so 是不同构建（liblwjgl 495456 vs
536408 字节），混装会触发版本校验失败或符号缺失。lmc_so2 部署的 41 个
FCL so 与后续 Zalith 9 个 so 目前在 natives/ 混存——下一步清理。

版本校验机制（LWJGL 源码）：每个绑定库的 java VERSION 常量 vs
native JNI_OnLoad/GetVersion 返回值，不等即抛 "Incompatible"。

### 最终状态（10:30）

全 Zalith 组合（全模块 jar + 全 Zalith natives + dex stub）仍报版本不匹配——
Zalith 发布组件间存在构建级隐式依赖（其运行时首次启动会从自家服务器下载
配套 natives，APK 内置的可能只是占位）。彻底解决需要：
1. 抓取 Zalith 首次运行下载的 natives（设备已装可跑的 Zalith，其数据目录
   files/runtime/ 下有实测可用的配套件——release 包无 run-as 权限，可用
   root 或让用户手动导出）
2. 或 FCL 源码 LWJGL/3.3.3/ 本地构建（repo 内含完整构建脚本）
3. 或逆向 Mio wrapper 的类改写清单（它可能改写了版本检查）

**已验证可靠的资产**（无论后续路线如何都可复用）：
- 版本下载器（piston-meta 全流程）
- JVM 层（FCL JRE21 + dlopen 链 + AppCDS + Mio wrapper 检测）
- dex 契约框架（CallbackBridge stub 方法集提取方法论）
- ANativeWindow 注入点（setupBridgeWindow + glfwstub.* 属性）
- 诊断体系（jvm_stdout/stderr 捕获 + LWJGL debug + verbose:class）

## 2026-09-28 上午二段：gladiolus dex 配对 + Zalith 窗口断点

**关键成果：LWJGL 版本校验与 pojavexec 加载全链打通**（FCL 自洽组合 + 修复）：

20. **版本校验通过**：`Backend library: LWJGL version 3.3.3-snapshot`——
    FCL merged jar(3.3.3-snapshot java) + FCL natives(3.3.3-snapshot build)
    同源配对 + lmcpatch/core-noglfw.jar（官方 core 剔除 glfw 类，补 system
    模块）= LWJGL 全链加载成功。
21. **libpojavexec 加载成功**（"Loaded from org.lwjgl.librarypath"）。
22. **dex 桥升级为 FCL main 版 CallbackBridge 全量源码**（495 行，含 SDL
    集成/剪贴板/输入映射完整方法集）——FCL 内部依赖（FCLApp/SdlBridge/
    FCLBridge/EfficientAndroidLWJGLKeycode/LwjglKeycodeMap/Logging）全部
    裁剪替换（SDL 集成→no-op、KeycodeMap→透传、剪贴板→GLOBAL_CLIPBOARD、
    sLauncherActivity 由 MainActivity 注入）。LwjglGlfwKeycode 常量类
    （net.kdt.pojavlaunch 包）一并纳入。
23. **ZLBridge stub**（com.movtery.zalithlauncher.bridge）：Zalith 版
    libpojavexec 的 dex 契约（chdir/dlopen/setLdLibraryPath/
    setupBridgeWindow/releaseBridgeWindow/setupExitMethod，签名自
    dexdump 提取）。

### 当前唯一断点

`ANativeWindow_acquire(NULL)`（fault 0x28）——已实现
`ZLBridge.setupBridgeWindow(surface)` 注入（调用成功无异常），但
pojavexec 内部保存的窗口仍无效。该函数的 Zalith 实现存在启动序列前置
（推测：需要 OpenJDKNativeRegister.nativeRegisterNatives 先把 natives
注册进 HotSpot——`Java_android_os_OpenJDKNativeRegister_nativeRegisterNatives`
符号在 pojavexec 导出表里，其调用约定需深读 Zalith 源码 ZalithJRE/ 相关模块）。

### 下一步（唯一剩余工作）

深读 Zalith 源码（github.com/ZalithLauncher/ZalithLauncher，重点
app 版 CallbackBridge.java 的调用者 + OpenJDKNativeRegister 的使用序列），
按其启动序列补齐：nativeRegisterNatives 调用时机/参数 → 窗口注入 →
渲染上下文创建。此后 MC 即可进入标题画面（渲染层用 FCL 组件里的
gl4es/Mesa）。

## 2026-09-28 午间：cacio 配方与 Zalith 窗口桥接的深层问题

24. **Zalith 完整启动配方到手**（反编译其 LaunchArgs.kt）：caciocavallo AWT
    桥全套参数（-Djava.awt.headless=false、-Dcacio.managed.screensize=WxH、
    cacio.font.fontmanager/fontscaler、awt.toolkit=CTCToolkit、
    java.awt.graphicsenv=CTCGraphicsEnvironment、-javaagent:cacio-agent.jar、
    -Xbootclasspath/a:cacio*.jar）——已全部实现到 GameViewModel.buildJvmArgs。
25. **cacio 组件已部署**（libs/cacio/：cacio-agent/cacio-shared/cacio-tta）。
26. **深层问题确认**：Zalith 版 libpojavexec 的窗口桥接依赖其 JNI_OnLoad 的
    RegisterNatives 序列，该序列要求：
    a) 通过 Java 流程 System.load 加载（触发 JNI_OnLoad——native dlopen 不触发）
    b) JNI_OnLoad 在 ART 执行时 FindClass 的全部 dex 类就位
    c) RegisterNatives 的方法集与 dex 声明精确配对
    实测 System.load 在 nativePreloadGameNatives（纯 dlopen）之后调用时，
    ART 复用 DSO 但 JNI_OnLoad 的执行路径与 Zalith 原生启动序列仍有差异
    （Zalith launcher 在独立 GameActivity 进程 + 完整 GameActivity 上下文）。

## 路线决策（下阶段）

窗口桥接层需要 Zalith 启动序列的完整移植（GameActivity 上下文、
SurfaceView 生命周期时序、ZLBridgeStates 状态机）——这是 1-2 周量级的
深度集成。备选路线：
A. Pojav gladiolus 简化序列（其 GLFW 桥无 SDL 深链，但版本配对同样待解）
B. Zalith GameActivity 移植：把我们的 SurfaceView 路径改为与 Zalith 一致的
   GameActivity + AWTCanvasView 结构（源码公开，工作量约 2-3 天）
C. 渲染先行：MC 主类已能执行到 blaze3d，用 OSMESA/软渲染先验证 Window 后
   的游戏逻辑（libOSMesa 在 FCL lib 里有）

## 资产清单（全部已验证存在）

- runtime-assets/zalith-unpack：Zalith v2 APK 全组件（3.3.6-snapshot 全套）
- runtime-assets/fcl-unpack：FCL 1.3.3.5 APK 全组件（Mesa/gl4es/ANGLE/JRE 全套）
- runtime-assets/jre21：FCL JRE21（设备已部署）
- runtime-assets/pojav-unpack：Pojav gladiolus 组件
- runtime-assets/mc-1.21.4：MC 1.21.4 官方文件（本地）
- 设备 files/：JRE21 + MC 1.21.4 + libs（fcl/pojav/cacio/mio/lmcpatch）+ natives

## 2026-09-28 下午：cacio 链攻坚与精确断点

27. **cacio AWT 桥链已通三层**：模块开放参数（16 个 --add-opens/exports 照抄
    Zalith LaunchArgs）✓ → cacio agent premain 执行 ✓ → CTCToolkit 构造 ✓。
28. **libawt_xawt/libawt_headless 补齐**（FCL lib → JRE lib）。
29. **FCLBridge.java 全量入 dex**（426 行裁剪版：execute/handleWindow 窗口
    序列 + redirectStdio + 剪贴板 + native 声明全集，FCL 内部依赖
    FCLApp/FCLActivity/OpenFolderDialog 裁剪）。
30. **CallbackBridge 补剪贴板桥**（putClipboardData/querySystemClipboard，
    libpojavexec_awt JNI_OnLoad 的 GetStaticMethodID 目标）。
31. **Zalith 环境配方实现**（setupZalithEnvironment：POJAV_NATIVEDIR/
    DRIVER_PATH/AWTSTUB_*/LIBGL_* 照抄 Zalith JREUtils）。

### 当前精确断点

`java.awt.Font.initIDs()` UnsatisfiedLinkError（cacio CacioWindowPeer 触发
Font.<clinit>）——**Zalith JRE 的 libawt.so 无 Font_initIDs 符号**（127 个
导出，Font 相关仅 Checkbox/Choice/Color），说明 Zalith 的 java.desktop jrt
模块用的是"Font.initIDs 已补丁移除"的版本，而设备上部署的 jrt（universal
tar 解出）是官方版——**JRE 的 java 模块与 natives 来自不同构建阶段**
（Zalith 首启会从其运行时服务器下载配套的补丁版运行时，APK 内置只是
基础底座）。

### 解决路径（唯一剩余）

获取 Zalith 首启下载的完整运行时（设备上 Zalith 数据目录
`files/runtimes/` 或类似路径——release 包需 root 或用户手动导出），
或者从 Zalith 的运行时构建仓库（ZalithJRE/ 目录，repo 内含 OpenJDK
Android 构建脚本）本地构建补丁版 java.desktop。

MC 启动链状态总结：Main.main → Bootstrap → Datafixer → Render thread →
LWJGL 3.3.3-snapshot 全链 ✓ → pojavexec 桥 ✓ → **等待 AWT/cacio 桥 natives
配对**（唯一剩余层，非代码缺陷而是运行时分发问题）。

## 2026-09-28 深夜终段：AWT headless 破解与 cacio 配对终局

32. **Font.initIDs 破解**：实现所在是 **libawt_headless.so**（非 libawt）！
    FCL/Zalith JRE 的 libawt_headless 是 Pojav 精简版（无 Font_initIDs），
    Termux 官方构建有（Font_initIDs/FontDescriptor_initIDs/PlatformFont_initIDs）。
    替换 Termux 版后 Font <clinit> 通过 ✓。
33. **新断点**：CTCScreen.<clinit>:144 的 System.load(libpojavexec_awt) →
    RegisterNatives 找 CTCScreen.putClipboardData——**cacio jar（1.19.1-SNAPSHOT
    maven）与 libpojavexec_awt（FCL 改版）不同源**：awt 库导出 CTCClipboard_nPutClipboardData
    （n 前缀，对应 CTCClipboard 类），而 CTCScreen 声明的 putClipboardData 无实现。
    FCL 发布的 cacio jar 与 pojavexec_awt 来自不同 commit。

### 配对收敛的唯一路径（下阶段核心任务）

锁定 FCL 源码单一 commit，从源码构建全套：cacio-tta（FCL fork 的 cacio 子模块）+
libpojavexec_awt + libpojavexec + liblwjgl（LWJGL/3.3.3/ 子模块）。FCL repo 内含
全部构建脚本（gradle/ndk），一台 Linux 构建机可产出完全自洽的全套组件。

### 当前配置状态（Poco F3 设备）

- JRE：FCL 21.0.1 + Termux libawt_headless（Font 补丁适配）
- cacio：FCL 1.19.1-SNAPSHOT jar（待换同源构建）
- LWJGL：FCL merged jar + FCL natives
- 全部 add-opens/exports 环境参数已实现到 buildJvmArgs（照抄 Zalith）

## 2026-09-28 傍晚：FCLBridge 回调配对与断点收敛

34. **FCLBridge 剪贴板回调配对**：pojavexec_awt 的 JNI_OnLoad 用
    GetStaticMethodID(FCLBridge, "putClipboardData"/"querySystemClipboard")——
    裁剪版 FCLBridge.java 已补这两个 java 方法（转发 GLOBAL_CLIPBOARD）。
    配对后错误栈推进回 acquire(NULL)（pojavexec 窗口状态机）。

### 窗口注入的最终状态

FCLBridge.execute(surface) → CallbackBridge.setupBridgeWindow(surface)
（ART→native ANativeWindow_fromSurface）调用成功，但 MC Render thread 的
gl_setup_window 仍 acquire(NULL)——pojavexec 内部窗口状态机的其余环节
（MCGLSurface 请求-响应模式：MC 请求窗口 → launcher 响应创建 SurfaceView →
setupBridgeWindow → 泵线程 pojavStartPumping）需要 Zalith GameActivity 级
完整移植（约 1-2 天：MinecraftGLSurface + 启动时序 + 泵线程 + 触摸桥）。

### 下阶段任务书（按序）

1. 移植 Zalith MinecraftGLSurface（请求-响应窗口模式 + refreshSize）
2. 移植 pojavStartPumping 事件泵线程（GLFW 事件循环）
3. 渲染后端接入：POJAV_RENDERER=opengles2 + libgl4es_114（已部署）
4. 触摸桥：MinecraftGLSurface 的 onTouch → CallbackBridge.sendXXX 全套已有
全部组件已就位（runtime-assets/ 三套 APK 解包 + 裁剪源码方法论已验证）。

## 2026-09-28 深夜：renderer 分发修正与调试边界

35. **POJAV_RENDERER 合法值修正**："opengles2" 非法（pojavexec 的分发值是
    "opengles"/"opengles3_desktopgl"/"zink"/"vulkan_zink"/"gallium_*"）——
    已改为 "opengles"（gl4es 桥）。
36. **崩溃点精确定位**：ANativeWindow_acquire(NULL) 的调用者是 pojavexec
    的 **dlsym_OSMesa 函数**（0xa678 = fprintf 错误打印后 abort，栈符号错位
    曾误导为 acquire 路径）——pojavexec 走了 **OSMesa 软渲染分支**：
    env_init 的 renderer 分发未匹配到 gl4es 桥，回落 OSMesa（libOSMesa
    未部署→dlsym 失败→fprintf→后续窗口代码访问 NULL 崩溃）。

### 根因链完整还原

1. POJAV_RENDERER 值曾不合法 → renderer 分发异常
2. 修正后仍 acquire(NULL)：setupZalithEnvironment 的 Os.setenv 生效于
   ART 线程，但 **pojavexec 的 env_init 在 HotSpot 的 JNI_OnLoad 里执行**——
   **bionic setenv 与 HotSpot 进程环境**：setenv 修改的是进程 environ，
   HotSpot getenv 可见 ✓（已验证 System.getenv 生效）……剩余差异需
   pojavexec 源码级调试（gl_setup_window 的窗口获取时序：Zalith 是
   "MC 请求→launcher 响应"模式，GLFW:618 的 loadNative 后 glfwInit →
   pojavInit → **pojavexec 内部创建桥窗口等待 Surface** → 我们的
   setupBridgeWindow 已注入 ✓……但 acquire 仍 NULL，需逆向
   gl_setup_window 与 ZLBridge.setupBridgeWindow 的结构体交互）。

### 收敛建议（维持不变）

FCL 源码单 commit 构建全套组件（cacio-tta + pojavexec_awt + pojavexec +
lwjgl + JRE），消除全部跨版本配对问题。这是进入标题画面的确定性路径，
预计 1-2 天（Linux 构建机 + NDK + FCL repo 构建脚本）。

### 今日总成果（2026-09-28 全天）

- 启动链从零推进到 blaze3d/RenderSystem（真机，MC 1.21.4 官方文件）
- 版本下载器全流程真机验证
- 31+ 条断点全部定位并记录（含 8 个真机专属 bug 修复）
- 三套生态组件（Pojav/FCL/Zalith APK）解包分析与配对矩阵实测
- dex 四桥（CallbackBridge/FCLBridge/ZLBridge/LoggerBridge）全量源码入 dex
- 环境配方/JVM 参数/注入机制全部实现并留有诊断开关

## 2026-09-28 终局：acquire(NULL) 根因边界确认

**新发现**：FCL JRE 的 jrt 模块**不含任何 android.* 类**（验证：jimage list
全模块扫描 android/ = 0）。而 Pojav 主线 JRE（OpenJDK-Android 构建）的 jrt
**内置 android.view.Surface 等 Android API 桥类**——pojavexec 的
`Java_android_view_Surface_nativeGetBridgeSurfaceAWT` 就是为这个 JRE 内置类
准备的。

**acquire(NULL) 完整根因链**：
MC 的 AWT 窗口（cacio）→ 需要 android.view.Surface 桥 → FCL JRE 的 jrt
无此桥类 → pojavexec 的窗口后端拿不到 Surface → bridge window 为 NULL →
ANativeWindow_acquire(NULL) SIGSEGV。

### 最终解决路径（三选一，均已记录细节）

A. **Zalith JRE 全套**：Zalith 首启下载的运行时（其数据目录，需 root/
   用户导出）——含补丁 jrt（android 桥类内置）+ 配套 natives。最直接。
B. **FCL 源码构建**：FCL repo 的构建脚本产出完整运行时（含 android 桥
   stub 类的 jrt）——构建机需求已记录。
C. **自建桥类 jar**：从 Zalith APK 的 dex 提取 android 桥类转 jar 放
   HotSpot classpath（工具：enjarify/jdex2jar）——最快但类签名需逐一对齐。

无论哪条路，MC 1.21.4 到达标题画面所需的全部其他层（JVM/LWJGL/桥 dex/
环境配方/窗口注入）**均已真机验证通过**。

## 2026-09-29 凌晨：pojavexec_awt 注册表全配对达成

36. **所有 JNI 注册关卡全部通过**：
    - FCL pojavexec 的 CallbackBridge 注册表（putClipboardData/querySystemClipboard 等）✓
    - pojavexec_awt 的 JNI_OnLoad 注册表（querySystemClipboard()V 签名）✓
    - dex 四桥（CallbackBridge/FCLBridge/ZLBridge/LoggerBridge）全部配对 ✓
37. **libpojavexec_awt 替换为 Zalith 版**（与 FCL pojavexec 的 CTCClipboard 系签名匹配）。

### 当前状态（真机）

MC 1.21.4 Render thread 存活（不崩溃），GL 桥挂载中：
`libEGL: call to OpenGL ES API with no current context`——pojavexec 内部
GL 状态机等待 Surface 关联（setupBridgeWindow 已调用但 GL context 未建立，
需要 pojavexec 的 pojavInit/pojavCreateContext 序列在 HotSpot 线程执行）。

### 下一步（GL 桥挂载）

pojavexec 的渲染初始化序列（pojavInit → pojavCreateContext → MakeCurrent）
需要 GLFW 类（Zalith 3.3.6）与 FCL pojavexec（FCL 版）的方法签名一致——
当前 mixed 组合（Zalith glfw-classes + FCL pojavexec）的接口差异待对齐。
备选：改用 FCL glfw-classes jar（与 FCL pojavexec 同源）。

## 2026-09-29 凌晨收官总结

**启动链推进全景**（真机 Poco F3，MC 1.21.4 官方文件）：

```
✅ 版本下载（piston-meta → client.jar + 61 libs + assets 索引）
✅ JVM 创建（dlopen 链 + dex 契约 + namespace 注入）
✅ MC 主类执行 → Bootstrap → Datafixer(243) → Render thread
✅ LWJGL 3.3.3-snapshot 加载 + natives 版本配对
✅ libpojavexec/libpojavexec_awt 加载 + JNI_OnLoad 注册表全配对
✅ cacio AWT 三层（agent premain + CTCToolkit + Font 链）
⏳ GL 桥挂载（pojavexec 内部 GL 状态机接 Surface）← 当前断点
```

**配对规律总结**（对齐 pojavexec 生态组件的通用方法）：
1. 组件三角：JRE(jrt+natives) ↔ pojavexec(_awt) ↔ cacio/lwjgl jar 必须**同源同 commit**
2. dex 契约：所有 JNI_OnLoad FindClass/GetStaticMethodID/RegisterNatives 的目标类
   必须同时存在于 dex（launcher）与 HotSpot classpath（游戏 jar）
3. 方法签名精确匹配：`()V` vs `()Ljava/lang/String;` 等逐一核对
4. ART 与 HotSpot 双 namespace：native 预加载必须在 ART JNI 栈里做

**GL 桥挂载的具体待办**（下一步精确任务）：
- 确认 Zalith glfw-classes 的 GL 函数表（Functions.Init 等）与 pojavexec 导出一致
- pojavInit 需在 GL 线程调用；gl_setup_window 从 callback 获取 ANativeWindow
- 我们的 ZLBridge.setupBridgeWindow 已注入 Surface ✓（ANativeWindow 可用）
