#ifndef LINUX_DESKTOP_WL_COMPOSITOR_SERVER_H
#define LINUX_DESKTOP_WL_COMPOSITOR_SERVER_H

#include "vk_presenter.h"
#include <stdbool.h>
#include <stdint.h>

typedef struct WlCompositorServer WlCompositorServer;

WlCompositorServer *wl_server_start(const char *runtime_dir, VkPresenter *presenter, uint32_t output_w,
                                    uint32_t output_h);
void wl_server_set_output_size(WlCompositorServer *srv, uint32_t output_w, uint32_t output_h);
void wl_server_get_surface_size(WlCompositorServer *srv, uint32_t *w, uint32_t *h, uint32_t *scale);
void wl_server_send_touch(WlCompositorServer *srv, int action, int id, float x, float y);
void wl_server_send_key(WlCompositorServer *srv, uint32_t evdev_key, bool pressed, uint32_t mods_depressed);
const char *wl_server_get_socket_path(WlCompositorServer *srv);
void wl_server_stop(WlCompositorServer *srv);

#endif
