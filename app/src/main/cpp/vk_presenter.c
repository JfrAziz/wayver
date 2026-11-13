#define VK_USE_PLATFORM_ANDROID_KHR
#include "vk_presenter.h"
#include "vk_loader.h"

#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "LinuxDesktopMVP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_SWAPCHAIN_IMAGES 8

struct VkPresenter {
    pthread_mutex_t lock;
    VkLoader loader;
    VkInstance instance;
    VkPhysicalDevice phys_device;
    VkPhysicalDeviceMemoryProperties mem_props;
    VkDevice device;
    VkQueue queue;
    uint32_t queue_family;

    VkSurfaceKHR surface;
    VkSwapchainKHR swapchain;
    VkFormat swapchain_format;
    VkExtent2D extent;
    uint32_t image_count;
    VkImage images[MAX_SWAPCHAIN_IMAGES];

    VkCommandPool cmd_pool;
    VkCommandBuffer cmd_buf;
    VkSemaphore sem_avail;
    VkSemaphore sem_done;
    VkFence fence;

    /* Staging buffer & image for uploading wl_shm / CPU pixels */
    VkBuffer staging_buf;
    VkDeviceMemory staging_mem;
    VkDeviceSize staging_size;
    VkImage upload_img;
    VkDeviceMemory upload_mem;
    uint32_t upload_w;
    uint32_t upload_h;
    VkFormat upload_fmt;

    char info[512];

    PFN_vkCreateInstance p_vkCreateInstance;
    PFN_vkDestroyInstance p_vkDestroyInstance;
    PFN_vkEnumeratePhysicalDevices p_vkEnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties p_vkGetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties p_vkGetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties p_vkGetPhysicalDeviceQueueFamilyProperties;
    PFN_vkCreateDevice p_vkCreateDevice;
    PFN_vkDestroyDevice p_vkDestroyDevice;
    PFN_vkGetDeviceQueue p_vkGetDeviceQueue;
    PFN_vkCreateAndroidSurfaceKHR p_vkCreateAndroidSurfaceKHR;
    PFN_vkDestroySurfaceKHR p_vkDestroySurfaceKHR;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR p_vkGetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR p_vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR p_vkGetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkCreateSwapchainKHR p_vkCreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR p_vkDestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR p_vkGetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR p_vkAcquireNextImageKHR;
    PFN_vkQueuePresentKHR p_vkQueuePresentKHR;
    PFN_vkCreateCommandPool p_vkCreateCommandPool;
    PFN_vkDestroyCommandPool p_vkDestroyCommandPool;
    PFN_vkAllocateCommandBuffers p_vkAllocateCommandBuffers;
    PFN_vkBeginCommandBuffer p_vkBeginCommandBuffer;
    PFN_vkEndCommandBuffer p_vkEndCommandBuffer;
    PFN_vkResetCommandBuffer p_vkResetCommandBuffer;
    PFN_vkCmdPipelineBarrier p_vkCmdPipelineBarrier;
    PFN_vkCmdClearColorImage p_vkCmdClearColorImage;
    PFN_vkCmdCopyBufferToImage p_vkCmdCopyBufferToImage;
    PFN_vkCmdBlitImage p_vkCmdBlitImage;
    PFN_vkCreateBuffer p_vkCreateBuffer;
    PFN_vkDestroyBuffer p_vkDestroyBuffer;
    PFN_vkGetBufferMemoryRequirements p_vkGetBufferMemoryRequirements;
    PFN_vkCreateImage p_vkCreateImage;
    PFN_vkDestroyImage p_vkDestroyImage;
    PFN_vkGetImageMemoryRequirements p_vkGetImageMemoryRequirements;
    PFN_vkAllocateMemory p_vkAllocateMemory;
    PFN_vkFreeMemory p_vkFreeMemory;
    PFN_vkBindBufferMemory p_vkBindBufferMemory;
    PFN_vkBindImageMemory p_vkBindImageMemory;
    PFN_vkMapMemory p_vkMapMemory;
    PFN_vkUnmapMemory p_vkUnmapMemory;
    PFN_vkCreateSemaphore p_vkCreateSemaphore;
    PFN_vkDestroySemaphore p_vkDestroySemaphore;
    PFN_vkCreateFence p_vkCreateFence;
    PFN_vkDestroyFence p_vkDestroyFence;
    PFN_vkWaitForFences p_vkWaitForFences;
    PFN_vkResetFences p_vkResetFences;
    PFN_vkQueueSubmit p_vkQueueSubmit;
    PFN_vkDeviceWaitIdle p_vkDeviceWaitIdle;
};

