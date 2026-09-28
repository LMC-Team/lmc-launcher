# 架构与性能设计

## 总体分层

```
┌────────────────────────────────────────────────────────────┐
│  UI 层  Kotlin + Jetpack Compose (Material 3)               │
│  主页 / 设置 / 版本 / 账户 / 悬浮控制层                       │
│  ↕ ViewModel + JNI 状态桥（NativeBridge）                    │
├────────────────────────────────────────────────────────────┤
│  核心后端 liblmc_core.so  C++20 (NDK r27 + CMake)           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ JVM 加载 │ │ 渲染桥接 │ │ CPU 调度 │ │ 输入 / 音频   │  │
│  │ dlopen   │ │ Zink /   │ │ 拓扑识别 │ │ AInputQueue   │  │
│  │ AppCDS   │ │ Vulkan / │ │ 绑核     │ │ AAudio MMAP   │  │
│  │ 参数调优 │ │ gl4es    │ │ 温度预测 │ │ mmap 存档     │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────┘  │
└────────────────────────────────────────────────────────────┘
```

各层只通过 `NativeBridge`（JNI）与 `core_runtime.h`（运行时上下文单例）耦合，
每个模块可独立替换（模块边界见 `core/src/` 目录划分）。

## 1. JVM 运行时层（core/src/jvm/）

**为什么 dlopen 而非 proot**：proot 引入 syscall 翻译开销（Pojav 实测 5~15% 差距）
与双文件系统缓存压力。Termux 补丁版 OpenJDK 21 已把 JDK 适配到 Android bionic
（合并 libc/缺失的 pthread 语义），`JNI_CreateJavaVM` 直接在应用进程内创建 JVM。

- `jvm_loader.cpp`：`dlopen(libjvm.so) → JNI_CreateJavaVM → FindClass/main`
  完整实现；调用线程即游戏主线程（此前已绑大核）。
- `jvm_options.cpp`：
  - 固定堆 `-Xms == -Xmx`：消除堆伸缩抖动与 G1/ZGC 的 resize 停顿；
  - GC 双选：**ZGC 分代**（亚毫秒停顿，骁龙 8 系）/ **ParallelGC**（总吞吐最高，低内存机）；
  - `-XX:CICompilerCount=<大核数>`：JIT 编译线程数匹配 big.LITTLE 拓扑；
  - **AppCDS**：`-XX:+AutoCreateSharedArchive`，首启自动生成 `app.jsa`，
    后续启动 mmap 类存档（类加载提速 30%~40%）。
- **GraalVM Native Image 预留**（`graal/graal_runner.h`）：极致模式 = 直接 exec
  AOT 产物，跳过 JVM/JIT，冷启动 <200ms。产物构建管线在 CI 中完成。

**mimalloc**（2.1.7）：静态链入 lmc_core。Android 应用进程受 SELinux 约束无法
LD_PRELOAD 全局替换，因此收益集中于 native 层分配（渲染中间缓冲、输入队列、
mmap 索引结构）；JVM 堆由 GC 自管 mmap，不受影响——这也是 Pojav 同样不做的部分。

## 2. 渲染层（core/src/render/）

### 主路径：Mesa Zink + Turnip

```
Minecraft (LWJGL GL 3.2+)
   │  libGL.so.1 ← LD_LIBRARY_PATH 指向自编译 Mesa
   ▼
Mesa Zink (Gallium)          GALLIUM_DRIVER=zink
   ▼  GL→Vulkan 翻译，绕过系统 GL 驱动的旧 ARM 路径
Turnip Vulkan (freedreno)    VK_ICD_FILENAMES 指向自编译 ICD
   ▼  Adreno 开源驱动，无闭源驱动的 GL 兼容层开销
ANativeWindow → BufferQueue → 显示
```

Zink 关键调优（`render_bridge.cpp`）：
`ZINK_DESCRIPTORS=lazy`（描述符惰性分配）、`MESA_GL_VERSION_OVERRIDE=4.6FC`、
`mesa_no_error=1`（关闭错误检查）、Mesa shader-cache 目录持久化。

### 备选：原生 Vulkan（VulkanMod 思路）

`vulkan_swapchain.cpp` 提供完整实现：instance → `vkCreateAndroidSurfaceKHR`
（直连 ANativeWindow）→ 设备/队列选择（Turnip 优先，vendorId 0x5143）→
swapchain（BGRA8 + FIFO）→ renderPass/framebuffer → acquire/present。

### 保底：裁剪版 gl4es

只保留 Minecraft 实际使用的 GL 函数子集（Pojav fork），`gl4es_loader` 负责
部署验证。牺牲上限性能换取全设备兼容。

### Pipeline cache 持久化

`pipeline_cache.cpp`：启动时从 `cache/vulkan_pipeline_cache.bin` 加载
`VkPipelineCache` 初始数据，退出/空闲时 `vkGetPipelineCacheData` 落盘——
消除世界加载时的着色器编译卡顿（第二次启动起生效）。

