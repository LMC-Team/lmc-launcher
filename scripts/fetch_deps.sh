#!/usr/bin/env bash
# =============================================================================
# 依赖获取脚本：把所有第三方运行时资产下载到 runtime-assets/
#
# 资产清单（版本与来源）：
#   OpenJDK 21 aarch64 JRE  : PojavLauncherTeam/android-openjdk-build-multiarch
#                             https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases
#                             （Termux 补丁版 OpenJDK 21，已适配 Android bionic）
#   Mesa 24.x 源码          : https://gitlab.freedesktop.org/mesa/mesa/-/archive/mesa-24.2.8/mesa-24.2.8.tar.gz
#   gl4es 源码              : https://github.com/PojavLauncherTeam/gl4es（含 Android 适配补丁）
#   OpenAL-Soft 1.24.1      : https://github.com/kcat/openal-soft/archive/refs/tags/1.24.1.tar.gz
#   mimalloc 2.1.7          : https://github.com/microsoft/mimalloc（CMake FetchContent 自动拉取）
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$ROOT_DIR/runtime-assets"
mkdir -p "$ASSETS_DIR"
cd "$ASSETS_DIR"

echo "==> [1/4] 下载 OpenJDK 21 JRE（aarch64, Termux 补丁版）"
# Termux 官方源 openjdk-21（bionic 原生编译，可 dlopen；21.0.12 真机实测可用）
# 解包与部署由 scripts/deploy_runtime_test.sh extract 完成
# 注意：该构建的 ZGC 初始化依赖的 get_mempolicy 被 Android 应用 seccomp 拒绝，
#       默认 GC 使用 ParallelGC；NUMA 补丁构建见 build_openjdk.sh 方案 B
if [ ! -f openjdk-21_21.0.12_aarch64.deb ]; then
    curl -fL --retry 10 -C - \
      "https://packages.termux.dev/apt/termux-main/pool/main/o/openjdk-21/openjdk-21_21.0.12_aarch64.deb" \
      -o openjdk-21_21.0.12_aarch64.deb
fi
echo "    deb 就绪: $(du -h openjdk-21_21.0.12_aarch64.deb | cut -f1)"

echo "==> [2/4] 下载 Mesa 源码（Zink + Turnip）"
if [ ! -d mesa-src ]; then
    curl -fL "https://gitlab.freedesktop.org/mesa/mesa/-/archive/mesa-24.2.8/mesa-24.2.8.tar.gz" -o mesa.tar.gz
    mkdir mesa-src && tar xzf mesa.tar.gz -C mesa-src --strip-components=1
    rm mesa.tar.gz
fi

echo "==> [3/4] 下载 gl4es 源码（Pojav 适配版）"
if [ ! -d gl4es-src ]; then
    curl -fL "https://github.com/PojavLauncherTeam/gl4es/archive/refs/heads/master.tar.gz" -o gl4es.tar.gz
    mkdir gl4es-src && tar xzf gl4es.tar.gz -C gl4es-src --strip-components=1
    rm gl4es.tar.gz
fi

echo "==> [4/4] 下载 OpenAL-Soft 源码"
if [ ! -d openal-src ]; then
    curl -fL "https://github.com/kcat/openal-soft/archive/refs/tags/1.24.1.tar.gz" -o openal.tar.gz
    mkdir openal-src && tar xzf openal.tar.gz -C openal-src --strip-components=1
    rm openal.tar.gz
fi

echo "全部依赖就绪：$ASSETS_DIR"
echo "下一步：依次运行 build_openjdk.sh(已完成下载) / build_mesa.sh / build_gl4es.sh / build_openal.sh"
