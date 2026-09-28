#!/usr/bin/env bash
# =============================================================================
# gl4es（保底渲染路径）交叉编译
# 来源: https://github.com/PojavLauncherTeam/gl4es（Pojav 适配版）
# 产物: libgl4es_114.so + 符号链接 libGL.so
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT_DIR/runtime-assets/gl4es-src"
OUT="$ROOT_DIR/runtime-assets/gl4es"
NDK="${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME}"
API=29

mkdir -p "$OUT"
cd "$SRC"

# gl4es 自带 Android 构建脚本（内部调用 ndk-build）
export NDK_PROJECT_PATH="$SRC"
export APP_ABI=arm64-v8a
export APP_PLATFORM="android-$API"
export NDK_LIBS_OUT="$SRC/libs"
bash "$SRC/project/jni/build.sh" || {
    # 备选：直接用 cmake 构建（上游同样支持）
    cmake -B build-cmake -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
          -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTS=OFF
    cmake --build build-cmake -j"$(nproc)"
    find build-cmake -name "libgl4es*" -exec cp {} "$OUT/" \;
}

find "$SRC" -name "libgl4es_114.so" -exec cp {} "$OUT/" \;
ln -sf libgl4es_114.so "$OUT/libGL.so" 2>/dev/null || true

echo "gl4es 构建完成: $OUT"
