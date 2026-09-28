#include "vulkan_swapchain.h"
#include "common/log.h"
#include "pipeline_cache.h"

#include <vector>

namespace lmc {

namespace {

const VkApplicationInfo kAppInfo = {
    .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
    .pApplicationName = "LMC Launcher",
    .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
    .pEngineName = "LMC-Core",
    .engineVersion = VK_MAKE_VERSION(0, 1, 0),
    .apiVersion = VK_API_VERSION_1_1, // Android 10 保证 1.1 支持
};

#define VK_CHECK(expr)                                                        \
    do {                                                                      \
        const VkResult _rc = (expr);                                          \
        if (_rc != VK_SUCCESS) {                                              \
            LOGE("Vulkan 调用失败 %s -> %d (%s:%d)", #expr, _rc, __FILE__,     \
                 __LINE__);                                                   \
            return false;                                                     \
        }                                                                     \
    } while (0)

} // namespace

bool VulkanSwapchain::init(ANativeWindow* window, const std::string& pipelineCachePath) {
    pipelineCachePath_ = pipelineCachePath;
    if (!createInstance()) return false;
    if (!createSurface(window)) return false;
    if (!pickPhysicalDevice()) return false;
    if (!createLogicalDevice()) return false;
    if (!createPipelineCache(pipelineCachePath_)) return false;
    if (!createSwapchain(window)) return false;
    if (!createImageViews()) return false;
    if (!createRenderPass()) return false;
    if (!createSyncObjects()) return false;
    LOGI("Vulkan 显示链路就绪: %ux%u, 交换链 %zu 图", extent_.width, extent_.height, images_.size());
    return true;
}

bool VulkanSwapchain::createInstance() {
    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME, // Android 平台 surface
    };
    VkInstanceCreateInfo ci{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &kAppInfo,
        .enabledExtensionCount = 2,
        .ppEnabledExtensionNames = extensions,
    };
    VK_CHECK(vkCreateInstance(&ci, nullptr, &instance_));
    return true;
}

bool VulkanSwapchain::createSurface(ANativeWindow* window) {
    VkAndroidSurfaceCreateInfoKHR ci{
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = window, // 直接使用框架下发的 ANativeWindow
    };
    VK_CHECK(vkCreateAndroidSurfaceKHR(instance_, &ci, nullptr, &surface_));
    ownsWindow_ = true;
    return true;
}

bool VulkanSwapchain::pickPhysicalDevice() {
    uint32_t count = 0;
    VK_CHECK(vkEnumeratePhysicalDevices(instance_, &count, nullptr));
    if (count == 0) {
        LOGE("无可用 Vulkan 物理设备（Turnip 未加载或驱动不可用）");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    VK_CHECK(vkEnumeratePhysicalDevices(instance_, &count, devices.data()));

    // 优先独立 GPU / Turnip（vendorId 0x5143 = Qualcomm Adreno 开源驱动）
    physicalDevice_ = devices[0];
    for (VkPhysicalDevice d : devices) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(d, &props);
        LOGI("物理设备: %s (vendor=0x%04x)", props.deviceName, props.vendorID);
        if (props.vendorID == 0x5143) physicalDevice_ = d; // Turnip 优先
    }
    return true;
}

