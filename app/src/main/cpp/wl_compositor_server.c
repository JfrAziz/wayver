#include "wl_compositor_server.h"
#include "wayland-server.h"
#include "xdg-shell-server-protocol.h"

#include <android/log.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "LinuxDesktopMVP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct WlCompositorServer {
    struct wl_display *display;
    struct wl_event_loop *loop;
    VkPresenter *presenter;
    pthread_t thread;
    bool running;

    char runtime_dir[256];
    char socket_path[300];
    uint32_t output_w;
    uint32_t output_h;
    uint32_t surface_w;
    uint32_t surface_h;
    uint32_t buffer_scale;

    struct wl_resource *output_resource;
    struct wl_resource *active_surface;
    int wakeup_fd[2];
    pthread_mutex_t input_lock;
    bool pending_reconfigure;
    struct {
        int type; /* 1=touch, 2=key */
        int action;
        int id;
        float x;
        float y;
        uint32_t evdev_key;
        bool pressed;
        uint32_t mods;
    } input_queue[128];
    int input_head;
    int input_tail;
    struct wl_resource *touch_resource;
    struct wl_resource *pointer_resource;
    struct wl_resource *keyboard_resource;
    bool pointer_entered;
    bool keyboard_focused;
    uint32_t last_mods;
};

typedef struct SurfaceState {
    struct wl_resource *resource;
    struct wl_resource *pending_buffer;
    struct wl_resource *frame_cb;
    struct wl_resource *xdg_surface;
    struct wl_resource *xdg_toplevel;
    WlCompositorServer *srv;
    bool is_subsurface;
    bool is_toplevel;
    bool configured;
    bool reconfigured;
    bool output_entered;
    uint32_t buffer_scale;
} SurfaceState;

static uint32_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}

/* ---- wl_region ---- */
static void region_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void region_add(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}
static void region_sub(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}
static const struct wl_region_interface region_iface = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_sub,
};

/* ---- wl_surface ---- */
static void surface_destroy_res(struct wl_resource *r) {
    SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(r);
    if (st) {
        if (st->srv && st->srv->active_surface == r) {
            st->srv->active_surface = NULL;
        }
        free(st);
    }
}

static void surface_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void surface_attach(struct wl_client *c, struct wl_resource *r, struct wl_resource *buf, int32_t x, int32_t y) {
    (void)c;
    (void)x;
    (void)y;
    SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(r);
    if (st)
        st->pending_buffer = buf;
}
static void surface_damage(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}
static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t callback) {
    SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(r);
    struct wl_resource *cb = wl_resource_create(c, &wl_callback_interface, 1, callback);
    if (st && cb)
        st->frame_cb = cb;
}
static void surface_set_opaque(struct wl_client *c, struct wl_resource *r, struct wl_resource *reg) {
    (void)c;
    (void)r;
    (void)reg;
}
static void surface_set_input(struct wl_client *c, struct wl_resource *r, struct wl_resource *reg) {
    (void)c;
    (void)r;
    (void)reg;
}
static void send_toplevel_configure(WlCompositorServer *srv, SurfaceState *st) {
    if (!srv || !st || !st->xdg_surface || !st->xdg_toplevel)
        return;
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *s1 = (uint32_t *)wl_array_add(&states, sizeof(uint32_t));
    if (s1)
        *s1 = XDG_TOPLEVEL_STATE_MAXIMIZED;
    uint32_t *s2 = (uint32_t *)wl_array_add(&states, sizeof(uint32_t));
    if (s2)
        *s2 = XDG_TOPLEVEL_STATE_ACTIVATED;
    int32_t w = (int32_t)(srv->output_w ? srv->output_w : 1280);
    int32_t h = (int32_t)(srv->output_h ? srv->output_h : 800);
    if (w < 320)
        w = 320;
    if (h < 240)
        h = 240;
    xdg_toplevel_send_configure(st->xdg_toplevel, w, h, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(st->xdg_surface, wl_display_next_serial(srv->display));
    st->configured = true;
}

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(r);
    if (!st || !st->srv)
        return;

    if (st->is_toplevel) {
        st->srv->active_surface = r;
        if (!st->output_entered && st->srv->output_resource &&
            wl_resource_get_client(st->srv->output_resource) == wl_resource_get_client(r)) {
            wl_surface_send_enter(r, st->srv->output_resource);
            st->output_entered = true;
        }
        if (!st->pending_buffer && !st->configured) {
            send_toplevel_configure(st->srv, st);
        }
        if (st->srv->keyboard_resource && !st->srv->keyboard_focused) {
            struct wl_array empty_keys;
            wl_array_init(&empty_keys);
            wl_keyboard_send_enter(st->srv->keyboard_resource, wl_display_next_serial(st->srv->display), r,
                                   &empty_keys);
            wl_array_release(&empty_keys);
            st->srv->keyboard_focused = true;
        }
    }

    if (st->pending_buffer) {
        if (!st->is_subsurface && (st->is_toplevel || !st->srv->active_surface)) {
            struct wl_shm_buffer *shm = wl_shm_buffer_get(st->pending_buffer);
            if (shm) {
                wl_shm_buffer_begin_access(shm);
                void *data = wl_shm_buffer_get_data(shm);
                int32_t w = wl_shm_buffer_get_width(shm);
                int32_t h = wl_shm_buffer_get_height(shm);
                int32_t stride = wl_shm_buffer_get_stride(shm);
                uint32_t fmt = wl_shm_buffer_get_format(shm);
                bool is_bgra = (fmt == WL_SHM_FORMAT_ARGB8888 || fmt == WL_SHM_FORMAT_XRGB8888);
                if (data && w > 0 && h > 0) {
                    uint32_t bscale = st->buffer_scale > 0 ? st->buffer_scale : 1;
                    if (st->srv->surface_w != (uint32_t)w || st->srv->surface_h != (uint32_t)h ||
                        st->srv->buffer_scale != bscale) {
                        st->srv->surface_w = (uint32_t)w;
                        st->srv->surface_h = (uint32_t)h;
                        st->srv->buffer_scale = bscale;
                        LOGI("Client buffer size: %dx%d (scale=%u, output %ux%u)", w, h, bscale, st->srv->output_w,
                             st->srv->output_h);
                    }
                    vk_presenter_present_pixels(st->srv->presenter, data, (uint32_t)w, (uint32_t)h, (uint32_t)stride,
                                                is_bgra);
                    int32_t expected_w = (int32_t)st->srv->output_w;
                    int32_t expected_h = (int32_t)st->srv->output_h;
                    if (st->is_toplevel && !st->reconfigured && expected_w > 0 && expected_h > 0 &&
                        (w != expected_w || h != expected_h)) {
                        st->reconfigured = true;
                        send_toplevel_configure(st->srv, st);
                    }
                }
                wl_shm_buffer_end_access(shm);
            }
        }
        wl_buffer_send_release(st->pending_buffer);
        st->pending_buffer = NULL;
    }

    if (st->frame_cb) {
        wl_callback_send_done(st->frame_cb, now_ms());
        wl_resource_destroy(st->frame_cb);
        st->frame_cb = NULL;
    }
}
static void surface_set_transform(struct wl_client *c, struct wl_resource *r, int32_t t) {
    (void)c;
    (void)r;
    (void)t;
}
static void surface_set_scale(struct wl_client *c, struct wl_resource *r, int32_t s) {
    (void)c;
    SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(r);
    if (st)
        st->buffer_scale = s > 0 ? (uint32_t)s : 1;
}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w,
                                  int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}