#define LOAD_GLOBAL(p, name) (p)->p_##name = (PFN_##name)(p)->loader.get_instance_proc_addr(VK_NULL_HANDLE, #name)
#define LOAD_INST(p, name) (p)->p_##name = (PFN_##name)(p)->loader.get_instance_proc_addr((p)->instance, #name)

static uint32_t find_mem_type(VkPresenter *p, uint32_t type_bits, VkMemoryPropertyFlags req) {
    for (uint32_t i = 0; i < p->mem_props.memoryTypeCount; i++) {
        if ((type_bits & (1u << i)) && (p->mem_props.memoryTypes[i].propertyFlags & req) == req) {
            return i;
        }
    }
    return UINT32_MAX;
}

static void destroy_upload_resources(VkPresenter *p) {
    if (!p->device)
        return;
    if (p->upload_img) {
        p->p_vkDestroyImage(p->device, p->upload_img, NULL);
        p->upload_img = VK_NULL_HANDLE;
    }
    if (p->upload_mem) {
        p->p_vkFreeMemory(p->device, p->upload_mem, NULL);
        p->upload_mem = VK_NULL_HANDLE;
    }
    if (p->staging_buf) {
        p->p_vkDestroyBuffer(p->device, p->staging_buf, NULL);
        p->staging_buf = VK_NULL_HANDLE;
    }
    if (p->staging_mem) {
        p->p_vkFreeMemory(p->device, p->staging_mem, NULL);
        p->staging_mem = VK_NULL_HANDLE;
    }
    p->staging_size = 0;
    p->upload_w = 0;
    p->upload_h = 0;
    p->upload_fmt = VK_FORMAT_UNDEFINED;
}

