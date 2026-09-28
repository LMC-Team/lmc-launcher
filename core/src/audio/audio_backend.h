// 音频后端：AAudio MMAP 低延迟探测 + OpenAL-Soft 配置生成
//
// Minecraft 通过 LWJGL OpenAL 输出音频，LWJGL 的 OpenAL Soft 实现
// 支持原生 AAudio 后端（openal-soft 1.23+）。本模块：
//  1. 预探测设备 AAudio MMAP 能力（MMAP 路径延迟 ~10ms 级，普通路径 ~40ms 级）；
//  2. 生成 alsoft.conf 指定 aaudio 后端与低延迟参数，环境变量 ALSOFT_CONF 指向它。
#pragma once

namespace lmc {

/** 探测 AAudio MMAP 能力（nativeInitCore 时调用一次，结果缓存进 CoreRuntime） */
bool probeAudioCapabilities(bool* mmapAvailable, int* sampleRate, int* burstFrames);

/** 生成 OpenAL-Soft 配置并设置 ALSOFT_CONF 环境变量（创建 JVM 前调用） */
bool initAudioBackend();

} // namespace lmc