bool VulkanSwapchain::createLogicalDevice() {
    // 查询队列族，找 GRAPHICS +PRESENT
    uint32_t famCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice_, &famCount, nullptr);
    std::vector<VkQueueFamilyProperties> fams(famCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice_, &famCount, fams.data());

    bool found = false;
    for (uint32_t i = 0; i < famCount; ++i) {
        VkBool32 presentOk = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice_, i, surface_, &presentOk);
        if ((fams[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presentOk) {
            graphicsFamily_ = i;
            presentFamily_ = i; // 同族最理想，避免跨队列同步
            found = true;
            break;
        }
    }
    if (!found) {
        LOGE("找不到同时支持 GRAPHICS+PRESENT 的队列族");
        return false;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueCi{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = graphicsFamily_,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo ci{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueCi,
        .enabledExtensionCount = 1,
        .ppEnabledExtensionNames = deviceExtensions,
    };
    VK_CHECK(vkCreateDevice(physicalDevice_, &ci, nullptr, &device_));
    vkGetDeviceQueue(device_, graphicsFamily_, 0, &graphicsQueue_);
    vkGetDeviceQueue(device_, presentFamily_, 0, &presentQueue_);
    return true;
}

bool VulkanSwapchain::createSwapchain(ANativeWindow* window) {
    // 表面能力
    VkSurfaceCapabilitiesKHR caps{};
    VK_CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps));

    // 选格式：BGRA8 UNORM 优先（线性无 gamma 二次转换，移动端最稳）
    uint32_t fmtCount = 0;
    VK_CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &fmtCount, nullptr));
    std::vector<VkSurfaceFormatKHR> formats(fmtCount);
    VK_CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &fmtCount, formats.data()));
    surfaceFormat_ = formats[0];
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_B8G8R8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            surfaceFormat_ = f;
            break;
        }
    }

    // 尺寸：跟随窗口；FIFO 模式（V-Sync，防撕裂；MAILBOX 在支持的设备上由上层选择）
    extent_ = caps.currentExtent;
    if (extent_.width == UINT32_MAX) {
        extent_.width = static_cast<uint32_t>(ANativeWindow_getWidth(window));
        extent_.height = static_cast<uint32_t>(ANativeWindow_getHeight(window));
    }
    uint32_t imageCount = caps.minImageCount + 1; // 双缓冲起步
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) imageCount = caps.maxImageCount;

    VkSwapchainCreateInfoKHR ci{
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = surface_,
        .minImageCount = imageCount,
        .imageFormat = surfaceFormat_.format,
        .imageColorSpace = surfaceFormat_.colorSpace,
        .imageExtent = extent_,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE, // 图形/展示同队列族
        .preTransform = caps.currentTransform,
        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR,
        .clipped = VK_TRUE,
        .oldSwapchain = VK_NULL_HANDLE,
    };
    VK_CHECK(vkCreateSwapchainKHR(device_, &ci, nullptr, &swapchain_));

    uint32_t n = 0;
    VK_CHECK(vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr));
    images_.resize(n);
    VK_CHECK(vkGetSwapchainImagesKHR(device_, swapchain_, &n, images_.data()));
    return true;
}

bool VulkanSwapchain::createImageViews() {
    imageViews_.resize(images_.size());
    for (size_t i = 0; i < images_.size(); ++i) {
        VkImageViewCreateInfo ci{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = images_[i],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = surfaceFormat_.format,
            .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        VK_CHECK(vkCreateImageView(device_, &ci, nullptr, &imageViews_[i]));
    }
    return true;
}

bool VulkanSwapchain::createRenderPass() {
    // 简单颜色附件 renderPass，供原生渲染器直接使用
    VkAttachmentDescription color{
        .format = surfaceFormat_.format,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
    };
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1,
        .pColorAttachments = &ref,
    };
    VkSubpassDependency dep{
        .srcSubpass = VK_SUBPASS_EXTERNAL,
        .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
    };
    VkRenderPassCreateInfo ci{
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &color,
        .subpassCount = 1,
        .pSubpasses = &subpass,
        .dependencyCount = 1,
        .pDependencies = &dep,
    };
    VK_CHECK(vkCreateRenderPass(device_, &ci, nullptr, &renderPass_));

    // framebuffer 与交换链图像一一对应
    framebuffers_.resize(imageViews_.size());
    for (size_t i = 0; i < imageViews_.size(); ++i) {
        VkFramebufferCreateInfo fci{
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = renderPass_,
            .attachmentCount = 1,
            .pAttachments = &imageViews_[i],
            .width = extent_.width,
            .height = extent_.height,
            .layers = 1,
        };
        VK_CHECK(vkCreateFramebuffer(device_, &fci, nullptr, &framebuffers_[i]));
    }
    return true;
}

