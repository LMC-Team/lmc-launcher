#!/usr/bin/env bash
# =============================================================================
# OpenJDK 21 (aarch64) 获取 / 编译
#
# 方案 A（默认，实测可用）：Termux 官方源 openjdk-21 deb，bionic 原生编译，
#   dlopen 直接加载，无 proot。解包由 scripts/deploy_runtime_test.sh extract 完成
#   （纯 python 解析 ar → data.tar.xz → 定位 libjvm.so）。
#   注意：该构建的 ZGC 初始化（NUMA 探测 get_mempolicy）被 Android 应用 seccomp
#   拒绝，须使用 ParallelGC；shim 库 libandroid-shmem/libandroid-spawn 由
#   deploy 脚本自动补入，且这些库需用 patchelf 补 DT_SONAME（脚本已处理）。
#
# 方案 B（自编译，解锁 ZGC）：基于 termux-packages 的 openjdk 源码，构建时
#   打 NUMA 探测禁用补丁（os_linux.cpp 的 numa_init 包一层 #ifdef __ANDROID__），
#   需 2-4 小时编译。源码：https://github.com/termux/termux-packages
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT_DIR/runtime-assets"
OUT_DIR="$ASSETS/jre21"

if [ -f "$OUT_DIR/lib/server/libjvm.so" ]; then
    echo "OpenJDK 21 已就绪: $OUT_DIR"
    exit 0
fi

echo "==> 下载 Termux openjdk-21 deb"
"$ROOT_DIR/scripts/fetch_deps.sh" | head -0 || true
[ -f "$ASSETS/openjdk-21_21.0.12_aarch64.deb" ] || {
    mkdir -p "$ASSETS"
    curl -fL --retry 10 -C - \
      "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-21/openjdk-21_21.0.12_aarch64.deb" \
      -o "$ASSETS/openjdk-21_21.0.12_aarch64.deb"
}
echo "==> 解包（deploy_runtime_test.sh extract）"
"$ROOT_DIR/scripts/deploy_runtime_test.sh" extract
echo "OpenJDK 21 就绪 ✓ ($OUT_DIR)"
