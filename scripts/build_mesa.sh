#!/usr/bin/env bash
# =============================================================================
# Mesa 交叉编译：Zink（GL on Vulkan）+ Turnip（Adreno 开源 Vulkan 驱动）
#
# 产物布局（部署到设备 <files>/runtime/mesa/）：
#   libzink.so? no ——
#   libgallium_drv_video.so / libGL.so.1     (GL 前端 + Zink gallium 驱动)
#   libvulkan_turnip.so                       (freedreno turnip Vulkan 驱动)
#   turnip_icd.aarch64.json                   (Vulkan ICD 描述文件)
#
# 依赖：NDK r27（aarch64-linux-android29-clang 在 PATH）、meson>=1.3、ninja、
#       本机 pkg-config、python3-mako、flex/bison、zstd 开发头（可选）
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT_DIR/runtime-assets/mesa-src"
BUILD="$ROOT_DIR/runtime-assets/mesa-build"
OUT="$ROOT_DIR/runtime-assets/mesa"
NDK="${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME 指向 NDK r27 根目录}"
PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

# 构建机若无 expat/zlib 依赖头，用 -Dglx=disabled -Dplatforms=[] 的纯 EGL-GLES 配置
meson setup "$BUILD" "$SRC" \
    --cross-file "$ROOT_DIR/scripts/mesa-cross-aarch64.txt" \
    --buildtype=release \
    -Dplatforms=[] \
    -Dglx=disabled \
    -Dgbm=disabled \
    -Degl=disabled \
    -Dllvm=disabled \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers=[],freedreno \
    -Dfreedreno-kmds=msm \
    -Dvulkan-layers=[] \
    -Dtools=[] \
    -Dzstd=disabled \
    -Dexpat=disabled \
    -Dshader-cache=enabled \
    -Dmesa-clc=disabled \
    -Dshared-glapi=enabled \
    -Dopengl=true \
    -Dgles1=disabled \
    -Dgles2=enabled

ninja -C "$BUILD" -j"$(nproc)"

mkdir -p "$OUT"
# 安装并整理命名：LWJGL 期望 libGL.so.1；ICD json 指向 libvulkan_turnip.so
ninja -C "$BUILD" install >/dev/null
cp -a "$BUILD/src/gallium/drivers/zink"/*.so* "$OUT/" 2>/dev/null || true
find "$BUILD/src" -name "libGL.so*"   -exec cp -a {} "$OUT/" \;
find "$BUILD/src" -name "libGLESv2.so*" -exec cp -a {} "$OUT/" \;
find "$BUILD/src" -name "libgallium*"   -exec cp -a {} "$OUT/" \;
find "$BUILD/src/freedreno/vulkan" -name "libvulkan_freedreno.so" \
    -exec cp -a {} "$OUT/libvulkan_turnip.so" \;

# Turnip ICD 描述文件（render_bridge 通过 VK_ICD_FILENAMES 指向它）
cat > "$OUT/turnip_icd.aarch64.json" <<EOF
{
  "ICD": {
    "library_path": "libvulkan_turnip.so",
    "api_version": "1.3.256"
  }
}
EOF

# Mesa shader cache 目录约定（pipeline/shader cache 持久化）
mkdir -p "$OUT/cache"

echo "Mesa 构建完成: $OUT"
echo "部署: adb push $OUT/* /data/data/com.lemwoodmc.launcher/files/runtime/mesa/"
