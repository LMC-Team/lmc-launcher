# 编译说明

## 0. 环境要求

| 组件 | 版本 | 来源 |
|---|---|---|
| JDK | 17+ | `https://adoptium.net` |
| Android SDK | API 35 | `https://developer.android.com/studio` |
| Android NDK | r27 (27.2.12479018) | SDK Manager / `https://developer.android.com/ndk/downloads` |
| CMake | 3.22.1+ | SDK Manager |
| Meson + Ninja | meson ≥ 1.3（仅 Mesa 构建） | `pip install meson ninja` |
| 设备 | ARM64，Android 10+，Vulkan 1.1 | — |

> **已验证构建环境**（2026-09-27）：本仓库在 JDK 17.0.20 + SDK 35 + build-tools 35.0.0 +
> NDK r27c + Gradle 8.10.2 组合下完成了 `assembleDebug` 与 `assembleRelease` 全流程构建：
> debug APK 28.4MB（内含 arm64 `liblmc_core.so`），release 经 R8 混淆后 3.6MB，
> `NativeBridge` JNI 签名在混淆后完整保留（ProGuard 规则生效）。
> 注意 `ndkVersion` 必须与已安装 NDK 精确匹配，否则 AGP 报 `[CXX1101]`。
>
> **已验证真机运行**（Poco F3 / 骁龙 870 / Android 13）：安装启动、native 初始化
> （JNI_OnLoad / CPU 拓扑 1+3+4 → 大核 [4,5,6,7] / AAudio 探测 / OpenAL 配置生成）、
> 主页 UI 渲染、开始游戏全链路（渲染环境布置 → oom_score_adj → 温度监控 →
> sched_setaffinity 绑核成功 → ANativeWindow 挂载 1080x2400）、JVM 缺失时优雅失败
> （UI 显示 exit=-1）——真机发现并修复了三个静态验证抓不到的问题：
> ① mimalloc static 自带的 `-ftls-model=initial-exec` 在 dlopen 场景被 bionic 拒绝
>  （启动即崩），已在 CMake 过滤；② big.LITTLE 分类需按 70% 频率阈值把三簇 SoC 的
>  中核归入大核；③ 控制层坐标混用 dp/px 导致按键挤在左侧 1/density 区域。

> **本项目仅构建 arm64-v8a**，不做其他 ABI 兼容。

## 1. 构建 APK（含 liblmc_core.so）

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

./scripts/build_all.sh
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

> Gradle wrapper 未随仓库提交：用 Android Studio 打开项目会自动配置，
> 或在有 Gradle 8.9+ 的机器上执行 `gradle wrapper --gradle-version 8.9` 生成。
> `build_all.sh` 在 wrapper 缺失时自动回退到系统 `gradle`。

Gradle 会自动调用 `core/CMakeLists.txt` 编译 C++ 核心后端（含 mimalloc，由 CMake
FetchContent 自动拉取 v2.1.7，来源 `github.com/microsoft/mimalloc`）。

### 1.1 仅编译核心后端（无需 Android SDK，快速验证）

只需要 NDK，即可单独编译 `liblmc_core.so` 做验证（CI 与本地调试都适用）：

```bash
cmake -B build-core -S core \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DLMC_USE_MIMALLOC=ON -DLMC_OPT_FLAGS=ON -G Ninja
cmake --build build-core

# 验证导出符号（应有 21 个 JNI 方法 + JNI_OnLoad + ANativeActivity_onCreate）
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D \
  --defined-only build-core/liblmc_core.so | grep -E "Java_com_lemwoodmc|JNI_OnLoad|ANativeActivity_onCreate"
```

> 该路径已在 NDK r27c（clang 18）下完整验证：编译零告警、链接通过、23 个关键导出符号齐全。
> CI 中二次构建时若 FetchContent 下载失败，可加
> `-DFETCHCONTENT_SOURCE_DIR_MIMALLOC=<首次构建>/_deps/mimalloc-src` 复用已拉取源码。

## 2. 构建 JVM 运行时（OpenJDK 21）

默认使用 **Termux 官方源 openjdk-21**（21.0.12，bionic 原生编译，可 dlopen，真机实测可用）：

```bash
./scripts/build_openjdk.sh          # 下载 deb + 解包 → runtime-assets/jre21/
./scripts/deploy_runtime_test.sh deploy   # 可选：把 JRE + hello 测试版本部署到真机
./scripts/deploy_runtime_test.sh read     # 回读真机 JVM 端到端测试结果
```

**真机部署要点**（deploy 脚本已自动化）：
- JRE 的 so 位于应用私有目录，bionic dlopen 的依赖解析不读运行期 `LD_LIBRARY_PATH`，
  必须按绝对路径预加载整条库链（`jvm_loader.cpp` 的 preloadJdkLibraries）；
- Termux 的 shim 库 `libandroid-shmem/libandroid-spawn` 无 DT_SONAME，需 patchelf 补齐；
- **targetSdk 必须为 28**：Android 10+ 上 targetSdk≥29 被 SELinux 禁止 dlopen
  app_data_file 下的 so（所有同类启动器的通行做法）；