static bool ensure_upload_resources(VkPresenter *p, uint32_t w, uint32_t h, VkFormat fmt) {
    VkDeviceSize needed = (VkDeviceSize)w * (VkDeviceSize)h * 4;
    if (p->staging_buf && p->staging_size >= needed && p->upload_img && p->upload_w == w && p->upload_h == h &&
        p->upload_fmt == fmt) {
        return true;
    }

    p->p_vkDeviceWaitIdle(p->device);
    destroy_upload_resources(p);

    VkBufferCreateInfo bci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = needed,
        .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (p->p_vkCreateBuffer(p->device, &bci, NULL, &p->staging_buf) != VK_SUCCESS)
        return false;

    VkMemoryRequirements breq;
    p->p_vkGetBufferMemoryRequirements(p->device, p->staging_buf, &breq);
    uint32_t btype = find_mem_type(p, breq.memoryTypeBits,
                                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (btype == UINT32_MAX)
        return false;

    VkMemoryAllocateInfo bai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = breq.size,
        .memoryTypeIndex = btype,
    };
    if (p->p_vkAllocateMemory(p->device, &bai, NULL, &p->staging_mem) != VK_SUCCESS)
        return false;
    p->p_vkBindBufferMemory(p->device, p->staging_buf, p->staging_mem, 0);
    p->staging_size = needed;

    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = fmt,
        .extent = {w, h, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (p->p_vkCreateImage(p->device, &ici, NULL, &p->upload_img) != VK_SUCCESS)
        return false;

    VkMemoryRequirements ireq;
    p->p_vkGetImageMemoryRequirements(p->device, p->upload_img, &ireq);
    uint32_t itype = find_mem_type(p, ireq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (itype == UINT32_MAX)
        itype = find_mem_type(p, ireq.memoryTypeBits, 0);
    if (itype == UINT32_MAX)
        return false;

    VkMemoryAllocateInfo iai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = ireq.size,
        .memoryTypeIndex = itype,
    };
    if (p->p_vkAllocateMemory(p->device, &iai, NULL, &p->upload_mem) != VK_SUCCESS)
        return false;
    p->p_vkBindImageMemory(p->device, p->upload_img, p->upload_mem, 0);
    p->upload_w = w;
    p->upload_h = h;
    p->upload_fmt = fmt;
    return true;
}

static void destroy_swapchain(VkPresenter *p) {
    if (!p->device)
        return;
    if (p->p_vkDeviceWaitIdle)
        p->p_vkDeviceWaitIdle(p->device);
    if (p->swapchain && p->p_vkDestroySwapchainKHR) {
        p->p_vkDestroySwapchainKHR(p->device, p->swapchain, NULL);
        p->swapchain = VK_NULL_HANDLE;
    }
    p->image_count = 0;
}

static void destroy_surface(VkPresenter *p) {
    destroy_swapchain(p);
    if (p->surface && p->instance && p->p_vkDestroySurfaceKHR) {
        p->p_vkDestroySurfaceKHR(p->instance, p->surface, NULL);
        p->surface = VK_NULL_HANDLE;
    }
}

VkPresenter *vk_presenter_create(const char *custom_driver_path) {
    VkPresenter *p = (VkPresenter *)calloc(1, sizeof(VkPresenter));
    if (!p)
        return NULL;
    pthread_mutex_init(&p->lock, NULL);

    if (!vk_loader_open(&p->loader, custom_driver_path)) {
        snprintf(p->info, sizeof(p->info), "Failed to open Vulkan loader");
        LOGE("%s", p->info);
        return p;
    }

    LOAD_GLOBAL(p, vkCreateInstance);
    if (!p->p_vkCreateInstance) {
        snprintf(p->info, sizeof(p->info), "Missing vkCreateInstance");
        LOGE("%s", p->info);
        return p;
    }

    const char *exts[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };

    VkApplicationInfo app_info = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "LinuxDesktopMVP",
        .applicationVersion = VK_MAKE_VERSION(0, 1, 0),
        .pEngineName = "LinuxDesktopCompositor",
        .engineVersion = VK_MAKE_VERSION(0, 1, 0),
        .apiVersion = VK_API_VERSION_1_0,
    };

    VkInstanceCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &app_info,
        .enabledExtensionCount = 2,
        .ppEnabledExtensionNames = exts,
    };

    VkResult res = p->p_vkCreateInstance(&ici, NULL, &p->instance);
    if (res != VK_SUCCESS) {
        snprintf(p->info, sizeof(p->info), "vkCreateInstance failed: %d", res);
        LOGE("%s", p->info);
        return p;
    }

    LOAD_INST(p, vkDestroyInstance);
    LOAD_INST(p, vkEnumeratePhysicalDevices);
    LOAD_INST(p, vkGetPhysicalDeviceProperties);
    LOAD_INST(p, vkGetPhysicalDeviceMemoryProperties);
    LOAD_INST(p, vkGetPhysicalDeviceQueueFamilyProperties);
    LOAD_INST(p, vkCreateDevice);
    LOAD_INST(p, vkDestroyDevice);
    LOAD_INST(p, vkGetDeviceQueue);
    LOAD_INST(p, vkCreateAndroidSurfaceKHR);
    LOAD_INST(p, vkDestroySurfaceKHR);
    LOAD_INST(p, vkGetPhysicalDeviceSurfaceSupportKHR);
    LOAD_INST(p, vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_INST(p, vkGetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_INST(p, vkCreateSwapchainKHR);
    LOAD_INST(p, vkDestroySwapchainKHR);
    LOAD_INST(p, vkGetSwapchainImagesKHR);
    LOAD_INST(p, vkAcquireNextImageKHR);
    LOAD_INST(p, vkQueuePresentKHR);
    LOAD_INST(p, vkCreateCommandPool);
    LOAD_INST(p, vkDestroyCommandPool);
    LOAD_INST(p, vkAllocateCommandBuffers);
    LOAD_INST(p, vkBeginCommandBuffer);
    LOAD_INST(p, vkEndCommandBuffer);
    LOAD_INST(p, vkResetCommandBuffer);
    LOAD_INST(p, vkCmdPipelineBarrier);
    LOAD_INST(p, vkCmdClearColorImage);
    LOAD_INST(p, vkCmdCopyBufferToImage);
    LOAD_INST(p, vkCmdBlitImage);
    LOAD_INST(p, vkCreateBuffer);
    LOAD_INST(p, vkDestroyBuffer);
    LOAD_INST(p, vkGetBufferMemoryRequirements);
    LOAD_INST(p, vkCreateImage);
    LOAD_INST(p, vkDestroyImage);
    LOAD_INST(p, vkGetImageMemoryRequirements);
    LOAD_INST(p, vkAllocateMemory);
    LOAD_INST(p, vkFreeMemory);
    LOAD_INST(p, vkBindBufferMemory);
    LOAD_INST(p, vkBindImageMemory);
    LOAD_INST(p, vkMapMemory);
    LOAD_INST(p, vkUnmapMemory);
    LOAD_INST(p, vkCreateSemaphore);
    LOAD_INST(p, vkDestroySemaphore);
    LOAD_INST(p, vkCreateFence);
    LOAD_INST(p, vkDestroyFence);
    LOAD_INST(p, vkWaitForFences);
    LOAD_INST(p, vkResetFences);
    LOAD_INST(p, vkQueueSubmit);
    LOAD_INST(p, vkDeviceWaitIdle);

    uint32_t gpu_count = 0;
    p->p_vkEnumeratePhysicalDevices(p->instance, &gpu_count, NULL);
    if (gpu_count == 0) {
        snprintf(p->info, sizeof(p->info), "No Vulkan physical devices found");
        LOGE("%s", p->info);
        return p;
    }

    VkPhysicalDevice devs[8];
    if (gpu_count > 8)
        gpu_count = 8;
    p->p_vkEnumeratePhysicalDevices(p->instance, &gpu_count, devs);
    p->phys_device = devs[0];

    VkPhysicalDeviceProperties props;
    p->p_vkGetPhysicalDeviceProperties(p->phys_device, &props);
    p->p_vkGetPhysicalDeviceMemoryProperties(p->phys_device, &p->mem_props);

    uint32_t qf_count = 0;
    p->p_vkGetPhysicalDeviceQueueFamilyProperties(p->phys_device, &qf_count, NULL);
    VkQueueFamilyProperties qfs[16];
    if (qf_count > 16)
        qf_count = 16;
    p->p_vkGetPhysicalDeviceQueueFamilyProperties(p->phys_device, &qf_count, qfs);

    p->queue_family = 0;
    for (uint32_t i = 0; i < qf_count; i++) {
        if (qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            p->queue_family = i;
            break;
        }
    }

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = p->queue_family,
        .queueCount = 1,
        .pQueuePriorities = &prio,
    };

    const char *dev_exts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo dci = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &qci,
        .enabledExtensionCount = 1,
        .ppEnabledExtensionNames = dev_exts,
    };

    res = p->p_vkCreateDevice(p->phys_device, &dci, NULL, &p->device);
    if (res != VK_SUCCESS) {
        snprintf(p->info, sizeof(p->info), "vkCreateDevice failed: %d (%s)", res, props.deviceName);
        LOGE("%s", p->info);
        return p;
    }

    p->p_vkGetDeviceQueue(p->device, p->queue_family, 0, &p->queue);

    VkCommandPoolCreateInfo cpci = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = p->queue_family,
    };
    p->p_vkCreateCommandPool(p->device, &cpci, NULL, &p->cmd_pool);

    VkCommandBufferAllocateInfo cbai = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = p->cmd_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    p->p_vkAllocateCommandBuffers(p->device, &cbai, &p->cmd_buf);

    VkSemaphoreCreateInfo sci = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    p->p_vkCreateSemaphore(p->device, &sci, NULL, &p->sem_avail);
    p->p_vkCreateSemaphore(p->device, &sci, NULL, &p->sem_done);

    VkFenceCreateInfo fci = {
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
        .flags = VK_FENCE_CREATE_SIGNALED_BIT,
    };
    p->p_vkCreateFence(p->device, &fci, NULL, &p->fence);

    snprintf(p->info, sizeof(p->info), "GPU: %s | Vulkan %u.%u.%u | Driver: %s", props.deviceName,
             VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion),
             p->loader.is_custom_driver ? "Custom" : "System");
    LOGI("%s", p->info);
    return p;
}

