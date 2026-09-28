// jni_globals.h 的实现：全局 JavaVM 指针存储
#include "jni_globals.h"

namespace lmc {

namespace {
JavaVM* g_globalJvm = nullptr; // JNI_OnLoad 缓存，进程级单例
} // namespace

JavaVM* globalJvm() { return g_globalJvm; }

void setGlobalJvm(JavaVM* vm) { g_globalJvm = vm; }

} // namespace lmc
