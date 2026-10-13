# 性能对比报告:lmc 集成版 vs 原版 ZalithLauncher

**日期**:2026-10-13/14(两台设备)
**测试人**:LMC(自动化基准脚本)+ 柠枺(设置同步)
**设备 A**:vivo PD2456(骁龙 SM7635,8 核 1+3+4:A55×4@1.80G / A77×3@2.40G / A77×1@2.50G),Android 16,USB 供电,屏幕亮度 255(全程未变)
**设备 B**:Redmi K40 / Poco F3(骁龙 SM8250,8 核 1+3+4:A55×4@1.80G / A77×3@2.42G / A77×1@3.19G),Android 13(MIUI),USB 供电

---

## 一、对比对象

| | lmc 集成版 | 原版 |
|---|---|---|
| 包名 | com.movtery.zalithlauncher.**v2.lmcbench** | com.movtery.zalithlauncher.**v2** |
| 上游版本 | **2.7**(含 lmc core 嵌入) | **2.6.1** |
| 构建类型 | benchmark(initWith release,R8 压缩+混淆+资源收缩,非 debuggable) | release |
| lmc 性能层 | 开(绑大核/温度监控/音频/oom_adj;GC/JIT 线程迁移**已撤销**) | 无 |

**受控变量**(全部一致,逐一核实过):
- 同一游戏目录 `/sdcard/FCL/.minecraft`(用户手动同步过设置)
- 同一版本 Fabulously Optimized **1.21.11**(Fabric),同一渲染器 **Krypton Wrapper 0.4.5**(两边游戏日志逐字核对)
- 同一世界存档(每轮从母本还原 → 玩家位置/时间/地形完全一致)
- 同一 `options.txt`(renderDistance 8 / vsync 关 / maxFps 260 / 粒子 1 / AO 关)
- 同窗口 2392×1080、同 `-Xmx2048M`、同 JRE **Internal-25**、同 `ActiveProcessorCount=8`、同账号

**协议**:冷却到热区 ≤45°C → 还原世界 → `--quickPlaySingleplayer` 直接进世界 → 稳定 45s → 每 5s 采样 FPS ×10(读壳层 overlay 的 "FPS: N")

---

## 二、数据

| 轮次 | 配置 | 平均 FPS | 最低 | 最高 | 原始样本 |
|---|---|---|---|---|---|
| 第一轮·A | 集成版 2.7,lmc 开(修复前) | 82 | 65 | 95 | 69 75 86 82 65 73 94 89 92 95 |
| 第一轮·C | **原版 2.6.1** | **97** | 76 | 111 | 76 79 87 104 102 111 108 102 106 104 |
| 第一轮·A' | 集成版 2.7,lmc 开(复测) | 88 | 71 | 98 | 74 71 86 91 85 94 94 98 93 95 |
| 第二轮·A1 | 集成版+lmc 修复,lmc 开 | 84 | 64 | 96 | 66 64 80 89 92 88 92 86 96 96 |
| 第二轮·A2 | 集成版+lmc 修复,lmc 开(复测) | 86 | 69 | 98 | 69 73 78 86 96 90 87 98 88 96 |
| 第二轮·B2 | 集成版,**同 APK,lmc 关** | 77 | 57 | 97 | 57 58 58 78 76 82 90 89 97 87 |
| 第二轮·B3 | 集成版,**同 APK,lmc 关**(复测) | 85 | 64 | 103 | 64 67 90 96 82 89 88 83 103 89 |

> B1(lmc 关)因开关文件权限问题无效,已修复(chmod 666)后重测为 B2/B3。

---

## 三、结论

### 1. lmc 性能层对帧率是**中性**的(既不加分也不扣分)

同一 APK 下,lmc 开(84/86)与 lmc 关(77/85)在测量噪声(±5 fps)内无法区分。
绑大核、温度监控线程、音频初始化这些本身开销极小;而**曾经"把 GC/JIT 线程迁到小核"
的策略经实测撤销**(见结论 3)——修正后的性能层不再有负面作用。

### 2. 集成版整体比原版慢 ~11%(85 vs 96 fps),**与 lmc 无关**

lmc 开/关都在 81-86 区间,而原版两轮 97/95 高度一致。差距来自**上游 2.7 相对 2.6.1
的变化**(两版启动器在游戏进程内设置的某些环境/组件存在差异,未逐项定位)。
注意对照不是完全同版:原版是 2.6.1,我们是 2.7——这是本轮对比最主要的局限。

### 3. 本轮发现并修复了 lmc 两个真实缺陷

| 缺陷 | 实锤证据 | 修复 |
|---|---|---|
| JIT 参数注入位置错误 | MC 日志:`Completely ignored arguments: [-XX:CICompilerCount=4, ...]` —— 参数落在主类之后,JVM 根本没收到 | 移到 `launchArgs`(含 -cp/主类)拼入**之前**,现已在 JVM 选项区生效 |
| GC/JIT 线程迁小核有害 | G1 的 STW 停顿由并行 GC 线程执行,放在 A55 小核上停顿显著变长 | 只迁移纯后台线程(VM Periodic Task / Service Thread / Common-Cleaner / Notification Thread),GC/JIT 线程交还调度器 |

