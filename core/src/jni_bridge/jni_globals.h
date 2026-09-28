// JNI 全局量：native 模块与 JVM 之间的共享锚点
#pragma once

#include <jni.h>

namespace lmc {

/** JNI_OnLoad 时缓存的 JavaVM 指针（供非 JNI 线程 Attach 用） */
JavaVM* globalJvm();
void setGlobalJvm(JavaVM* vm);

} // namespace lmc