static const struct wl_surface_interface surface_iface = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_opaque,
    .set_input_region = surface_set_input,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_transform,
    .set_buffer_scale = surface_set_scale,
    .damage_buffer = surface_damage_buffer,
};

/* ---- wl_compositor ---- */
static void comp_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    struct wl_resource *sr = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    if (!sr) {
        wl_client_post_no_memory(c);
        return;
    }
    SurfaceState *st = (SurfaceState *)calloc(1, sizeof(SurfaceState));
    st->resource = sr;
    st->srv = srv;
    wl_resource_set_implementation(sr, &surface_iface, st, surface_destroy_res);
}

static void comp_create_region(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *rr = wl_resource_create(c, &wl_region_interface, wl_resource_get_version(r), id);
    if (!rr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(rr, &region_iface, NULL, NULL);
}

static const struct wl_compositor_interface comp_iface = {
    .create_surface = comp_create_surface,
    .create_region = comp_create_region,
};

static void bind_compositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface, ver < 4 ? ver : 4, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &comp_iface, data, NULL);
}

/* ---- wl_subcompositor ---- */
static void subsurf_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void subsurf_set_pos(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
}
static void subsurf_place(struct wl_client *c, struct wl_resource *r, struct wl_resource *s) {
    (void)c;
    (void)r;
    (void)s;
}
static void subsurf_noop(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    (void)r;
}

static const struct wl_subsurface_interface subsurf_iface = {
    .destroy = subsurf_destroy,
    .set_position = subsurf_set_pos,
    .place_above = subsurf_place,
    .place_below = subsurf_place,
    .set_sync = subsurf_noop,
    .set_desync = subsurf_noop,
};

static void subcomp_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void subcomp_get_subsurface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surf,
                                   struct wl_resource *parent) {
    (void)parent;
    SurfaceState *st = surf ? (SurfaceState *)wl_resource_get_user_data(surf) : NULL;
    if (st)
        st->is_subsurface = true;
    struct wl_resource *sr = wl_resource_create(c, &wl_subsurface_interface, wl_resource_get_version(r), id);
    if (!sr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(sr, &subsurf_iface, NULL, NULL);
}

static const struct wl_subcompositor_interface subcomp_iface = {
    .destroy = subcomp_destroy,
    .get_subsurface = subcomp_get_subsurface,
};

static void bind_subcompositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_subcompositor_interface, ver < 1 ? ver : 1, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &subcomp_iface, data, NULL);
}