bool vk_presenter_set_window(VkPresenter *p, ANativeWindow *window, int width, int height) {
    if (!p || !p->device)
        return false;
    pthread_mutex_lock(&p->lock);
    destroy_surface(p);

    if (!window || width <= 0 || height <= 0) {
        pthread_mutex_unlock(&p->lock);
        return true;
    }

    VkAndroidSurfaceCreateInfoKHR asci = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = window,
    };
    VkResult res = p->p_vkCreateAndroidSurfaceKHR(p->instance, &asci, NULL, &p->surface);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateAndroidSurfaceKHR failed: %d", res);
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    VkBool32 supported = VK_FALSE;
    p->p_vkGetPhysicalDeviceSurfaceSupportKHR(p->phys_device, p->queue_family, p->surface, &supported);
    if (!supported) {
        LOGE("Queue family %u does not support Android surface", p->queue_family);
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    VkSurfaceCapabilitiesKHR caps;
    p->p_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(p->phys_device, p->surface, &caps);

    uint32_t fmt_count = 0;
    p->p_vkGetPhysicalDeviceSurfaceFormatsKHR(p->phys_device, p->surface, &fmt_count, NULL);
    VkSurfaceFormatKHR fmts[16];
    if (fmt_count > 16)
        fmt_count = 16;
    p->p_vkGetPhysicalDeviceSurfaceFormatsKHR(p->phys_device, p->surface, &fmt_count, fmts);

    VkSurfaceFormatKHR chosen = fmts[0];
    for (uint32_t i = 0; i < fmt_count; i++) {
        if (fmts[i].format == VK_FORMAT_R8G8B8A8_UNORM) {
            chosen = fmts[i];
            break;
        }
    }

    p->swapchain_format = chosen.format;
    p->extent.width = (uint32_t)width;
    p->extent.height = (uint32_t)height;
    if (caps.currentExtent.width != 0xFFFFFFFF) {
        p->extent = caps.currentExtent;
    }

    uint32_t min_images = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && min_images > caps.maxImageCount) {
        min_images = caps.maxImageCount;
    }

    VkSwapchainCreateInfoKHR swci = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = p->surface,
        .minImageCount = min_images,
        .imageFormat = chosen.format,
        .imageColorSpace = chosen.colorSpace,
        .imageExtent = p->extent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR,
        .compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR,
        .clipped = VK_TRUE,
    };

    res = p->p_vkCreateSwapchainKHR(p->device, &swci, NULL, &p->swapchain);
    if (res != VK_SUCCESS) {
        LOGE("vkCreateSwapchainKHR failed: %d", res);
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    uint32_t img_count = 0;
    p->p_vkGetSwapchainImagesKHR(p->device, p->swapchain, &img_count, NULL);
    if (img_count > MAX_SWAPCHAIN_IMAGES)
        img_count = MAX_SWAPCHAIN_IMAGES;
    p->p_vkGetSwapchainImagesKHR(p->device, p->swapchain, &img_count, p->images);
    p->image_count = img_count;

    LOGI("Swapchain ready: %ux%u (%u images, fmt=%d)", p->extent.width, p->extent.height, p->image_count,
         p->swapchain_format);
    pthread_mutex_unlock(&p->lock);
    return true;
}