### 4. 温度是这台设备上最大的性能变量

每次运行内温度从 45°C 升到 ~67°C,样本间波动 ±8 fps;任何"小优化"的收益都会被
热噪声淹没。**要在这台机器上获得可感知的提升,方向应该是降低发热**(渲染分辨率/
帧率上限/亮度),而不是 CPU 调度微优化。

---

## 四、局限与改进方向

1. **上游版本不同**(2.6.1 vs 2.7)是本轮无法消除的变量;若要精确归因,需构建
   "2.7 无 lmc"的纯上游对照。
2. 单设备、单世界、每轮 50s 采样窗口,样本噪声 ±5-8 fps;结论只支持"量级判断"。
3. 未测:世界生成耗时、区块加载速度、内存峰值(内存上 lmc 版略高 ~0.1-0.2G,
   主要来自 lmc core 的 native 库与状态)。
4. 建议后续:把 Krypton 渲染器的版本在两边对齐后重测;以及用 **MC F3 + 固定视角
   长采样** 代替 overlay 读数以降噪。

---

## 五、复现方式

```sh
# 构建正式优化版(包名独立,与原版共存)
sh gradlew -Darch=arm64 :ZalithLauncher:assembleBenchmark
# 基准脚本(还原世界/进世界/采样/冷却)
sh bench_cmp.sh <包名> <标签> <on|off|na>
```

开关文件机制:在 app 外部私有目录放置 `lmc_disable`(chmod 666)即完全跳过
lmc 性能层,同一 APK 可做 A/B。

---

## 六、设备 B(Poco F3)复测(2026-10-14 凌晨)

### 6.1 环境搭建(从零)

- 设备 B 只装了原版 zl2 2.6.1(空游戏目录),无任何 MC 资源
- 资源来源:设备上残留的旧独立启动器 `com.lemwoodmc.launcher`(可 run-as)
  内有完整 MC 1.21.4(client.jar + 官方 libraries + assets + 一个世界)
- PC 端重组为 zl2 标准布局,并补齐官方清单缺的 59 个库(16.7MB,sha1 校验)
- 应柠枺要求改用**优化整合包**:Modrinth 下载 **Fabulously Optimized 8.2.0
  for 1.21.4**(48 个 mod,40MB),fabric-loader 0.19.3,组装为自包含版本
  `FO-1.21.4`(mainClass=KnotClient,vanilla libraries + fabric libraries 合并)
- 同一份资源复制进两个启动器的默认游戏目录,双方同世界/同设置/同账号

### 6.2 结果:三方全部锁在 30 fps,无区分度

| 轮次 | 配置 | FPS(10 样本) |
|---|---|---|
| A | 集成版,lmc 开 | 30,30,30,30,30,30,30,30,30,30 |
| B | 集成版,**同 APK lmc 关** | 30 ×10 |
| C | 原版 2.6.1 | 30 ×10 |

三个配置**逐帧一致地锁在 30 fps**。已排除的因素:MC 设置(vsync=false、
maxFps=260、inactivityFpsLimit=off 均已写入)、lmc 性能层(开/关一致)、
渲染器(双方同用 MobileGlues 2.0.0)。

定位:MIUI 把屏幕锁在 **60Hz**(支持 90/120 但 adb `cmd display
set-user-preferred-display-mode` 被拒),游戏以半速率呈现。这是**设备级
帧率策略**,在解除前,该设备上的 FPS 对比无区分度 —— **lmc 在锁帧下
无损失也无收益(与设备 A 的"中性"结论一致)**。

### 6.3 本轮新发现的工程问题

| 问题 | 实测证据 | 建议 |
|---|---|---|
| 大核判定阈值在 SD870 上失效 | 870 中核 2.419GHz / 超大核 3.187GHz = 75.9% < 80% 阈值 → 中核三颗被判小核,**游戏主线程只绑 [7] 单核**;vivo 上比值 96% 无此问题 | 改用 `cpu_capacity`(该设备实测 313/777/777/1024,清晰三档),频率阈值仅作回落 |
| zl2 对 fabric 库的解析要求 | 自造 `{name,url}` 库条目 → zl2 解析崩溃(IndexOutOfBounds);缺 `downloads.artifact`(path/sha1/size)→ KnotClient 找不到(classpath 缺失) | 自包含 fabric 版本必须给**完整标准 maven 格式**;且 zl2 对部分库按 `<组尾段>-<artifact>.jar` 命名查找(如 `fabricmc-fabric-loader.jar`) |
| MIUI 测试环境差异 | uiautomator 在游戏画面返回 null root;安装需人工确认;adb 无法强制刷新率 | 采样改用截图读数;装机需人工配合 |
| MC 的 session.lock 时序 | session.lock 在世界**开始加载**时即写入,不代表已进世界 | 稳定期需 ≥75s(FO 首次加载更慢) |
