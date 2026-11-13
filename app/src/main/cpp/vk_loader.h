#ifndef LINUX_DESKTOP_VK_LOADER_H
#define LINUX_DESKTOP_VK_LOADER_H

#include <vulkan/vulkan.h>
#include <stdbool.h>

typedef struct VkLoader {
    void *handle;
    PFN_vkGetInstanceProcAddr get_instance_proc_addr;
    bool is_custom_driver;
} VkLoader;

bool vk_loader_open(VkLoader *loader, const char *driver_so_path);
void vk_loader_close(VkLoader *loader);

#endif