bool vk_presenter_draw_clear(VkPresenter *p, float r, float g, float b, float a) {
    if (!p || !p->device)
        return false;
    pthread_mutex_lock(&p->lock);
    if (!p->swapchain) {
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    p->p_vkWaitForFences(p->device, 1, &p->fence, VK_TRUE, UINT64_MAX);
    p->p_vkResetFences(p->device, 1, &p->fence);

    uint32_t img_idx = 0;
    VkResult res =
        p->p_vkAcquireNextImageKHR(p->device, p->swapchain, UINT64_MAX, p->sem_avail, VK_NULL_HANDLE, &img_idx);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        LOGE("vkAcquireNextImageKHR failed: %d", res);
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    p->p_vkResetCommandBuffer(p->cmd_buf, 0);
    VkCommandBufferBeginInfo cbbi = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    p->p_vkBeginCommandBuffer(p->cmd_buf, &cbbi);

    VkImageSubresourceRange range = {
        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
        .baseMipLevel = 0,
        .levelCount = 1,
        .baseArrayLayer = 0,
        .layerCount = 1,
    };

    VkImageMemoryBarrier to_clear = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = p->images[img_idx],
        .subresourceRange = range,
    };
    p->p_vkCmdPipelineBarrier(p->cmd_buf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL,
                              0, NULL, 1, &to_clear);

    VkClearColorValue color = {{r, g, b, a}};
    p->p_vkCmdClearColorImage(p->cmd_buf, p->images[img_idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &range);

    VkImageMemoryBarrier to_present = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = 0,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = p->images[img_idx],
        .subresourceRange = range,
    };
    p->p_vkCmdPipelineBarrier(p->cmd_buf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                              NULL, 0, NULL, 1, &to_present);

    p->p_vkEndCommandBuffer(p->cmd_buf);

    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &p->sem_avail,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1,
        .pCommandBuffers = &p->cmd_buf,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &p->sem_done,
    };
    res = p->p_vkQueueSubmit(p->queue, 1, &si, p->fence);
    if (res != VK_SUCCESS) {
        LOGE("vkQueueSubmit failed: %d", res);
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    VkPresentInfoKHR pi = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &p->sem_done,
        .swapchainCount = 1,
        .pSwapchains = &p->swapchain,
        .pImageIndices = &img_idx,
    };
    res = p->p_vkQueuePresentKHR(p->queue, &pi);
    pthread_mutex_unlock(&p->lock);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        LOGE("vkQueuePresentKHR failed: %d", res);
        return false;
    }

    LOGI("Presented clear frame #%u", img_idx);
    return true;
}