- **ZGC 不可用**：其 NUMA 探测调用 get_mempolicy 被应用 seccomp 拒绝（SIGSYS），
  默认 GC 已设为 ParallelGC；解锁 ZGC 需自编译时打 NUMA 探测禁用补丁（方案 B）。

自编译方案 B 见 `scripts/build_openjdk.sh` 头部注释
（源码：`https://github.com/termux/termux-packages` openjdk-21 包）。

## 3. 构建 Mesa（Zink + Turnip）

需要本机安装 meson/ninja/pkg-config/flex/bison 与 python3-mako：

```bash
pip install meson ninja mako
sudo apt install flex bison pkg-config   # 或对应发行版包管理器

./scripts/build_mesa.sh
# 产物: runtime-assets/mesa/
#   libGL.so.1 / libGLESv2.so / libgallium*.so   ← Zink
#   libvulkan_turnip.so + turnip_icd.aarch64.json ← Turnip
```

交叉编译配置在 `scripts/mesa-cross-aarch64.txt`，关键 meson 选项：

- `-Dgallium-drivers=zink` — GL on Vulkan 主路径
- `-Dvulkan-drivers=[],freedreno` — Turnip（Adreno 开源驱动）
- `-Dshader-cache=enabled` — Mesa 着色器缓存持久化
- `-Dplatforms=[] -Degl=disabled` — surfaceless 配置，直接吃 ANativeWindow

非骁龙设备（Mali/PowerVR）去掉 `freedreno`，走系统闭源 Vulkan 驱动（Zink 照常工作）。

## 4. 构建 gl4es（保底路径）

```bash
./scripts/build_gl4es.sh
# 产物: runtime-assets/gl4es/libgl4es_114.so
```

## 5. 构建 OpenAL-Soft（音频）

```bash
./scripts/build_openal.sh
# 产物: runtime-assets/openal/libopenal.so （AAudio + OpenSL 双后端）
```

## 6. 部署到设备

```bash
adb install app/build/outputs/apk/debug/app-debug.apk

adb shell run-as com.lemwoodmc.launcher mkdir -p files/runtime
adb push runtime-assets/jre21  /data/local/tmp/ && adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/jre21  files/runtime/
adb push runtime-assets/mesa   /data/local/tmp/ && adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/mesa   files/runtime/
adb push runtime-assets/gl4es  /data/local/tmp/ && adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/gl4es  files/runtime/
adb push runtime-assets/openal /data/local/tmp/ && adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/openal files/runtime/
```

最终设备目录布局（`/data/data/com.lemwoodmc.launcher/files/`）：

```
files/
├── runtime/
│   ├── jre21/lib/server/libjvm.so     # JVM 加载器 dlopen 目标
│   ├── mesa/{libGL.so.1, libvulkan_turnip.so, turnip_icd.aarch64.json, ...}
│   ├── gl4es/libgl4es_114.so
│   ├── openal/libopenal.so
│   └── graal/lmc-native               # GraalVM AOT 产物（预留，可选）
├── minecraft/                          # user.home / 游戏目录
├── openalsoft/alsoft.conf              # 由 lmc_core 自动生成
└── (cache/) app.jsa                    # AppCDS 自动生成（见下）
```

## 7. AppCDS 与首启加速

`core/src/jvm/jvm_options.cpp` 默认追加 `-XX:+AutoCreateSharedArchive -Xshare:auto`：
首次启动游戏时 JVM 自动把已加载类写入 `cache/app.jsa`，后续启动直接 mmap。
离线预生成方式见 `scripts/gen_appcds.sh`。

## 8. 验证

```bash
adb logcat -s LMC-Core          # 核心后端日志（拓扑/渲染/温度）
adb logcat -s LMC               # Kotlin 侧日志
```

正常应看到：

```
CPU 拓扑: 8 核, 大核 [4,5,6,7], 小核 [0,1,2,3]
AAudio MMAP: 支持, 采样率 48000
渲染环境: Zink(/data/.../runtime/mesa) + Turnip
游戏主线程 -> 大核 [4,5,6,7]: 成功
JVM 服务线程迁核: 12/19 -> 小核 [0,1,2,3]
```

## 9. 常见问题

- **dlopen libjvm.so 失败**：runtime 未部署或布局不对（必须 `lib/server/libjvm.so`）；
  `LD_LIBRARY_PATH` 已在 native 侧自动设置，无需手工干预。
- **Turnip 未生效**：检查 `files/runtime/mesa/turnip_icd.aarch64.json` 是否存在且
  `library_path` 指向 `libvulkan_turnip.so`；`adb shell dumpsys SurfaceFlinger | grep -i gpu` 看驱动加载。
- **ZGC 启动失败**：部分内核限制了 ZGC 需要的虚拟地址预留，回落 ParallelGC
  （设置页切换 GC，对应 `-XX:+UseParallelGC`）。