bool VulkanSwapchain::createSyncObjects() {
    VkSemaphoreCreateInfo sci{.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fci{.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                          .flags = VK_FENCE_CREATE_SIGNALED_BIT};
    VK_CHECK(vkCreateSemaphore(device_, &sci, nullptr, &imageAvailable_));
    VK_CHECK(vkCreateSemaphore(device_, &sci, nullptr, &renderDone_));
    VK_CHECK(vkCreateFence(device_, &fci, nullptr, &inFlight_));
    return true;
}

bool VulkanSwapchain::createPipelineCache(const std::string& path) {
    pipelineCache_ = lmc::loadOrCreatePipelineCache(device_, physicalDevice_, path);
    return pipelineCache_ != VK_NULL_HANDLE;
}

bool VulkanSwapchain::acquireNextImage(uint32_t* imageIndex) {
    vkWaitForFences(device_, 1, &inFlight_, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &inFlight_);
    const VkResult rc = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                              imageAvailable_, VK_NULL_HANDLE, imageIndex);
    if (rc == VK_ERROR_OUT_OF_DATE_KHR || rc == VK_SUBOPTIMAL_KHR) {
        // 旋转/分辨率变化：交由上层 recreate（此处仅报告）
        return false;
    }
    return rc == VK_SUCCESS;
}

bool VulkanSwapchain::present(uint32_t imageIndex, VkSemaphore renderDone) {
    VkPresentInfoKHR pi{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderDone,
        .swapchainCount = 1,
        .pSwapchains = &swapchain_,
        .pImageIndices = &imageIndex,
    };
    return vkQueuePresentKHR(presentQueue_, &pi) == VK_SUCCESS;
}

bool VulkanSwapchain::recreate(ANativeWindow* window) {
    vkDeviceWaitIdle(device_);
    destroySwapchainOnly();
    if (!createSwapchain(window)) return false;
    if (!createImageViews()) return false;
    if (!createRenderPass()) return false;
    LOGI("swapchain 重建完成: %ux%u", extent_.width, extent_.height);
    return true;
}

void VulkanSwapchain::destroySwapchainOnly() {
    for (VkFramebuffer fb : framebuffers_) if (fb) vkDestroyFramebuffer(device_, fb, nullptr);
    framebuffers_.clear();
    for (VkImageView v : imageViews_) if (v) vkDestroyImageView(device_, v, nullptr);
    imageViews_.clear();
    if (renderPass_) { vkDestroyRenderPass(device_, renderPass_, nullptr); renderPass_ = VK_NULL_HANDLE; }
    if (swapchain_) { vkDestroySwapchainKHR(device_, swapchain_, nullptr); swapchain_ = VK_NULL_HANDLE; }
    images_.clear();
}

void VulkanSwapchain::destroy() {
    if (!device_) return;
    vkDeviceWaitIdle(device_);
    destroySwapchainOnly();
    if (inFlight_) vkDestroyFence(device_, inFlight_, nullptr);
    if (imageAvailable_) vkDestroySemaphore(device_, imageAvailable_, nullptr);
    if (renderDone_) vkDestroySemaphore(device_, renderDone_, nullptr);
    if (pipelineCache_) {
        lmc::savePipelineCache(device_, pipelineCache_, pipelineCachePath_);
        vkDestroyPipelineCache(device_, pipelineCache_, nullptr);
        pipelineCache_ = VK_NULL_HANDLE;
    }
    if (device_) { vkDestroyDevice(device_, nullptr); device_ = VK_NULL_HANDLE; }
    if (surface_) { vkDestroySurfaceKHR(instance_, surface_, nullptr); surface_ = VK_NULL_HANDLE; }
    if (instance_) { vkDestroyInstance(instance_, nullptr); instance_ = VK_NULL_HANDLE; }
}

} // namespace lmc
