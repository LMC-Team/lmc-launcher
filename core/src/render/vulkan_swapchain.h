// Vulkan swapchain：直接对接 ANativeWindow 的完整显示链路。
//
// 用途：
//  - VulkanNative 模式（VulkanMod 思路的原生渲染器）的主显示通路；
//  - 同时也是 swapchain 生命周期管理的参考实现（Zink 路径下由 Mesa 内部完成）。
//
// 绕过说明：ANativeWindow 是 BufferQueue 的生产者端，Vulkan WSI 直接向它提交图像，
// 与 SurfaceView/EGL 链路相同的最终合成路径，但省去了 Java Surface 抽象与
// GL 翻译层开销。
#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/native_window.h>
#include <cstdint>
#include <string>
#include <vector>

namespace lmc {

class VulkanSwapchain {
public:
    ~VulkanSwapchain() { destroy(); }

    /** 全链路初始化：instance → surface(ANativeWindow) → device → swapchain → 同步原语 */
    bool init(ANativeWindow* window, const std::string& pipelineCachePath);

    /** 窗口尺寸变化时重建 swapchain（保留 instance/device） */
    bool recreate(ANativeWindow* window);

    /** 获取下一帧图像索引（内部处理子优化态与重建） */
    bool acquireNextImage(uint32_t* imageIndex);

    /** 提交图像到显示队列 */
    bool present(uint32_t imageIndex, VkSemaphore renderDone);

    // 供渲染器使用的访问器
    VkInstance instance() const { return instance_; }
    VkDevice device() const { return device_; }
    VkQueue graphicsQueue() const { return graphicsQueue_; }
    uint32_t graphicsQueueFamily() const { return graphicsFamily_; }
    VkSwapchainKHR swapchain() const { return swapchain_; }
    const std::vector<VkImage>& images() const { return images_; }
    const std::vector<VkImageView>& imageViews() const { return imageViews_; }
    VkFormat format() const { return surfaceFormat_.format; }
    VkExtent2D extent() const { return extent_; }
    VkSemaphore imageAvailableSemaphore() const { return imageAvailable_; }
    VkRenderPass renderPass() const { return renderPass_; }
    VkPipelineCache pipelineCache() const { return pipelineCache_; }

    void destroy();

private:
    bool createInstance();
    bool createSurface(ANativeWindow* window);
    bool pickPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain(ANativeWindow* window);
    bool createImageViews();
    bool createRenderPass();
    bool createSyncObjects();
    bool createPipelineCache(const std::string& path);
    void destroySwapchainOnly();

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t graphicsFamily_ = 0;
    uint32_t presentFamily_ = 0;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    VkQueue presentQueue_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkSurfaceFormatKHR surfaceFormat_{};
    VkExtent2D extent_{};
    std::vector<VkImage> images_;
    std::vector<VkImageView> imageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    VkSemaphore renderDone_ = VK_NULL_HANDLE;
    VkFence inFlight_ = VK_NULL_HANDLE;
    // pipeline cache：持久化到磁盘消除着色器编译卡顿（见 pipeline_cache.h/.cpp）
    VkPipelineCache pipelineCache_ = VK_NULL_HANDLE;
    std::string pipelineCachePath_;
    bool ownsWindow_ = false;
};

} // namespace lmc
