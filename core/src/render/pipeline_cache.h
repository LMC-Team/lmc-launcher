// Vulkan pipeline cache 持久化：
// 首次运行时把驱动生成的 pipeline 着色器编译产物写入磁盘，
// 之后每次启动直接 mmap 加载，消除 Minecraft 世界加载时的着色器编译卡顿。
#pragma once

#include <vulkan/vulkan.h>
#include <string>

namespace lmc {

/**
 * 从磁盘加载既有 cache 数据创建 VkPipelineCache；文件不存在则为空 cache。
 * 厂商标识（vendorId/deviceId）不一致时驱动会自动忽略不匹配的数据，安全。
 */
VkPipelineCache loadOrCreatePipelineCache(VkDevice device,
                                          VkPhysicalDevice physicalDevice,
                                          const std::string& path);

/** 把当前 cache 内容落盘（在 vkDeviceWaitIdle 之后 / 游戏退出前调用） */
bool savePipelineCache(VkDevice device, VkPipelineCache cache, const std::string& path);

} // namespace lmc
