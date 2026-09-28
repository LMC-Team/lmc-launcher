#include "pipeline_cache.h"
#include "common/log.h"

#include <cstdio>
#include <vector>

namespace lmc {

VkPipelineCache loadOrCreatePipelineCache(VkDevice device,
                                          VkPhysicalDevice physicalDevice,
                                          const std::string& path) {
    // 物理设备指纹写入 cache 头部校验用（由驱动内部处理，这里仅为文件命名/日志）
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physicalDevice, &props);

    std::vector<uint8_t> initialData;
    if (FILE* f = fopen(path.c_str(), "rb")) {
        fseek(f, 0, SEEK_END);
        const long size = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (size > 0 && size < 256 * 1024 * 1024) { // 上限 256MB 防御异常文件
            initialData.resize(static_cast<size_t>(size));
            if (fread(initialData.data(), 1, initialData.size(), f) !=
                initialData.size()) {
                initialData.clear();
            }
        }
        fclose(f);
    }

    VkPipelineCacheCreateInfo ci{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO,
        .initialDataSize = initialData.size(),
        .pInitialData = initialData.empty() ? nullptr : initialData.data(),
    };
    VkPipelineCache cache = VK_NULL_HANDLE;
    if (vkCreatePipelineCache(device, &ci, nullptr, &cache) != VK_SUCCESS) {
        LOGE("创建 VkPipelineCache 失败: %s", path.c_str());
        return VK_NULL_HANDLE;
    }
    LOGI("pipeline cache %s: %s (%zu 字节, 设备 %s)",
         initialData.empty() ? "新建" : "命中", path.c_str(),
         initialData.size(), props.deviceName);
    return cache;
}

bool savePipelineCache(VkDevice device, VkPipelineCache cache, const std::string& path) {
    size_t size = 0;
    if (vkGetPipelineCacheData(device, cache, &size, nullptr) != VK_SUCCESS || size == 0) {
        return false;
    }
    std::vector<uint8_t> data(size);
    if (vkGetPipelineCacheData(device, cache, &size, data.data()) != VK_SUCCESS) {
        return false;
    }
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    const bool ok = fwrite(data.data(), 1, size, f) == size;
    fclose(f);
    LOGI("pipeline cache 落盘: %s (%zu 字节) %s", path.c_str(), size, ok ? "成功" : "失败");
    return ok;
}

} // namespace lmc
