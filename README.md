# LMC Launcher —— 嵌入 ZalithLauncher 2 的高性能 Minecraft Android 启动后端

以**性能为最高优先级**的 Minecraft Java 版 Android 运行时后端。
当前形态:**`liblmc_core.so` 性能层嵌入 [ZalithLauncher 2](https://github.com/ZalithLauncher/ZalithLauncher2)**(分支 `zl2-shell`),为壳提供 CPU 调度、温度管理、状态可见化等原生能力。

## 当前状态(2026-10)

- 自研启动器壳的开发暂停(完整断点记录见 [docs/PROGRESS.md](docs/PROGRESS.md) #1~#54),
  核心能力以**性能层**形式接入 ZalithLauncher 2(#55 起)
- 已随 zl2 在真机完整跑通:**MC 1.21.11(Fabulously Optimized 8.0.3)** 与
  **MC 1.21.4(FO 8.2.0)**,渲染走 MobileGlues / Krypton 插件
- CI:`main` 分支 [build-apk](https://github.com/LMC-Team/lmc-launcher/actions) 构建通过

## lmc core(liblmc_core.so)提供什么

| 模块 | 能力 | 壳内接入点 |
|---|---|---|
| sched | big.LITTLE 拓扑识别、游戏主线程绑大核、JVM 纯后台线程迁核 | 启动前自动执行,日志 `[LMC]` |
| sched | 温度监控:采样 CPU 温区,滑动窗口线性外推,提前预警(Info/预警/严重) | 壳内 Toast(30s 冷却)+ 日志 |
| mem | oom_score_adj 调整(非 root 受 SELinux 限制,自动降级) | 启动前 |
| audio | OpenAL-Soft 配置生成、AAudio MMAP 探测 | 启动前 |
| 桥 | `NativeBridge`(Kotlin↔C++),标准 JNI 命名,R8 下由 [consumer-rules.pro](lmc-core/consumer-rules.pro) 保护 | — |
| UI | 设置 → 关于 → **「LMC 核心状态」实时面板**(拓扑/绑核/温度,2s 轮询) | 游戏进程 3s 写 `lmc_status.json`,跨进程读取 |

温度预警阈值 80°C、预测窗 8s;仅在上坡趋势(>0.02°C/s)且预测超阈值时触发,
斜率限幅 ±0.3°C/s 防误报(真机校准)。

## 构建

环境:JDK 21(Temurin)、NDK 27、CMake 3.22、Android SDK。

```sh
# 调试版(含全部 ABI 时 ~404MB;arm64 单架构 216MB)
JAVA_HOME=<jdk21> sh gradlew -Darch=arm64 :ZalithLauncher:assembleDebug

# 正式优化版(R8 压缩+资源收缩,独立包名 *.v2.lmcbench,可与原版共存)
JAVA_HOME=<jdk21> sh gradlew -Darch=arm64 :ZalithLauncher:assembleBenchmark
```

- **基准开关**:在 app 外部私有目录放置 `lmc_disable`(需 chmod 666)即完全跳过
  性能层 —— 同一 APK 可做严格 A/B
- **Windows 注意**:提交后检查 `gradlew`/`*.sh` 的 git mode(丢失可执行位会让
  Linux CI 以退出码 126 全线失败;修复用 `git update-index --chmod=+x`,
  之后**不要再 `git add`**)

## 性能对比(详见 [docs/BENCHMARK-2026-10-13.md](docs/BENCHMARK-2026-10-13.md))

受控协议:同一游戏目录/版本/世界(逐轮还原母本)/画质/账号,冷却至同温度起点,
`--quickPlaySingleplayer` 直进世界,定时采样。同 APK 的 lmc 开/关用开关文件切换。

| 设备 | 结论 |
|---|---|
| vivo PD2456(SM7635,Android 16) | lmc 性能层**对帧率中性**(开 84-88 vs 关 77-85);集成版整体比原版 2.6.1 慢 ~11%(85 vs 96),**与 lmc 无关**,为上游 2.7→2.6.1 差异 |
| Redmi K40(SM8250,MIUI 13) | 三方(开/关/原版)一致锁 30 fps —— MIUI 显示策略锁 60Hz 半速率,**无区分度**;锁帧下 lmc 无损失 |

**核心洞察**:游戏内温度每轮从 45°C 升至 ~67°C,样本噪声 ±8 fps ——
在这类设备上,可感知的性能提升来自**降发热**(渲染分辨率/帧率上限/亮度),
而非 CPU 调度微优化。

## 已知问题与后续

1. **大核判定在 SD870 上失效**:频率阈值(≥最高簇 80%)遇到
   2.419/3.187GHz(比值 75.9%)把中核三颗判成小核,主线程只绑单核。
   设备 `cpu_capacity` 信号干净(313/777/1024),应改用 capacity 判定、频率作回落。
2. `oom_score_adj` 非 root 无法写负值(SELinux),该优化实际为 no-op。
3. CI 的 `actions/checkout@v4`、`setup-java@v4` 有 Node 20 弃用告警,待升级 v5+。

## 文档索引

- [docs/PROGRESS.md](docs/PROGRESS.md) —— 全部断点与根因分析(#1~#71)
- [docs/BENCHMARK-2026-10-13.md](docs/BENCHMARK-2026-10-13.md) —— 两台设备的受控性能对比

## 分支说明

- `zl2-shell` —— zl2 集成开发分支(当前活跃)
- `main` —— CI 构建分支(build-apk)

## License

遵循上游 ZalithLauncher 2 与各依赖组件的许可证;自研部分见仓库历史。
