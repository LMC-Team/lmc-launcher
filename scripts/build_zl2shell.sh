#!/bin/sh
# zl2-shell 分支构建入口
#
# 关键:ndk-build 的内部脚本(ldflags_to_sanitizers.py)不支持含空格的项目路径,
# 项目位于 "E:\lmc launcher" 时 ndkBuild 必失败。解决:经 subst 盘映射构建。
#
# 首次使用(Windows 命令行/资源管理器,一次即可,重启后需重做):
#   subst L: "E:\lmc launcher"
# 之后本脚本自动检查 L: 并在其中执行 gradle。
#
# 用法:sh scripts/build_zl2shell.sh :ZalithLauncher:assembleDebug
export MSYS_NO_PATHCONV=1
if [ ! -d "/l/ZalithLauncher" ]; then
  echo "L: 未映射。请先执行: subst L: \"E:\\lmc launcher\""
  exit 1
fi
cd /l && JAVA_HOME=E:/jdk21 sh gradlew "$@"
