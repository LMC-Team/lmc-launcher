// JVM 参数生成：性能调优默认值 + AppCDS + 运行时环境布置
#pragma once

#include <jni.h>
#include <string>
#include <vector>

namespace lmc {

/** 保证 JavaVMOption 内字符串生命周期的存储区 */
struct JvmOptionsStore {
    std::vector<std::string> strings;
};

/**
 * 生成最终 JVM 参数。
 *
 * 策略：用户参数（Kotlin 设置页拼装）优先；本函数只补充 native 侧才能确定的调优项，
 * 且逐项检查避免重复：
 *  - -XX:CICompilerCount=<大核数>       JIT 编译线程匹配 big.LITTLE 拓扑
 *  - -XX:CICompilerCountPerCPU=false
 *  - AppCDS: -XX:SharedArchiveFile=<cache>/app.jsa + -XX:+AutoCreateSharedArchive
 *            （JDK 19+ 特性：首次运行自动生成类存档，之后启动直接 mmap，省去类解析）
 *  - -XX:+UnlockExperimentalVMOptions（置于非 product 开关前）
 *  - 属性：java.home / java.io.tmpdir / user.home / java.library.path / os.name 等
 */
bool buildJvmOptions(const std::string& javaHome,
                     const std::vector<std::string>& userArgs,
                     std::vector<JavaVMOption>& out,
                     JvmOptionsStore& store);

/**
 * 布置 JVM 进程环境变量（必须在 CreateJavaVM 之前）：
 *   JAVA_HOME / HOME / TMPDIR / PATH / LD_LIBRARY_PATH（追加 JDK 自身库目录）
 */
void setupJvmProcessEnvironment(const std::string& javaHome);

/** 用户参数中是否已存在以 prefix 开头的选项 */
bool hasArgWithPrefix(const std::vector<std::string>& args, const char* prefix);

} // namespace lmc
