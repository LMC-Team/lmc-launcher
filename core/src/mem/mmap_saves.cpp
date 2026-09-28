#include "mmap_saves.h"
#include "common/log.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace lmc {

bool openWorldMmap(const char* path, WorldMmap* out) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGW("打开存档失败: %s", path);
        return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return false;
    }
    void* addr = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ,
                      MAP_PRIVATE, fd, 0);
    if (addr == MAP_FAILED) {
        close(fd);
        LOGW("mmap 失败: %s (%lld 字节)", path, static_cast<long long>(st.st_size));
        return false;
    }
    // 顺序读提示：region 解析基本是顺序扫描，可提升预读效率
    madvise(addr, static_cast<size_t>(st.st_size), MADV_SEQUENTIAL);

    out->fd = fd;
    out->addr = addr;
    out->size = static_cast<size_t>(st.st_size);
    LOGI("mmap 存档: %s (%lld 字节)", path, static_cast<long long>(st.st_size));
    return true;
}

void closeWorldMmap(WorldMmap* mmap) {
    if (mmap->addr && mmap->size > 0) munmap(mmap->addr, mmap->size);
    if (mmap->fd >= 0) close(mmap->fd);
    *mmap = WorldMmap{};
}

} // namespace lmc
