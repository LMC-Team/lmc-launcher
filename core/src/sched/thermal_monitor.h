// 温度监控与主动降频预测：
// 周期读取 /sys/class/thermal/thermal_zone*/temp（毫摄氏度），
// 用滑动窗口线性趋势外推 horizonSec 后的温度，
// 提前回调 UI / 渲染桥做“降分辨率/降帧”保护，而不是等 kernel 温控降频。
#pragma once

#include <functional>

namespace lmc {

/** 预警严重度（与 Kotlin NativeBridge.onThermalWarning 对齐） */
enum class ThermalSeverity : int {
    Info = 0,          // 接近阈值，仅提醒
    ReduceScale = 1,   // 建议降低渲染分辨率
    Aggressive = 2,    // 强制激进降载（降帧 + 降分辨率）
};

using ThermalCallback = std::function<void(float predictedTempC, int horizonSec, ThermalSeverity severity)>;

/** 温度快照（线程安全读取，供启动器 UI 状态面板展示） */
struct ThermalSnapshot {
    bool running = false;      // 监控线程是否在运行
    float currentC = 0.0f;     // 最近一次采样的最高 CPU 温度（°C）
    float predictedC = 0.0f;   // 最近一次外推预测温度（°C）
    int severity = 0;          // 最近一次预警严重度（0/1/2）
    int samples = 0;           // 历史样本数
};

/** 启动监控线程。重复调用会先停掉旧线程。 */
bool startThermalMonitor(float thresholdC, float horizonSec, ThermalCallback cb);

/** 停止监控线程（幂等） */
void stopThermalMonitor();

/** 读取温度快照（无副作用，可在任意线程调用） */
ThermalSnapshot getThermalSnapshot();

} // namespace lmc