/* ---- xdg_shell ---- */
static void xdg_toplevel_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void xdg_toplevel_noop_res(struct wl_client *c, struct wl_resource *r, struct wl_resource *p) {
    (void)c;
    (void)r;
    (void)p;
}
static void xdg_toplevel_noop_str(struct wl_client *c, struct wl_resource *r, const char *s) {
    (void)c;
    (void)r;
    (void)s;
}
static void xdg_toplevel_show_menu(struct wl_client *c, struct wl_resource *r, struct wl_resource *s, uint32_t ser,
                                   int32_t x, int32_t y) {
    (void)c;
    (void)r;
    (void)s;
    (void)ser;
    (void)x;
    (void)y;
}
static void xdg_toplevel_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *s, uint32_t ser) {
    (void)c;
    (void)r;
    (void)s;
    (void)ser;
}
static void xdg_toplevel_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *s, uint32_t ser,
                                uint32_t edges) {
    (void)c;
    (void)r;
    (void)s;
    (void)ser;
    (void)edges;
}
static void xdg_toplevel_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    (void)c;
    (void)r;
    (void)w;
    (void)h;
}
static void xdg_toplevel_noop(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    (void)r;
}
static void xdg_toplevel_fs(struct wl_client *c, struct wl_resource *r, struct wl_resource *o) {
    (void)c;
    (void)r;
    (void)o;
}

static const struct xdg_toplevel_interface xdg_toplevel_iface = {
    .destroy = xdg_toplevel_destroy,
    .set_parent = xdg_toplevel_noop_res,
    .set_title = xdg_toplevel_noop_str,
    .set_app_id = xdg_toplevel_noop_str,
    .show_window_menu = xdg_toplevel_show_menu,
    .move = xdg_toplevel_move,
    .resize = xdg_toplevel_resize,
    .set_max_size = xdg_toplevel_size,
    .set_min_size = xdg_toplevel_size,
    .set_maximized = xdg_toplevel_noop,
    .unset_maximized = xdg_toplevel_noop,
    .set_fullscreen = xdg_toplevel_fs,
    .unset_fullscreen = xdg_toplevel_noop,
    .set_minimized = xdg_toplevel_noop,
};

typedef struct XdgSurfData {
    WlCompositorServer *srv;
    struct wl_resource *xdg_surface;
    struct wl_resource *wl_surface;
} XdgSurfData;

static void xdg_surface_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void xdg_surface_free(struct wl_resource *r) {
    free(wl_resource_get_user_data(r));
}
static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    XdgSurfData *xs = (XdgSurfData *)wl_resource_get_user_data(r);
    struct wl_resource *tr = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id);
    if (!tr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(tr, &xdg_toplevel_iface, xs ? xs->srv : NULL, NULL);

    if (xs && xs->wl_surface) {
        SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(xs->wl_surface);
        if (st) {
            st->is_toplevel = true;
            st->xdg_surface = r;
            st->xdg_toplevel = tr;
            send_toplevel_configure(xs->srv, st);
        }
        if (xs->srv) {
            xs->srv->active_surface = xs->wl_surface;
            xs->srv->pointer_entered = false;
            xs->srv->keyboard_focused = false;
        }
    } else if (xs && xs->srv) {
        struct wl_array states;
        wl_array_init(&states);
        uint32_t *s = (uint32_t *)wl_array_add(&states, sizeof(uint32_t));
        if (s)
            *s = XDG_TOPLEVEL_STATE_MAXIMIZED;
        int32_t w = xs->srv->output_w ? (int32_t)xs->srv->output_w : 1280;
        int32_t h = xs->srv->output_h ? (int32_t)xs->srv->output_h : 800;
        xdg_toplevel_send_configure(tr, w, h, &states);
        wl_array_release(&states);
        xdg_surface_send_configure(r, wl_display_next_serial(xs->srv->display));
    }
}
static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *p,
                                  struct wl_resource *pos) {
    (void)c;
    (void)r;
    (void)id;
    (void)p;
    (void)pos;
}
static void xdg_surface_set_geom(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w,
                                 int32_t h) {
    (void)c;
    (void)r;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}
static void xdg_surface_ack(struct wl_client *c, struct wl_resource *r, uint32_t serial) {
    (void)c;
    (void)r;
    (void)serial;
}

static const struct xdg_surface_interface xdg_surface_iface = {
    .destroy = xdg_surface_destroy,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_geom,
    .ack_configure = xdg_surface_ack,
};

static void xdg_wm_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void xdg_wm_create_pos(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    (void)c;
    (void)r;
    (void)id;
}
static void xdg_wm_get_surf(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surf) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    struct wl_resource *xr = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    if (!xr) {
        wl_client_post_no_memory(c);
        return;
    }
    XdgSurfData *xs = (XdgSurfData *)calloc(1, sizeof(XdgSurfData));
    xs->srv = srv;
    xs->xdg_surface = xr;
    xs->wl_surface = surf;
    wl_resource_set_implementation(xr, &xdg_surface_iface, xs, xdg_surface_free);
}
static void xdg_wm_pong(struct wl_client *c, struct wl_resource *r, uint32_t ser) {
    (void)c;
    (void)r;
    (void)ser;
}

