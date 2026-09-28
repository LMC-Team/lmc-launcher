#!/usr/bin/env bash
# =============================================================================
# OpenAL-Soft 1.24.1 交叉编译（AAudio MMAP 后端）
# 来源: https://github.com/kcat/openal-soft
# 产物: libopenal.so（含 aaudio 后端；openal-soft ≥1.23 原生支持 AAudio）
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT_DIR/runtime-assets/openal-src"
OUT="$ROOT_DIR/runtime-assets/openal"
NDK="${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME}"

cmake -S "$SRC" -B "$SRC/build-android" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DALSOFT_REQUIRE_AAUDIO=ON \
    -DALSOFT_REQUIRE_OPENSL=ON \
    -DALSOFT_EXAMPLES=OFF \
    -DALSOFT_TESTS=OFF \
    -DALSOFT_UTILS=OFF \
    -DALSOFT_AMBDEC_PRESETS=OFF
cmake --build "$SRC/build-android" -j"$(nproc)"

mkdir -p "$OUT"
cp "$SRC/build-android/libopenal.so" "$OUT/"
echo "OpenAL-Soft 构建完成: $OUT"
echo "设备侧配置文件由 lmc_core 音频模块自动生成（AAudio MMAP 探测结果）"
