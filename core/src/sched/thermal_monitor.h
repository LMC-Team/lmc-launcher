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

/** 启动监控线程。重复调用会先停掉旧线程。 */
bool startThermalMonitor(float thresholdC, float horizonSec, ThermalCallback cb);

/** 停止监控线程（幂等） */
void stopThermalMonitor();

} // namespace lmc