bool vk_presenter_present_pixels(VkPresenter *p, const void *pixels, uint32_t width, uint32_t height,
                                 uint32_t stride_bytes, bool is_bgra) {
    if (!p || !p->device || !pixels || width == 0 || height == 0)
        return false;
    pthread_mutex_lock(&p->lock);
    if (!p->swapchain) {
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    p->p_vkWaitForFences(p->device, 1, &p->fence, VK_TRUE, UINT64_MAX);
    p->p_vkResetFences(p->device, 1, &p->fence);

    VkFormat src_fmt = is_bgra ? VK_FORMAT_B8G8R8A8_UNORM : VK_FORMAT_R8G8B8A8_UNORM;
    if (!ensure_upload_resources(p, width, height, src_fmt)) {
        LOGE("ensure_upload_resources failed for %ux%u", width, height);
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    void *mapped = NULL;
    if (p->p_vkMapMemory(p->device, p->staging_mem, 0, (VkDeviceSize)width * height * 4, 0, &mapped) != VK_SUCCESS) {
        pthread_mutex_unlock(&p->lock);
        return false;
    }
    const uint8_t *src = (const uint8_t *)pixels;
    uint8_t *dst = (uint8_t *)mapped;
    uint32_t row_bytes = width * 4;
    if (stride_bytes == row_bytes) {
        memcpy(dst, src, (size_t)row_bytes * height);
    } else {
        for (uint32_t y = 0; y < height; y++) {
            memcpy(dst + y * row_bytes, src + y * stride_bytes, row_bytes);
        }
    }
    p->p_vkUnmapMemory(p->device, p->staging_mem);

    uint32_t img_idx = 0;
    VkResult res =
        p->p_vkAcquireNextImageKHR(p->device, p->swapchain, UINT64_MAX, p->sem_avail, VK_NULL_HANDLE, &img_idx);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    p->p_vkResetCommandBuffer(p->cmd_buf, 0);
    VkCommandBufferBeginInfo cbbi = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    p->p_vkBeginCommandBuffer(p->cmd_buf, &cbbi);

    VkImageSubresourceRange range = {
        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
        .baseMipLevel = 0,
        .levelCount = 1,
        .baseArrayLayer = 0,
        .layerCount = 1,
    };

    VkImageMemoryBarrier barriers_before[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = p->upload_img,
            .subresourceRange = range,
        },
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = p->images[img_idx],
            .subresourceRange = range,
        },
    };
    p->p_vkCmdPipelineBarrier(p->cmd_buf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL,
                              0, NULL, 2, barriers_before);

    VkBufferImageCopy region = {
        .bufferOffset = 0,
        .bufferRowLength = 0,
        .bufferImageHeight = 0,
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageOffset = {0, 0, 0},
        .imageExtent = {width, height, 1},
    };
    p->p_vkCmdCopyBufferToImage(p->cmd_buf, p->staging_buf, p->upload_img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                                &region);

    VkImageMemoryBarrier upload_to_src = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = p->upload_img,
        .subresourceRange = range,
    };
    p->p_vkCmdPipelineBarrier(p->cmd_buf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0,
                              NULL, 1, &upload_to_src);

    VkImageBlit blit = {
        .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .srcOffsets = {{0, 0, 0}, {(int32_t)width, (int32_t)height, 1}},
        .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .dstOffsets = {{0, 0, 0}, {(int32_t)p->extent.width, (int32_t)p->extent.height, 1}},
    };
    VkFilter filter = (width == p->extent.width && height == p->extent.height) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    p->p_vkCmdBlitImage(p->cmd_buf, p->upload_img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, p->images[img_idx],
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, filter);

    VkImageMemoryBarrier to_present = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = 0,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = p->images[img_idx],
        .subresourceRange = range,
    };
    p->p_vkCmdPipelineBarrier(p->cmd_buf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                              NULL, 0, NULL, 1, &to_present);

    p->p_vkEndCommandBuffer(p->cmd_buf);

    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &p->sem_avail,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1,
        .pCommandBuffers = &p->cmd_buf,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &p->sem_done,
    };
    res = p->p_vkQueueSubmit(p->queue, 1, &si, p->fence);
    if (res != VK_SUCCESS) {
        pthread_mutex_unlock(&p->lock);
        return false;
    }

    VkPresentInfoKHR pi = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &p->sem_done,
        .swapchainCount = 1,
        .pSwapchains = &p->swapchain,
        .pImageIndices = &img_idx,
    };
    res = p->p_vkQueuePresentKHR(p->queue, &pi);
    pthread_mutex_unlock(&p->lock);
    return (res == VK_SUCCESS || res == VK_SUBOPTIMAL_KHR);
}

