#!/usr/bin/env bash
# =============================================================================
# JRE 21 + hello 测试版本：真机端到端部署与验证
#
# 用法：
#   scripts/deploy_runtime_test.sh extract  # 从 Termux deb 解出 JRE → runtime-assets/jre21
#   scripts/deploy_runtime_test.sh deploy   # 打包 JRE+hello 并流式部署到设备 files/
#   scripts/deploy_runtime_test.sh read     # 回读设备上的 JVM 测试结果
#
# 前置：设备已连接 adb（debug 构建可用 run-as）；Termux deb 已由 fetch_deps.sh 下载。
# 来源：Termux 官方源 openjdk-21_21.0.12_aarch64.deb（bionic 原生编译，可 dlopen）。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/runtime-assets"
PKG="com.lemwoodmc.launcher"
ADB="${ADB:-/run/media/lemwood/project/android-sdk-home/platform-tools/adb}"
DEB="$ASSETS/openjdk-21_21.0.12_aarch64.deb"
JRE_STAGE="$ASSETS/jre21"
HELLO_JAR="/tmp/hello/hello.jar"

extract() {
    [ -f "$DEB" ] || { echo "缺少 $DEB，请先运行 fetch_deps.sh 下载"; exit 1; }
    [ -f "$HELLO_JAR" ] || { echo "缺少 $HELLO_JAR（由 scripts 构建 hello 测试 jar 生成）"; exit 1; }

    echo "==> 用 python 解包 deb（ar → data.tar.xz → tar）"
    python3 - "$DEB" "$ASSETS" <<'PYEOF'
import sys, os, io, tarfile, shutil, lzma

deb_path, out_dir = sys.argv[1], sys.argv[2]

# ---- 最小 ar 归档解析，取出 data.tar.xz ----
# 注意：60 字节头里已包含 2 字节结束符（` + \n），头后紧跟 payload，不要再多读
with open(deb_path, 'rb') as f:
    assert f.read(8) == b'!<arch>\n', "不是合法的 deb"
    data = None
    while True:
        hdr = f.read(60)
        if len(hdr) < 60:
            break
        name = hdr[0:16].decode().strip().rstrip('/')
        size = int(hdr[48:58].decode().strip())
        payload = f.read(size)
        if size % 2:
            f.read(1)  # ar 成员按 2 字节对齐
        if name == 'data.tar.xz':
            data = payload
            break
assert data, "deb 中未找到 data.tar.xz"

# ---- 解压 data.tar.xz ----
tmp = os.path.join(out_dir, ".deb-unpack")
shutil.rmtree(tmp, ignore_errors=True)
os.makedirs(tmp, exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(lzma.decompress(data))) as t:
    t.extractall(tmp)

# ---- 定位含 lib/server/libjvm.so 的 JDK 根目录 ----
jdk_root = None
for root, dirs, files in os.walk(tmp):
    if 'libjvm.so' in files and os.path.basename(root) == 'server':
        jdk_root = os.path.dirname(os.path.dirname(root))  # .../lib/server → .../lib → JDK 根
        break
assert jdk_root, "未在 deb 中找到 lib/server/libjvm.so"
print("JDK 根目录:", jdk_root)

# ---- 组装标准布局 runtime-assets/jre21/ ----
stage = os.path.join(out_dir, "jre21")
shutil.rmtree(stage, ignore_errors=True)
shutil.move(jdk_root, stage)
shutil.rmtree(tmp, ignore_errors=True)
print("已就绪:", stage)
PYEOF
}

deploy() {
    [ -f "$JRE_STAGE/lib/server/libjvm.so" ] || { echo "jre21 未解包，先执行 $0 extract"; exit 1; }
    command -v "$ADB" >/dev/null || ADB=adb

    echo "==> 打包 jre21 + hello 版本为 tar（条目含 runtime/ 与 versions/ 前缀）"
    STAGE_TAR="/tmp/lmc_bundle.tar"
    STAGE_DIR="/tmp/lmc_bundle"
    rm -rf "$STAGE_DIR" "$STAGE_TAR"
    mkdir -p "$STAGE_DIR/runtime" "$STAGE_DIR/versions/hello"
    cp -a "$JRE_STAGE" "$STAGE_DIR/runtime/jre21"
    cp "$HELLO_JAR" "$STAGE_DIR/versions/hello/client.jar"
    tar -C "$STAGE_DIR" -cf "$STAGE_TAR" runtime versions
    echo "   $(du -h "$STAGE_TAR" | cut -f1)"

    echo "==> adb push 到 /data/local/tmp（协议可靠传输）"
    "$ADB" push "$STAGE_TAR" /data/local/tmp/lmc_bundle.tar

    echo "==> 设备端解包到应用私有目录（/data/local/tmp 文件对 app 可读）"
    "$ADB" shell "run-as $PKG sh -c 'rm -rf files/runtime/jre21 files/versions/hello; mkdir -p files/runtime files/versions && cd files && tar xf /data/local/tmp/lmc_bundle.tar'"
    "$ADB" shell rm -f /data/local/tmp/lmc_bundle.tar
    echo "==> 校验 libjvm.so"
    "$ADB" shell "run-as $PKG ls -l files/runtime/jre21/lib/server/libjvm.so"
    "$ADB" shell "run-as $PKG ls -l files/versions/hello/client.jar"
    echo "部署完成：打开应用 → 版本页应出现 hello（已安装）→ 选择后开始游戏"
}

read_result() {
    echo "==> 设备上的 JVM 测试结果"
    "$ADB" shell "run-as $PKG sh -c 'cat files/minecraft/jvm_test_ok.txt 2>/dev/null || echo 尚未生成'" 2>/dev/null
}

case "${1:-deploy}" in
    extract) extract ;;
    deploy)  deploy ;;
    read)    read_result ;;
    *) echo "用法: $0 {extract|deploy|read}"; exit 1 ;;
esac
