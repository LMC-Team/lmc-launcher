// gl4es 保底加载器：
// 仅验证裁剪版 gl4es 动态库可加载。真正的 GL 拦截由 LWJGL 按
// java.library.path / org.lwjgl.opengl.libname 解析完成。
#pragma once

#include <string>

namespace lmc {

/** dlopen 探测 libgl4es；成功返回 true。重复调用无副作用。 */
bool ensureGl4esLoaded(const std::string& gl4esDir);

} // namespace lmc