### 显示链路说明

无论哪条路径，最终都提交到 ANativeWindow（BufferQueue 生产者端）。
"绕过 SurfaceFlinger 额外合成"指：使用直扫模式/避免多余离屏层，
游戏画面 SurfaceView 不再叠加 GL 中转层——原生 Vulkan 路径连 Java Surface
抽象都省去（NativeActivity 直接给 ANativeWindow）。

## 3. CPU 调度层（core/src/sched/）

- **拓扑识别** `cpu_topology.cpp`：扫 `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`，
  按频率聚类出集群，最高频集群 = 大核。兼容 2 簇（4+4）与 3 簇（1+4+3）。
- **绑核** `thread_affinity.cpp`：
  - 游戏主线程（CreateJavaVM 调用线程）→ 大核 `sched_setaffinity`；
  - JVM 服务线程（`/proc/self/task/*/comm` 匹配 `GC Thread#`、`C1/C2 CompilerThread`、
    `VM Thread` 等）→ 小核，避免 JIT 编译风暴抢占渲染线程。
- **温度监控** `thermal_monitor.cpp`：2s 周期读 `/sys/class/thermal`，8 样本滑窗
  最小二乘拟合温升斜率，外推 20s 预测温度；越过阈值即分级回调
  （提醒 → 降渲染分辨率 → 激进降载），**在内核温控降频之前主动降载**，
  避免不可控的掉帧悬崖。

## 4. 输入与音频

- **输入**：NativeActivity 路径下框架把 `AInputQueue` 交给 `input_queue.cpp`，
  在 ALooper 回调里排空事件并转译成 GLFW 语义进 `InputHub`（`input_hub.h`），
  全程不经过 Java 事件管线；Compose 悬浮控制层的事件经 JNI 直接注入同一 Hub。
  BACK 键例外：回调 `GameActivity.onNativeToggleOverlay()` 控制控制层显隐。
- **音频**：`audio_backend.cpp` 启动时探测 AAudio MMAP（EXCLUSIVE + LOW_LATENCY，
  `AAudioStream_isMMapUsed` 验证真实通道），生成 `alsoft.conf` 交给 OpenAL-Soft
  的 AAudio 后端（≥1.23 内置）。MMAP 快速路径延迟 ~10ms 级。

## 5. 进程与内存

- `oom_adj.cpp`：写 `/proc/self/oom_score_adj` 尽力请求 lowmemorykiller 保护
  （SELinux 允许的范围内）。
- `mmap_saves.cpp`：region 文件（.mca）`mmap(PROT_READ) + MADV_SEQUENTIAL`，
  以 DirectByteBuffer 暴露给 JVM 侧零拷贝解析。

## 6. UI 层与零开销约定

- 全部页面 Compose Material 3；游戏画面两条路径：
  1. **SurfaceView 内嵌**（`ui/game/GameScreen.kt`）：`AndroidView` 嵌入 SurfaceView，
     Compose 悬浮层叠加；
  2. **NativeActivity 直连**（`game/GameActivity.kt`）：ANativeWindow + AInputQueue
     全 native。Compose 悬浮层使用 **TYPE_APPLICATION_PANEL 独立窗口**
     （`game/GameComposeOverlayHost.kt`）——NativeActivity 的游戏窗口输入全部交给
     AInputQueue，叠加 View 收不到事件，因此控制层必须是拥有自己 InputChannel 的
     panel 窗口，并手工注入 Lifecycle/ViewModelStore/SavedStateRegistry 三类 owner。
- **输入路由（双层窗口）**：
  - 控制层隐藏：panel 窗口移除，触摸全部进入游戏窗口 → AInputQueue → native 直读，
    UI 零开销；
  - 控制层呼出：panel 窗口拦截触摸，按钮注入 native；背景拖动由 Compose 根布局
    转发回 InputHub，游戏内视角操作不中断；按键（BACK）仍走 native 路径
    （NOT_FOCUSABLE）。
- 游戏运行期 UI ↔ native 状态仅剩两条轻量通道：
  `NativeBridge` 输入注入（下行）与温度回调 `onThermalWarning`（上行，0.5Hz 封顶）。

## 7. 与 PojavLauncher / Boat 的取舍

| 决策点 | PojavLauncher | Boat | LMC 选择 |
|---|---|---|---|
| JVM 容器 | proot（兼容优先） | 无容器、直载 | **直载**（同 Boat），版本锁定 JDK21+ |
| GL 翻译 | gl4es 全量 | gl4es 全量 | **Zink 主路径**（GL→Vulkan，驱动端优化空间大） |
| 输入 | Java 转发 | native 直读 | **native 直读**（AInputQueue），UI 层注入走 JNI |
| UI | 原生 View | 原生 View | **Compose**，但运行期可整体卸载 |
| 兼容性 | 多 ABI / 旧系统 | 多设备 | **仅 arm64 / Android 10+** |
