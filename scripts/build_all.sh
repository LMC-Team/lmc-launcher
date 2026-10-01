#!/usr/bin/env bash
# =============================================================================
# 一键构建：native 资产（可选） + APK
#
# 用法:
#   ./scripts/build_all.sh            # 仅构建 APK（要求已部署 runtime-assets）
#   ./scripts/build_all.sh --with-runtime   # 连同 Mesa/gl4es/OpenAL 一起构建
#
# 环境要求:
#   - JDK 17+
#   - Android SDK（ANDROID_HOME）
#   - NDK r27（ANDROID_NDK_HOME）
#   - APK 内的 liblmc_core.so 由 Gradle externalNativeBuild 调 CMake 编译
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

: "${ANDROID_HOME:?请设置 ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME}"

if [ "${1:-}" = "--with-runtime" ]; then
    "$(dirname "$0")/fetch_deps.sh"
    "$(dirname "$0")/build_openjdk.sh"
    "$(dirname "$0")/build_mesa.sh"
    "$(dirname "$0")/build_gl4es.sh"
    "$(dirname "$0")/build_openal.sh"
fi

echo "==> Gradle 构建 APK（含 liblmc_core.so）"
# 优先用 wrapper；仓库尚未提交 gradle wrapper 时回退到系统 gradle
# （Android Studio 打开本项目会自动生成 wrapper，或手动执行: gradle wrapper --gradle-version 8.9）
if [ -x ./gradlew ]; then
    ./gradlew assembleDebug
elif command -v gradle >/dev/null 2>&1; then
    gradle assembleDebug
else
    echo "错误：未找到 ./gradlew 也不存在系统 gradle。请安装 Gradle 8.9+ 或用 Android Studio 打开项目。" >&2
    exit 1
fi

echo "==> 构建完成: ${APK:-app/build/outputs/apk/debug/app-debug.apk}"

cat <<'EOF'

== 下一步（部署运行时资产到设备） ==
  运行时资产位于 runtime-assets/，首次启动前推送到应用私有目录：

  adb install app/build/outputs/apk/debug/app-debug.apk
  adb shell run-as com.lemwoodmc.launcher mkdir -p files/runtime
  adb push runtime-assets/jre21     /data/local/tmp/jre21
  adb push runtime-assets/mesa      /data/local/tmp/mesa
  adb push runtime-assets/gl4es     /data/local/tmp/gl4es
  adb push runtime-assets/openal    /data/local/tmp/openal
  adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/jre21  files/runtime/
  adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/mesa   files/runtime/
  adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/gl4es  files/runtime/
  adb shell run-as com.lemwoodmc.launcher cp -r /data/local/tmp/openal files/runtime/

  (release 构建无 run-as 权限，用应用内“导入运行时”入口或首次启动下载)
EOF
