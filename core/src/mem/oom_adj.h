// 进程保护：调整 oom_score_adj，降低被 lowmemorykiller 杀死的概率。
//
// 说明：普通应用进程由 SELinux 约束，能设置的最低值受 lmkd 策略限制；
// 本模块尽力写到目标值并报告实际结果（失败不视为致命，游戏仍可运行）。
#pragma once

namespace lmc {

/** 写 /proc/self/oom_score_adj。返回是否成功写入。 */
bool setOomScoreAdj(int adj);

/** 读取当前 oom_score_adj（诊断用） */
int getOomScoreAdj();

} // namespace lmc
