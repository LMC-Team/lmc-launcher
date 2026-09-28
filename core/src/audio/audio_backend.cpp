#include "audio_backend.h"
#include "common/log.h"
#include "core_runtime.h"

#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <cstdlib>
#include <cstdio>

namespace lmc {

bool probeAudioCapabilities(bool* mmapAvailable, int* sampleRate, int* burstFrames) {
    *mmapAvailable = false;
    *sampleRate = 48000;
    *burstFrames = 0;

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE); // MMAP 前提
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);

    AAudioStream* stream = nullptr;
    const aaudio_result_t rc = AAudioStreamBuilder_openStream(builder, &stream);
    if (rc == AAUDIO_OK && stream) {
        // 关键检查：是否真的走了 MMAP 通道（内核快速路径）。
        // AAudioStream_isMMapUsed 属半公开 API（AAudioTesting.h，NDK 头文件不含），
        // 从 libaaudio.so 动态解析；符号缺失视为不支持 MMAP。
        using IsMMapUsed_t = bool (*)(const AAudioStream*);
        auto isMMapUsed = reinterpret_cast<IsMMapUsed_t>(
            dlsym(RTLD_DEFAULT, "AAudioStream_isMMapUsed"));
        *mmapAvailable = isMMapUsed != nullptr && isMMapUsed(stream);
        *sampleRate = AAudioStream_getSampleRate(stream);
        *burstFrames = AAudioStream_getFramesPerBurst(stream);
        AAudioStream_close(stream);
    } else {
        LOGW("AAudio 探测流打开失败: %d", rc);
    }
    AAudioStreamBuilder_delete(builder);
    return true;
}

bool initAudioBackend() {
    const CoreRuntime& rt = runtime();

    // alsoft.conf 目录
    const std::string dir = rt.filesDir + "/openalsoft";
    mkdir(dir.c_str(), 0755);
    const std::string confPath = dir + "/alsoft.conf";

    FILE* f = fopen(confPath.c_str(), "w");
    if (!f) {
        LOGE("无法写入 OpenAL-Soft 配置: %s", confPath.c_str());
        return false;
    }
    fprintf(f,
            "# LMC Launcher 自动生成（openal-soft 1.24.x）\n"
            "[general]\n"
            "drivers = aaudio      # 使用 AAudio 原生后端（1.23+ 内置）\n"
            "resampler = spline    # 低 CPU 占用的重采样档位\n"
            "stereo-mode = speakers\n"
            "[aaudio]\n"
            "mmap = %s             # MMAP 低延迟通道（探测结果）\n"
            "buffer-size = %d      # 以 burst 为单位对齐，避免周期漂移\n",
            rt.aaudioMmapAvailable ? "true" : "false",
            rt.aaudioBurstFrames > 0 ? rt.aaudioBurstFrames : 256);
    fclose(f);

    setenv("ALSOFT_CONF", confPath.c_str(), 1);
    LOGI("OpenAL-Soft 配置: %s (MMAP=%s)", confPath.c_str(),
         rt.aaudioMmapAvailable ? "是" : "否");
    return true;
}

} // namespace lmc