const char *vk_presenter_get_info(VkPresenter *p) {
    return p ? p->info : "No presenter";
}

void vk_presenter_destroy(VkPresenter *p) {
    if (!p)
        return;
    pthread_mutex_lock(&p->lock);
    destroy_surface(p);
    destroy_upload_resources(p);
    if (p->device) {
        if (p->fence && p->p_vkDestroyFence)
            p->p_vkDestroyFence(p->device, p->fence, NULL);
        if (p->sem_avail && p->p_vkDestroySemaphore)
            p->p_vkDestroySemaphore(p->device, p->sem_avail, NULL);
        if (p->sem_done && p->p_vkDestroySemaphore)
            p->p_vkDestroySemaphore(p->device, p->sem_done, NULL);
        if (p->cmd_pool && p->p_vkDestroyCommandPool)
            p->p_vkDestroyCommandPool(p->device, p->cmd_pool, NULL);
        if (p->p_vkDestroyDevice)
            p->p_vkDestroyDevice(p->device, NULL);
    }
    if (p->instance && p->p_vkDestroyInstance)
        p->p_vkDestroyInstance(p->instance, NULL);
    vk_loader_close(&p->loader);
    pthread_mutex_unlock(&p->lock);
    pthread_mutex_destroy(&p->lock);
    free(p);
}
