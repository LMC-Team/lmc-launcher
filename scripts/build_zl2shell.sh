#!/bin/sh
# zl2-shell 分支构建入口
#
# 关键 1:ndk-build 的内部脚本(ldflags_to_sanitizers.py)不支持含空格的项目路径,
#         项目位于 "E:\lmc launcher" 时 ndkBuild 必失败。解决:经 subst 盘映射构建。
#         首次使用(Windows 命令行/资源管理器,一次即可,重启后需重做):
#             subst L: "E:\lmc launcher"
#
# 关键 2:默认只构建 arm64-v8a(-Darch=arm64),APK 由 404MB 降至 ~216MB,
#         安装临时空间需求大幅下降;需要全 ABI 时传 -Darch=all。
#
# 用法:
#   sh scripts/build_zl2shell.sh :ZalithLauncher:assembleDebug
#   sh scripts/build_zl2shell.sh -Darch=all :ZalithLauncher:assembleDebug
export MSYS_NO_PATHCONV=1
if [ ! -d "/l/ZalithLauncher" ]; then
  echo "L: 未映射。请先执行: subst L: \"E:\\lmc launcher\""
  exit 1
fi
case "$*" in
  *-Darch=*) ARCH_ARG="" ;;                 # 调用方已显式指定
  *)         ARCH_ARG="-Darch=arm64" ;;     # 默认 arm64(真机架构)
esac
cd /l && JAVA_HOME=E:/jdk21 sh gradlew $ARCH_ARG "$@"