static const struct xdg_wm_base_interface xdg_wm_iface = {
    .destroy = xdg_wm_destroy,
    .create_positioner = xdg_wm_create_pos,
    .get_xdg_surface = xdg_wm_get_surf,
    .pong = xdg_wm_pong,
};

static void bind_xdg_wm(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface, ver < 2 ? ver : 2, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &xdg_wm_iface, data, NULL);
}

/* ---- wl_output ---- */
static void output_destroy_res(struct wl_resource *r) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    if (srv && srv->output_resource == r) {
        srv->output_resource = NULL;
    }
}

static void bind_output(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    WlCompositorServer *srv = (WlCompositorServer *)data;
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, ver < 2 ? ver : 2, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, NULL, srv, output_destroy_res);
    srv->output_resource = r;
    int32_t w = srv->output_w ? (int32_t)srv->output_w : 1280;
    int32_t h = srv->output_h ? (int32_t)srv->output_h : 800;
    wl_output_send_geometry(r, 0, 0, w / 8, h / 8, WL_OUTPUT_SUBPIXEL_UNKNOWN, "LinuxDesktop", "AndroidSurface",
                            WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, w, h, 60000);
    if (ver >= 2) {
        wl_output_send_scale(r, 1);
        wl_output_send_done(r);
    }
}

/* ---- wl_seat ---- */
static void touch_release(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static const struct wl_touch_interface touch_iface = {.release = touch_release};

static void ptr_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t ser, struct wl_resource *surf,
                           int32_t hx, int32_t hy) {
    (void)c;
    (void)r;
    (void)ser;
    (void)surf;
    (void)hx;
    (void)hy;
}
static void ptr_release(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static const struct wl_pointer_interface ptr_iface = {
    .set_cursor = ptr_set_cursor,
    .release = ptr_release,
};

static void ptr_destroy_res(struct wl_resource *r) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    if (srv && srv->pointer_resource == r) {
        srv->pointer_resource = NULL;
        srv->pointer_entered = false;
    }
}

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    struct wl_resource *pr = wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
    if (!pr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(pr, &ptr_iface, srv, ptr_destroy_res);
    srv->pointer_resource = pr;
    srv->pointer_entered = false;
}
static const char *MINIMAL_XKB_KEYMAP =
    "xkb_keymap {\n"
    "  xkb_keycodes \"evdev\" {\n"
    "    minimum = 8; maximum = 255;\n"
    "    <ESC> = 9; <AE01> = 10; <AE02> = 11; <AE03> = 12; <AE04> = 13; <AE05> = 14;\n"
    "    <AE06> = 15; <AE07> = 16; <AE08> = 17; <AE09> = 18; <AE10> = 19; <AE11> = 20;\n"
    "    <AE12> = 21; <BKSP> = 22; <TAB> = 23;\n"
    "    <AD01> = 24; <AD02> = 25; <AD03> = 26; <AD04> = 27; <AD05> = 28; <AD06> = 29;\n"
    "    <AD07> = 30; <AD08> = 31; <AD09> = 32; <AD10> = 33; <AD11> = 34; <AD12> = 35;\n"
    "    <RTRN> = 36; <LCTL> = 37;\n"
    "    <AC01> = 38; <AC02> = 39; <AC03> = 40; <AC04> = 41; <AC05> = 42; <AC06> = 43;\n"
    "    <AC07> = 44; <AC08> = 45; <AC09> = 46; <AC10> = 47; <AC11> = 48; <TLDE> = 49;\n"
    "    <LFSH> = 50; <BKSL> = 51;\n"
    "    <AB01> = 52; <AB02> = 53; <AB03> = 54; <AB04> = 55; <AB05> = 56; <AB06> = 57;\n"
    "    <AB07> = 58; <AB08> = 59; <AB09> = 60; <AB10> = 61; <RTSH> = 62;\n"
    "    <LALT> = 64; <SPCE> = 65;\n"
    "    <UP> = 111; <LEFT> = 113; <RGHT> = 114; <DOWN> = 116;\n"
    "  };\n"
    "  xkb_types \"complete\" {\n"
    "    virtual_modifiers NumLock,Alt;\n"
    "    type \"ONE_LEVEL\" { modifiers = none; map[none] = Level1; level_name[Level1] = \"Any\"; };\n"
    "    type \"TWO_LEVEL\" { modifiers = Shift; map[Shift] = Level2; level_name[Level1] = \"Base\"; "
    "level_name[Level2] = \"Shift\"; };\n"
    "    type \"ALPHABETIC\" { modifiers = Shift; map[Shift] = Level2; level_name[Level1] = \"Base\"; "
    "level_name[Level2] = \"Caps\"; };\n"
    "  };\n"
    "  xkb_compatibility \"complete\" {\n"
    "    interpret Shift_L { action = SetMods(modifiers=Shift); };\n"
    "    interpret Shift_R { action = SetMods(modifiers=Shift); };\n"
    "    interpret Control_L { action = SetMods(modifiers=Control); };\n"
    "    interpret Alt_L { action = SetMods(modifiers=Mod1); };\n"
    "  };\n"
    "  xkb_symbols \"pc+us\" {\n"
    "    key <ESC>  { [ Escape ] };\n"
    "    key <AE01> { [ 1, exclam ] }; key <AE02> { [ 2, at ] }; key <AE03> { [ 3, numbersign ] };\n"
    "    key <AE04> { [ 4, dollar ] }; key <AE05> { [ 5, percent ] }; key <AE06> { [ 6, asciicircum ] };\n"
    "    key <AE07> { [ 7, ampersand ] }; key <AE08> { [ 8, asterisk ] }; key <AE09> { [ 9, parenleft ] };\n"
    "    key <AE10> { [ 0, parenright ] }; key <AE11> { [ minus, underscore ] }; key <AE12> { [ equal, plus ] };\n"
    "    key <BKSP> { [ BackSpace ] }; key <TAB> { [ Tab ] };\n"
    "    key <AD01> { [ q, Q ] }; key <AD02> { [ w, W ] }; key <AD03> { [ e, E ] }; key <AD04> { [ r, R ] };\n"
    "    key <AD05> { [ t, T ] }; key <AD06> { [ y, Y ] }; key <AD07> { [ u, U ] }; key <AD08> { [ i, I ] };\n"
    "    key <AD09> { [ o, O ] }; key <AD10> { [ p, P ] }; key <AD11> { [ bracketleft, braceleft ] }; key <AD12> { [ "
    "bracketright, braceright ] };\n"
    "    key <RTRN> { [ Return ] }; key <LCTL> { [ Control_L ] };\n"
    "    key <AC01> { [ a, A ] }; key <AC02> { [ s, S ] }; key <AC03> { [ d, D ] }; key <AC04> { [ f, F ] };\n"
    "    key <AC05> { [ g, G ] }; key <AC06> { [ h, H ] }; key <AC07> { [ j, J ] }; key <AC08> { [ k, K ] };\n"
    "    key <AC09> { [ l, L ] }; key <AC10> { [ semicolon, colon ] }; key <AC11> { [ apostrophe, quotedbl ] }; key "
    "<TLDE> { [ grave, asciitilde ] };\n"
    "    key <LFSH> { [ Shift_L ] }; key <BKSL> { [ backslash, bar ] };\n"
    "    key <AB01> { [ z, Z ] }; key <AB02> { [ x, X ] }; key <AB03> { [ c, C ] }; key <AB04> { [ v, V ] };\n"
    "    key <AB05> { [ b, B ] }; key <AB06> { [ n, N ] }; key <AB07> { [ m, M ] };\n"
    "    key <AB08> { [ comma, less ] }; key <AB09> { [ period, greater ] }; key <AB10> { [ slash, question ] }; key "
    "<RTSH> { [ Shift_R ] };\n"
    "    key <LALT> { [ Alt_L ] }; key <SPCE> { [ space ] };\n"
    "    key <UP> { [ Up ] }; key <LEFT> { [ Left ] }; key <RGHT> { [ Right ] }; key <DOWN> { [ Down ] };\n"
    "    modifier_map Shift { <LFSH>, <RTSH> };\n"
    "    modifier_map Control { <LCTL> };\n"
    "    modifier_map Mod1 { <LALT> };\n"
    "  };\n"
    "};\n";

