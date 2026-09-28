// LMC 核心后端公共日志宏（__android_log_print，运行于 logd）
#pragma once

#include <android/log.h>

#define LMC_LOG_TAG "LMC-Core"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LMC_LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LMC_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LMC_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LMC_LOG_TAG, __VA_ARGS__)
