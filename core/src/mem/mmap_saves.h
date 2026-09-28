// 世界存档 mmap 读取：region 文件（.mca）直接映射进地址空间，
// JVM 侧通过 DirectByteBuffer 零拷贝访问，避免 read() 拷贝与堆压力。
#pragma once

#include <cstddef>

namespace lmc {

struct WorldMmap {
    int fd = -1;
    void* addr = nullptr;
    size_t size = 0;
};

/** mmap 只读打开一个 region 文件；文件不存在或为空返回 false */
bool openWorldMmap(const char* path, WorldMmap* out);

/** 解除映射并关闭 */
void closeWorldMmap(WorldMmap* mmap);

} // namespace lmc