static void kbd_release(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static const struct wl_keyboard_interface kbd_iface = {.release = kbd_release};

static void kbd_destroy_res(struct wl_resource *r) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    if (srv && srv->keyboard_resource == r) {
        srv->keyboard_resource = NULL;
        srv->keyboard_focused = false;
    }
}

static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    uint32_t ver = wl_resource_get_version(r);
    struct wl_resource *kr = wl_resource_create(c, &wl_keyboard_interface, ver, id);
    if (!kr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(kr, &kbd_iface, srv, kbd_destroy_res);
    srv->keyboard_resource = kr;
    srv->keyboard_focused = false;

    char kpath[300];
    snprintf(kpath, sizeof(kpath), "%s/keymap.xkb", srv->runtime_dir);
    int fd = open(kpath, O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        unlink(kpath);
        size_t len = strlen(MINIMAL_XKB_KEYMAP) + 1;
        if (write(fd, MINIMAL_XKB_KEYMAP, len) == (ssize_t)len) {
            lseek(fd, 0, SEEK_SET);
            wl_keyboard_send_keymap(kr, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t)len);
        }
        close(fd);
    }
    if (ver >= 4) {
        wl_keyboard_send_repeat_info(kr, 25, 400);
    }
    if (srv->active_surface) {
        struct wl_array empty_keys;
        wl_array_init(&empty_keys);
        wl_keyboard_send_enter(kr, wl_display_next_serial(srv->display), srv->active_surface, &empty_keys);
        wl_array_release(&empty_keys);
        srv->keyboard_focused = true;
    }
}
static void touch_destroy_res(struct wl_resource *r) {
    WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
    if (srv && srv->touch_resource == r) {
        srv->touch_resource = NULL;
    }
}

static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *tr = wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id);
    if (tr) {
        WlCompositorServer *srv = (WlCompositorServer *)wl_resource_get_user_data(r);
        wl_resource_set_implementation(tr, &touch_iface, srv, touch_destroy_res);
        srv->touch_resource = tr;
    }
}
static void seat_release(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}

