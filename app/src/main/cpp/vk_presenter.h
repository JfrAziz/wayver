#ifndef LINUX_DESKTOP_VK_PRESENTER_H
#define LINUX_DESKTOP_VK_PRESENTER_H

#include <android/native_window.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct VkPresenter VkPresenter;

VkPresenter *vk_presenter_create(const char *custom_driver_path);
void vk_presenter_destroy(VkPresenter *p);

bool vk_presenter_set_window(VkPresenter *p, ANativeWindow *window, int width, int height);
bool vk_presenter_draw_clear(VkPresenter *p, float r, float g, float b, float a);
bool vk_presenter_present_pixels(VkPresenter *p, const void *pixels, uint32_t width, uint32_t height,
                                 uint32_t stride_bytes, bool is_bgra);
const char *vk_presenter_get_info(VkPresenter *p);

#endif
