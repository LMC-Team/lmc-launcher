#!/usr/bin/env bash
# =============================================================================
# Zalith 2 同源组件栈部署：natives 全套 + LWJGL 配套 jar → 设备
#
# 背景（2026-10-01 路线收敛）：
#   旧混合生态（FCL pojavexec + Zalith glfw-classes + FCL JRE 混装）存在
#   跨 APK 配对问题，37 个断点大多源于此。现全部组件收敛为 Zalith 2 同源：
#     - libpojavexec(_awt)/exithook/driver_helper/bytehook/linkerhook
#       → 打进 APK jniLibs（app/src/main/jniLibs/arm64-v8a，已就位）
#     - 渲染栈/LWJGL natives（gl4es/Mesa/zink/OSMesa/lwjgl*/openal/freetype/
#       jnidispatch/awt_headless...）→ 本脚本部署到设备 versions/<id>/natives/
#     - lwjgl-glfw-classes.jar（LWJGL 3.3.6-snapshot java，与 natives 同源）
#       → 部署到设备 versions/<id>/libs/pojav/
#
# 组件来源：ZalithLauncher 2 构建产物（E:/zl2，或 GitHub ZalithLauncher/ZalithLauncher）：
#   SRC=.../ZalithLauncher/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a
#
# 用法：
#   scripts/deploy_zalith2_stack.sh deploy 1.21.4   # 部署 natives+jar 到版本 1.21.4
#   scripts/deploy_zalith2_stack.sh list            # 列出将部署的组件
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.lemwoodmc.launcher"

# 组件源目录：优先 zl2 源码树构建产物，其次 runtime-assets 快照
if [ -d "/e/zl2/ZalithLauncher/build/intermediates/merged_native_libs" ]; then
    SO_SRC="/e/zl2/ZalithLauncher/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"
else
    SO_SRC="${SO_SRC:-$ROOT/runtime-assets/zalith2-stack/arm64-v8a}"
fi
JAR_SRC="${JAR_SRC:-$ROOT/app/libs/zalith2/lwjgl-glfw-classes.jar}"

ADB_CANDIDATES=(
    "${ADB:-}"
    "$ROOT/../android-sdk-home/platform-tools/adb.exe"
    "/e/android-sdk-home/platform-tools/adb.exe"
    "/run/media/lemwood/project/android-sdk-home/platform-tools/adb"
)
ADB=""
for c in "${ADB_CANDIDATES[@]}"; do
    if [ -n "$c" ] && command -v "$c" >/dev/null 2>&1; then ADB="$c"; break; fi
done
[ -n "$ADB" ] || { echo "找不到 adb，请 ADB=<路径> 环境变量指定"; exit 1; }

# natives 白名单（Zalith 2 merged_native_libs 全集；排除与 APK jniLibs 重复的桥件）
NATIVES=(
    liblwjgl.so liblwjgl_opengl.so liblwjgl_stb.so liblwjgl_tinyfd.so
    liblwjgl_nanovg.so liblwjgl_vma.so
    libgl4es_114.so libng_gl4es.so
    libEGL_mesa.so libglapi.so libzink_dri.so libvulkan_freedreno.so
    libEGL_angle.so libGLESv2_angle.so
    libOSMesa_8.so libOSMesa_2121.so libOSMesa_2300d.so
    libopenal.so libfreetype.so libjnidispatch.so
    libawt_headless.so libawt_xawt.so
    libshaderc.so libshaderconv.so libspirv-cross-c-shared.so
    libVkLayer_khronos_timeline_semaphore.so
    libSDL3.so libglxshim.so libvirgl_test_server.so
)

list() {
    echo "组件源: $SO_SRC"
    echo "LWJGL jar: $JAR_SRC"
    echo "---- natives（将部署到 versions/<id>/natives/）----"
    for f in "${NATIVES[@]}"; do
        if [ -f "$SO_SRC/$f" ]; then echo "  [✓] $f"; else echo "  [缺失] $f"; fi
    done
}

deploy() {
    local version="${1:?用法: $0 deploy <版本id，如 1.21.4>}"
    [ -d "$SO_SRC" ] || { echo "找不到组件源目录: $SO_SRC"; exit 1; }
    [ -f "$JAR_SRC" ] || { echo "找不到 LWJGL jar: $JAR_SRC"; exit 1; }

    echo "==> 打包 Zalith 2 同源组件栈（natives ${#NATIVES[@]} 项 + lwjgl-glfw-classes.jar）"
    STAGE_DIR="/tmp/lmc_zalith2_stack"
    STAGE_TAR="/tmp/lmc_zalith2_stack.tar"
    rm -rf "$STAGE_DIR" "$STAGE_TAR"
    mkdir -p "$STAGE_DIR/natives" "$STAGE_DIR/libs/pojav"
    local missing=0
    for f in "${NATIVES[@]}"; do
        if [ -f "$SO_SRC/$f" ]; then
            cp "$SO_SRC/$f" "$STAGE_DIR/natives/"
        else
            echo "  [缺失] $f"; missing=$((missing+1))
        fi
    done
    cp "$JAR_SRC" "$STAGE_DIR/libs/pojav/lwjgl-glfw-classes.jar"
    tar -C "$STAGE_DIR" -cf "$STAGE_TAR" natives libs
    echo "   打包完成 $(du -h "$STAGE_TAR" | cut -f1)，缺失 $missing 项"

    echo "==> push 到设备并解包到 files/versions/$version/"
    "$ADB" push "$STAGE_TAR" /data/local/tmp/lmc_zalith2_stack.tar
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/versions/$version && cd files/versions/$version && rm -rf natives libs/pojav && tar xf /data/local/tmp/lmc_zalith2_stack.tar'"
    "$ADB" shell rm -f /data/local/tmp/lmc_zalith2_stack.tar

    echo "==> 校验"
    "$ADB" shell "run-as $PKG sh -c 'ls files/versions/$version/natives | wc -l; ls -l files/versions/$version/libs/pojav/'"
    echo "部署完成。注意：旧 FCL 混装 so（libfcl/libbytehook 旧版/ mio wrapper jar 等）"
    echo "如仍在 libs/ 混存，建议清理：libs/ 仅保留 pojav(lwjgl jar)/cacio/mio 三目录。"
}

case "${1:-list}" in
    list) list ;;
    deploy) deploy "${2:-1.21.4}" ;;
    *) echo "用法: $0 {list|deploy <版本id>}"; exit 1 ;;
esac