static const struct wl_seat_interface seat_iface = {
    .get_pointer = seat_get_pointer,
    .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch,
    .release = seat_release,
};

static void bind_seat(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, ver < 5 ? ver : 5, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &seat_iface, data, NULL);
    wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_TOUCH | WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD);
    if (ver >= 2)
        wl_seat_send_name(r, "seat0");
}

/* ---- wl_data_device_manager ---- */
static void data_src_offer(struct wl_client *c, struct wl_resource *r, const char *mime) {
    (void)c;
    (void)r;
    (void)mime;
}
static void data_src_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static void data_src_set_actions(struct wl_client *c, struct wl_resource *r, uint32_t actions) {
    (void)c;
    (void)r;
    (void)actions;
}
static const struct wl_data_source_interface data_src_iface = {
    .offer = data_src_offer,
    .destroy = data_src_destroy,
    .set_actions = data_src_set_actions,
};

static void data_dev_start_drag(struct wl_client *c, struct wl_resource *r, struct wl_resource *src,
                                struct wl_resource *orig, struct wl_resource *icon, uint32_t ser) {
    (void)c;
    (void)r;
    (void)src;
    (void)orig;
    (void)icon;
    (void)ser;
}
static void data_dev_set_sel(struct wl_client *c, struct wl_resource *r, struct wl_resource *src, uint32_t ser) {
    (void)c;
    (void)r;
    (void)src;
    (void)ser;
}
static void data_dev_release(struct wl_client *c, struct wl_resource *r) {
    (void)c;
    wl_resource_destroy(r);
}
static const struct wl_data_device_interface data_dev_iface = {
    .start_drag = data_dev_start_drag,
    .set_selection = data_dev_set_sel,
    .release = data_dev_release,
};

static void ddm_create_src(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *sr = wl_resource_create(c, &wl_data_source_interface, wl_resource_get_version(r), id);
    if (!sr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(sr, &data_src_iface, NULL, NULL);
}
static void ddm_get_dev(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    (void)seat;
    struct wl_resource *dr = wl_resource_create(c, &wl_data_device_interface, wl_resource_get_version(r), id);
    if (!dr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(dr, &data_dev_iface, NULL, NULL);
}
static const struct wl_data_device_manager_interface ddm_iface = {
    .create_data_source = ddm_create_src,
    .get_data_device = ddm_get_dev,
};
static void bind_data_device_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_data_device_manager_interface, ver < 3 ? ver : 3, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &ddm_iface, data, NULL);
}

static void dispatch_touch_on_loop(WlCompositorServer *srv, int action, int id, float x, float y) {
    if (!srv || !srv->active_surface)
        return;
    uint32_t t = now_ms();
    double sx = (double)x;
    double sy = (double)y;
    if (srv->output_w > 0 && srv->output_h > 0 && srv->surface_w > 0 && srv->surface_h > 0) {
        uint32_t bscale = srv->buffer_scale > 0 ? srv->buffer_scale : 1;
        double logical_w = (double)srv->surface_w / (double)bscale;
        double logical_h = (double)srv->surface_h / (double)bscale;
        sx = sx * logical_w / (double)srv->output_w;
        sy = sy * logical_h / (double)srv->output_h;
        if (sx < 0.0)
            sx = 0.0;
        if (sy < 0.0)
            sy = 0.0;
        if (sx >= logical_w)
            sx = logical_w - 1.0;
        if (sy >= logical_h)
            sy = logical_h - 1.0;
    }
    wl_fixed_t fx = wl_fixed_from_double(sx);
    wl_fixed_t fy = wl_fixed_from_double(sy);

    if (srv->pointer_resource && id == 0) {
        if (!srv->pointer_entered) {
            wl_pointer_send_enter(srv->pointer_resource, wl_display_next_serial(srv->display), srv->active_surface, fx,
                                  fy);
            srv->pointer_entered = true;
        }
        wl_pointer_send_motion(srv->pointer_resource, t, fx, fy);
        if (action == 0) {
            wl_pointer_send_button(srv->pointer_resource, wl_display_next_serial(srv->display), t, 0x110 /* BTN_LEFT */,
                                   WL_POINTER_BUTTON_STATE_PRESSED);
        } else if (action == 2) {
            wl_pointer_send_button(srv->pointer_resource, wl_display_next_serial(srv->display), t, 0x110 /* BTN_LEFT */,
                                   WL_POINTER_BUTTON_STATE_RELEASED);
        }
        wl_pointer_send_frame(srv->pointer_resource);
    } else if (srv->touch_resource) {
        if (action == 0) {
            uint32_t serial = wl_display_next_serial(srv->display);
            wl_touch_send_down(srv->touch_resource, serial, t, srv->active_surface, id, fx, fy);
            wl_touch_send_frame(srv->touch_resource);
        } else if (action == 1) {
            wl_touch_send_motion(srv->touch_resource, t, id, fx, fy);
            wl_touch_send_frame(srv->touch_resource);
        } else if (action == 2) {
            uint32_t serial = wl_display_next_serial(srv->display);
            wl_touch_send_up(srv->touch_resource, serial, t, id);
            wl_touch_send_frame(srv->touch_resource);
        }
    }
}

