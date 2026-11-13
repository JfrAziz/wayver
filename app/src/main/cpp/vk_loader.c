#include "vk_loader.h"
#include <dlfcn.h>
#include <stddef.h>

bool vk_loader_open(VkLoader *loader, const char *driver_so_path) {
    if (!loader)
        return false;
    loader->handle = NULL;
    loader->get_instance_proc_addr = NULL;
    loader->is_custom_driver = false;

    if (driver_so_path && driver_so_path[0] != '\0') {
        loader->handle = dlopen(driver_so_path, RTLD_NOW | RTLD_LOCAL);
        if (loader->handle) {
            loader->get_instance_proc_addr = (PFN_vkGetInstanceProcAddr)dlsym(loader->handle, "vkGetInstanceProcAddr");
            if (!loader->get_instance_proc_addr) {
                loader->get_instance_proc_addr =
                    (PFN_vkGetInstanceProcAddr)dlsym(loader->handle, "vk_icdGetInstanceProcAddr");
            }
            if (loader->get_instance_proc_addr) {
                loader->is_custom_driver = true;
                return true;
            }
            dlclose(loader->handle);
            loader->handle = NULL;
        }
    }

    loader->handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!loader->handle)
        return false;

    loader->get_instance_proc_addr = (PFN_vkGetInstanceProcAddr)dlsym(loader->handle, "vkGetInstanceProcAddr");
    if (!loader->get_instance_proc_addr) {
        dlclose(loader->handle);
        loader->handle = NULL;
        return false;
    }
    return true;
}

void vk_loader_close(VkLoader *loader) {
    if (!loader)
        return;
    if (loader->handle) {
        dlclose(loader->handle);
        loader->handle = NULL;
    }
    loader->get_instance_proc_addr = NULL;
    loader->is_custom_driver = false;
}
