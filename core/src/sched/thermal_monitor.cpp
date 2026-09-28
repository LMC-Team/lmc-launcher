#include "thermal_monitor.h"
#include "common/log.h"
#include "core_runtime.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <strings.h>
#include <dirent.h>
#include <string>
#include <thread>
#include <vector>

namespace lmc {

namespace {

std::atomic<bool> g_running{false};
std::thread g_thread;

// 收集所有 CPU 相关温区的当前温度（摄氏度）
// 匹配温区类型：骁龙 "cpu-0-0"/"cpu-1-1-0"，MTK "cpu"，通用 "CPU"
std::vector<float> readCpuTempsC() {
    std::vector<float> temps;
    DIR* dir = opendir("/sys/class/thermal");
    if (!dir) return temps;
    struct dirent* ent;
    while ((ent = readdir(dir)) != nullptr) {
        if (strncmp(ent->d_name, "thermal_zone", 12) != 0) continue;
        const std::string base = std::string("/sys/class/thermal/") + ent->d_name;

        // 类型过滤：只看 CPU 温区，忽略 battery/gpu/modem 等
        FILE* tf = fopen((base + "/type").c_str(), "re");
        if (!tf) continue;
        char type[64] = {0};
        const size_t n = fread(type, 1, sizeof(type) - 1, tf);
        fclose(tf);
        type[n > 0 ? n : 0] = '\0';
        if (strncasecmp(type, "cpu", 3) != 0) continue;

        FILE* vf = fopen((base + "/temp").c_str(), "re");
        if (!vf) continue;
        int milliC = 0;
        if (fscanf(vf, "%d", &milliC) == 1 && milliC > -40000 && milliC < 150000) {
            temps.push_back(milliC / 1000.0f);
        }
        fclose(vf);
    }
    closedir(dir);
    return temps;
}

float maxOf(const std::vector<float>& v) {
    return v.empty() ? 0.0f : *std::max_element(v.begin(), v.end());
}

void monitorLoop(float thresholdC, int horizonSec, ThermalCallback cb) {
    // 采样窗口：2s 间隔，保留 8 个样本（16 秒历史）做线性趋势
    constexpr int kIntervalMs = 2000;
    constexpr int kWindowSize = 8;
    std::vector<float> history;
    history.reserve(kWindowSize);

    while (g_running.load(std::memory_order_relaxed)) {
        const float nowC = maxOf(readCpuTempsC());
        if (nowC > 0.0f) {
            history.push_back(nowC);
            if (history.size() > static_cast<size_t>(kWindowSize)) history.erase(history.begin());

            if (history.size() >= 3) {
                // 最小二乘拟合斜率（°C/秒）
                const float n = static_cast<float>(history.size());
                const float step = kIntervalMs / 1000.0f;
                float sumT = 0, sumY = 0, sumTY = 0, sumT2 = 0;
                for (size_t i = 0; i < history.size(); ++i) {
                    const float t = static_cast<float>(i) * step;
                    sumT += t; sumY += history[i];
                    sumTY += t * history[i];
                    sumT2 += t * t;
                }
                const float denom = n * sumT2 - sumT * sumT;
                float slope = denom > 1e-6f ? (n * sumTY - sumT * sumY) / denom : 0.0f;
                // 限幅：真机实测样本少时会拟合出极端斜率（预测 134°C 的误报），
                // 物理上 CPU 升温不超过 ~0.3°C/s（18°C/分钟）
                slope = std::min(0.3f, std::max(-0.3f, slope));

                // 外推 horizonSec 后温度；仅上坡（slope > 0.02 °C/s）时预警
                const float predicted = history.back() + slope * horizonSec;
                if (predicted >= thresholdC && slope > 0.02f) {
                    // 严重度：离阈值越近越激进
                    ThermalSeverity sev = ThermalSeverity::Info;
                    const float overRatio = (predicted - thresholdC) / std::max(thresholdC, 1.0f);
                    if (overRatio > 0.05f) sev = ThermalSeverity::Aggressive;
                    else if (overRatio > 0.0f) sev = ThermalSeverity::ReduceScale;
                    LOGW("温度预警: 当前 %.1f°C, %ds 后预测 %.1f°C (阈值 %.1f)",
                         history.back(), horizonSec, predicted, thresholdC);
                    if (cb) cb(predicted, horizonSec, sev);
                }
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kIntervalMs));
    }
}

} // namespace

bool startThermalMonitor(float thresholdC, float horizonSec, ThermalCallback cb) {
    stopThermalMonitor();
    g_running.store(true);
    g_thread = std::thread(monitorLoop, thresholdC, static_cast<int>(horizonSec), std::move(cb));
    LOGI("温度监控启动: 阈值 %.1f°C, 预测窗 %.0fs", thresholdC, horizonSec);
    return true;
}

void stopThermalMonitor() {
    if (!g_running.exchange(false)) return;
    if (g_thread.joinable()) g_thread.join();
}

} // namespace lmc
