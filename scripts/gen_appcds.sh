#!/usr/bin/env bash
# =============================================================================
# AppCDS 类存档（classes.jsa）生成说明
#
# lmc_core 的 JVM 参数默认带 -XX:+AutoCreateSharedArchive（JDK 19+ 特性），
# 因此「无需手工生成」：首次启动游戏时 JVM 会自动把已加载类写入
#   <cacheDir>/app.jsa
# 之后每次启动直接 mmap 该存档，类加载提速 30%~40%。
#
# 如需离线预生成（把耗时从首次启动挪到部署阶段），按下面步骤：
# =============================================================================
set -euo pipefail

PKG="com.lemwoodmc.launcher"

echo "== 方式一：自动生成（默认） =="
echo "  首次游戏启动时 JVM 自动完成，零操作。"
echo ""
echo "== 方式二：adb 手动预生成（离线部署） =="
echo "  1) 构建一个包含 Minecraft 依赖 jar 的 classlist："
echo "     adb shell run-as $PKG sh -c '"
echo "        export JAVA_HOME=/data/data/$PKG/files/runtime/jre21"
echo "        export CLASSPATH=/data/data/$PKG/files/versions/1.21.4/client.jar"
echo "        \$JAVA_HOME/bin/java -XX:DumpLoadedClassList=\$HOME/app.classlist \\"
echo "            -cp \$CLASSPATH net.minecraft.client.main.Main --version 1.21.4 --exitOnCrash"
echo "     '"
echo ""
echo "  2) 用 classlist 生成归档："
echo "     adb shell run-as $PKG sh -c '"
echo "        \$JAVA_HOME/bin/java -Xshare:dump \\"
echo "            -XX:SharedClassListFile=\$HOME/app.classlist \\"
echo "            -XX:SharedArchiveFile=\$HOME/cache/app.jsa \\"
echo "            -cp /data/data/$PKG/files/versions/1.21.4/client.jar"
echo "     '"
echo ""
echo "  3) 校验：启动日志应出现 'sharing' / 'opened archive app.jsa'"
echo ""
echo "== 校验 AppCDS 生效 =="
echo "  adb logcat -s LMC-Core | grep -i 'share'"
echo "  Java 侧验证: System.out.println(SharedClassRuntime 走 -Xlog:cds)"