static void dispatch_key_on_loop(WlCompositorServer *srv, uint32_t evdev_key, bool pressed, uint32_t mods_depressed) {
    if (!srv || !srv->keyboard_resource || !srv->active_surface)
        return;
    if (!srv->keyboard_focused) {
        struct wl_array empty_keys;
        wl_array_init(&empty_keys);
        wl_keyboard_send_enter(srv->keyboard_resource, wl_display_next_serial(srv->display), srv->active_surface,
                               &empty_keys);
        wl_array_release(&empty_keys);
        srv->keyboard_focused = true;
    }
    if (mods_depressed != srv->last_mods) {
        wl_keyboard_send_modifiers(srv->keyboard_resource, wl_display_next_serial(srv->display), mods_depressed, 0, 0,
                                   0);
        srv->last_mods = mods_depressed;
    }
    wl_keyboard_send_key(srv->keyboard_resource, wl_display_next_serial(srv->display), now_ms(), evdev_key,
                         pressed ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
}

static int on_wakeup_reconfigure(int fd, uint32_t mask, void *data) {
    (void)mask;
    WlCompositorServer *srv = (WlCompositorServer *)data;
    char buf[64];
    while (read(fd, buf, sizeof(buf)) > 0) {
    }
    if (!srv)
        return 0;

    bool do_reconfigure = false;
    pthread_mutex_lock(&srv->input_lock);
    do_reconfigure = srv->pending_reconfigure;
    srv->pending_reconfigure = false;
    while (srv->input_head != srv->input_tail) {
        int idx = srv->input_head;
        srv->input_head = (srv->input_head + 1) % 128;
        int type = srv->input_queue[idx].type;
        int action = srv->input_queue[idx].action;
        int id = srv->input_queue[idx].id;
        float x = srv->input_queue[idx].x;
        float y = srv->input_queue[idx].y;
        uint32_t evdev_key = srv->input_queue[idx].evdev_key;
        bool pressed = srv->input_queue[idx].pressed;
        uint32_t mods = srv->input_queue[idx].mods;
        pthread_mutex_unlock(&srv->input_lock);

        if (type == 1) {
            dispatch_touch_on_loop(srv, action, id, x, y);
        } else if (type == 2) {
            dispatch_key_on_loop(srv, evdev_key, pressed, mods);
        }

        pthread_mutex_lock(&srv->input_lock);
    }
    pthread_mutex_unlock(&srv->input_lock);

    if (do_reconfigure && srv->active_surface) {
        SurfaceState *st = (SurfaceState *)wl_resource_get_user_data(srv->active_surface);
        if (srv->output_resource) {
            int32_t w = srv->output_w ? (int32_t)srv->output_w : 1280;
            int32_t h = srv->output_h ? (int32_t)srv->output_h : 800;
            wl_output_send_geometry(srv->output_resource, 0, 0, w / 8, h / 8, WL_OUTPUT_SUBPIXEL_UNKNOWN,
                                    "LinuxDesktop", "AndroidSurface", WL_OUTPUT_TRANSFORM_NORMAL);
            wl_output_send_mode(srv->output_resource, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, w, h, 60000);
            if (wl_resource_get_version(srv->output_resource) >= 2) {
                wl_output_send_scale(srv->output_resource, 1);
                wl_output_send_done(srv->output_resource);
            }
        }
        if (st && st->is_toplevel) {
            send_toplevel_configure(srv, st);
        }
    }
    wl_display_flush_clients(srv->display);
    return 0;
}

static void *server_thread(void *arg) {
    WlCompositorServer *srv = (WlCompositorServer *)arg;
    LOGI("Wayland event loop started on %s", srv->socket_path);
    wl_display_run(srv->display);
    LOGI("Wayland event loop exited");
    return NULL;
}

WlCompositorServer *wl_server_start(const char *runtime_dir, VkPresenter *presenter, uint32_t output_w,
                                    uint32_t output_h) {
    if (!runtime_dir || !presenter)
        return NULL;

    WlCompositorServer *srv = (WlCompositorServer *)calloc(1, sizeof(WlCompositorServer));
    if (!srv)
        return NULL;
    srv->presenter = presenter;
    srv->output_w = output_w;
    srv->output_h = output_h;
    srv->buffer_scale = 1;
    pthread_mutex_init(&srv->input_lock, NULL);
    snprintf(srv->runtime_dir, sizeof(srv->runtime_dir), "%s", runtime_dir);
    snprintf(srv->socket_path, sizeof(srv->socket_path), "%s/wayland-0", runtime_dir);

    unlink(srv->socket_path);
    setenv("XDG_RUNTIME_DIR", srv->runtime_dir, 1);

    srv->display = wl_display_create();
    if (!srv->display) {
        LOGE("wl_display_create failed");
        free(srv);
        return NULL;
    }

    wl_display_init_shm(srv->display);
    wl_display_add_shm_format(srv->display, WL_SHM_FORMAT_ARGB8888);
    wl_display_add_shm_format(srv->display, WL_SHM_FORMAT_XRGB8888);

    wl_global_create(srv->display, &wl_compositor_interface, 4, srv, bind_compositor);
    wl_global_create(srv->display, &wl_subcompositor_interface, 1, srv, bind_subcompositor);
    wl_global_create(srv->display, &xdg_wm_base_interface, 2, srv, bind_xdg_wm);
    wl_global_create(srv->display, &wl_output_interface, 2, srv, bind_output);
    wl_global_create(srv->display, &wl_seat_interface, 5, srv, bind_seat);
    wl_global_create(srv->display, &wl_data_device_manager_interface, 3, srv, bind_data_device_manager);

    if (wl_display_add_socket(srv->display, "wayland-0") != 0) {
        LOGE("wl_display_add_socket failed at %s", srv->socket_path);
        wl_display_destroy(srv->display);
        free(srv);
        return NULL;
    }

    srv->loop = wl_display_get_event_loop(srv->display);
    srv->wakeup_fd[0] = -1;
    srv->wakeup_fd[1] = -1;
    if (pipe(srv->wakeup_fd) == 0) {
        fcntl(srv->wakeup_fd[0], F_SETFL, O_NONBLOCK);
        fcntl(srv->wakeup_fd[1], F_SETFL, O_NONBLOCK);
        wl_event_loop_add_fd(srv->loop, srv->wakeup_fd[0], WL_EVENT_READABLE, on_wakeup_reconfigure, srv);
    }
    srv->running = true;
    pthread_create(&srv->thread, NULL, server_thread, srv);
    return srv;
}

void wl_server_set_output_size(WlCompositorServer *srv, uint32_t output_w, uint32_t output_h) {
    if (!srv)
        return;
    pthread_mutex_lock(&srv->input_lock);
    srv->output_w = output_w;
    srv->output_h = output_h;
    srv->pending_reconfigure = true;
    pthread_mutex_unlock(&srv->input_lock);
    if (srv->wakeup_fd[1] >= 0) {
        char b = 1;
        (void)write(srv->wakeup_fd[1], &b, 1);
    }
}

void wl_server_get_surface_size(WlCompositorServer *srv, uint32_t *w, uint32_t *h, uint32_t *scale) {
    if (!srv) {
        if (w)
            *w = 0;
        if (h)
            *h = 0;
        if (scale)
            *scale = 1;
        return;
    }
    if (w)
        *w = srv->surface_w;
    if (h)
        *h = srv->surface_h;
    if (scale)
        *scale = srv->buffer_scale > 0 ? srv->buffer_scale : 1;
}

void wl_server_send_touch(WlCompositorServer *srv, int action, int id, float x, float y) {
    if (!srv)
        return;
    pthread_mutex_lock(&srv->input_lock);
    int next = (srv->input_tail + 1) % 128;
    if (next != srv->input_head) {
        srv->input_queue[srv->input_tail].type = 1;
        srv->input_queue[srv->input_tail].action = action;
        srv->input_queue[srv->input_tail].id = id;
        srv->input_queue[srv->input_tail].x = x;
        srv->input_queue[srv->input_tail].y = y;
        srv->input_tail = next;
    }
    pthread_mutex_unlock(&srv->input_lock);
    if (srv->wakeup_fd[1] >= 0) {
        char b = 1;
        (void)write(srv->wakeup_fd[1], &b, 1);
    }
}

void wl_server_send_key(WlCompositorServer *srv, uint32_t evdev_key, bool pressed, uint32_t mods_depressed) {
    if (!srv)
        return;
    pthread_mutex_lock(&srv->input_lock);
    int next = (srv->input_tail + 1) % 128;
    if (next != srv->input_head) {
        srv->input_queue[srv->input_tail].type = 2;
        srv->input_queue[srv->input_tail].evdev_key = evdev_key;
        srv->input_queue[srv->input_tail].pressed = pressed;
        srv->input_queue[srv->input_tail].mods = mods_depressed;
        srv->input_tail = next;
    }
    pthread_mutex_unlock(&srv->input_lock);
    if (srv->wakeup_fd[1] >= 0) {
        char b = 1;
        (void)write(srv->wakeup_fd[1], &b, 1);
    }
}

const char *wl_server_get_socket_path(WlCompositorServer *srv) {
    return srv ? srv->socket_path : "";
}

void wl_server_stop(WlCompositorServer *srv) {
    if (!srv)
        return;
    if (srv->running && srv->display) {
        wl_display_terminate(srv->display);
        pthread_join(srv->thread, NULL);
        srv->running = false;
    }
    if (srv->display) {
        wl_display_destroy(srv->display);
        srv->display = NULL;
    }
    if (srv->wakeup_fd[0] >= 0)
        close(srv->wakeup_fd[0]);
    if (srv->wakeup_fd[1] >= 0)
        close(srv->wakeup_fd[1]);
    pthread_mutex_destroy(&srv->input_lock);
    unlink(srv->socket_path);
    free(srv);
}
