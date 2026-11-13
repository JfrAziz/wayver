#include "wl_selftest.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "LinuxDesktopMVP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int send_msg(int fd, const void *buf, size_t len, int pass_fd) {
    struct msghdr msg = {0};
    struct iovec iov = {.iov_base = (void *)buf, .iov_len = len};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    char cbuf[CMSG_SPACE(sizeof(int))];
    if (pass_fd >= 0) {
        memset(cbuf, 0, sizeof(cbuf));
        msg.msg_control = cbuf;
        msg.msg_controllen = sizeof(cbuf);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &pass_fd, sizeof(int));
    }
    return sendmsg(fd, &msg, 0) == (ssize_t)len ? 0 : -1;
}

static uint32_t pad4(uint32_t n) {
    return (n + 3u) & ~3u;
}

bool wl_selftest_send_frame(const char *runtime_dir, int width, int height) {
    if (!runtime_dir || width <= 0 || height <= 0)
        return false;

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0)
        return false;

    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s/wayland-0", runtime_dir);

    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        LOGE("selftest connect(%s) failed: %s", addr.sun_path, strerror(errno));
        close(sock);
        return false;
    }

    /* 1. wl_display(1)::get_registry(opcode=1) -> new_id=2 */
    uint32_t get_reg[3] = {1, (12u << 16) | 1u, 2};
    send_msg(sock, get_reg, sizeof(get_reg), -1);

    /* 2. wl_display(1)::sync(opcode=0) -> new_id=3 */
    uint32_t sync_msg[3] = {1, (12u << 16) | 0u, 3};
    send_msg(sock, sync_msg, sizeof(sync_msg), -1);

    /* Read globals until wl_callback(3)::done */
    uint32_t comp_name = 0, comp_ver = 0;
    uint32_t shm_name = 0, shm_ver = 0;
    uint8_t rbuf[4096];
    bool synced = false;
    while (!synced) {
        ssize_t n = recv(sock, rbuf, sizeof(rbuf), 0);
        if (n <= 0)
            break;
        ssize_t off = 0;
        while (off + 8 <= n) {
            uint32_t obj = *(uint32_t *)(rbuf + off);
            uint32_t hdr = *(uint32_t *)(rbuf + off + 4);
            uint32_t sz = hdr >> 16;
            uint32_t op = hdr & 0xFFFFu;
            if (sz < 8 || off + (ssize_t)sz > n)
                break;

            if (obj == 2 && op == 0 && sz >= 20) { /* wl_registry::global */
                uint32_t name = *(uint32_t *)(rbuf + off + 8);
                uint32_t slen = *(uint32_t *)(rbuf + off + 12);
                const char *iface = (const char *)(rbuf + off + 16);
                uint32_t ver = *(uint32_t *)(rbuf + off + 16 + pad4(slen));
                if (strcmp(iface, "wl_compositor") == 0) {
                    comp_name = name;
                    comp_ver = ver;
                }
                if (strcmp(iface, "wl_shm") == 0) {
                    shm_name = name;
                    shm_ver = ver;
                }
            } else if (obj == 3 && op == 0) { /* wl_callback::done */
                synced = true;
            }
            off += (ssize_t)sz;
        }
    }

    if (!comp_name || !shm_name) {
        LOGE("selftest missing globals (comp=%u shm=%u)", comp_name, shm_name);
        close(sock);
        return false;
    }

    /* 3. wl_registry(2)::bind(opcode=0) for wl_compositor -> id 4 */
    uint32_t bind_comp[10] = {0};
    const char *s_comp = "wl_compositor";
    uint32_t s_comp_len = (uint32_t)strlen(s_comp) + 1;
    uint32_t bind_comp_sz = 8 + 4 + 4 + pad4(s_comp_len) + 4 + 4;
    bind_comp[0] = 2;
    bind_comp[1] = (bind_comp_sz << 16) | 0u;
    bind_comp[2] = comp_name;
    bind_comp[3] = s_comp_len;
    memcpy(&bind_comp[4], s_comp, s_comp_len);
    bind_comp[4 + pad4(s_comp_len) / 4] = comp_ver < 4 ? comp_ver : 4;
    bind_comp[5 + pad4(s_comp_len) / 4] = 4;
    send_msg(sock, bind_comp, bind_comp_sz, -1);

    /* 4. wl_registry(2)::bind(opcode=0) for wl_shm -> id 5 */
    uint32_t bind_shm[8] = {0};
    const char *s_shm = "wl_shm";
    uint32_t s_shm_len = (uint32_t)strlen(s_shm) + 1;
    uint32_t bind_shm_sz = 8 + 4 + 4 + pad4(s_shm_len) + 4 + 4;
    bind_shm[0] = 2;
    bind_shm[1] = (bind_shm_sz << 16) | 0u;
    bind_shm[2] = shm_name;
    bind_shm[3] = s_shm_len;
    memcpy(&bind_shm[4], s_shm, s_shm_len);
    bind_shm[4 + pad4(s_shm_len) / 4] = shm_ver < 1 ? shm_ver : 1;
    bind_shm[5 + pad4(s_shm_len) / 4] = 5;
    send_msg(sock, bind_shm, bind_shm_sz, -1);

    /* 5. Create shm backing file in runtime_dir */
    char shm_path[300];
    snprintf(shm_path, sizeof(shm_path), "%s/selftest-shm.bin", runtime_dir);
    int shm_fd = open(shm_path, O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (shm_fd < 0) {
        close(sock);
        return false;
    }
    unlink(shm_path);

    uint32_t stride = (uint32_t)width * 4u;
    size_t shm_size = (size_t)stride * (size_t)height;
    if (ftruncate(shm_fd, (off_t)shm_size) != 0) {
        close(shm_fd);
        close(sock);
        return false;
    }

    uint32_t *px = (uint32_t *)mmap(NULL, shm_size, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
    if (px == MAP_FAILED) {
        close(shm_fd);
        close(sock);
        return false;
    }

    /* Draw a crisp Linux Desktop test pattern (XRGB8888: 0xFFRRGGBB) */
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            uint8_t r = (uint8_t)((x * 255) / width);
            uint8_t g = (uint8_t)((y * 255) / height);
            uint8_t b = 0x90;
            if (((x / 32) + (y / 32)) % 2 == 0) {
                r = (uint8_t)(r / 2 + 20);
                g = (uint8_t)(g / 2 + 30);
            }
            px[y * width + x] = 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
        }
    }
    munmap(px, shm_size);

    /* 6. wl_compositor(4)::create_surface(opcode=0) -> id 6 */
    uint32_t create_surf[3] = {4, (12u << 16) | 0u, 6};
    send_msg(sock, create_surf, sizeof(create_surf), -1);

    /* 7. wl_shm(5)::create_pool(opcode=0) -> id 7, fd=shm_fd, size */
    uint32_t create_pool[4] = {5, (16u << 16) | 0u, 7, (uint32_t)shm_size};
    send_msg(sock, create_pool, sizeof(create_pool), shm_fd);
    close(shm_fd);

    /* 8. wl_shm_pool(7)::create_buffer(opcode=0) -> id 8, offset=0, w, h, stride, fmt=1(XRGB8888) */
    uint32_t create_buf[8] = {7, (32u << 16) | 0u, 8, 0, (uint32_t)width, (uint32_t)height, stride, 1u};
    send_msg(sock, create_buf, sizeof(create_buf), -1);

    /* 9. wl_surface(6)::attach(opcode=1) buf=8, x=0, y=0 */
    uint32_t attach[5] = {6, (20u << 16) | 1u, 8, 0, 0};
    send_msg(sock, attach, sizeof(attach), -1);

    /* 10. wl_surface(6)::commit(opcode=6) */
    uint32_t commit[2] = {6, (8u << 16) | 6u};
    send_msg(sock, commit, sizeof(commit), -1);

    /* 11. wl_display(1)::sync(opcode=0) -> id 9 to wait for server commit */
    uint32_t sync2[3] = {1, (12u << 16) | 0u, 9};
    send_msg(sock, sync2, sizeof(sync2), -1);
    recv(sock, rbuf, sizeof(rbuf), 0);

    close(sock);
    LOGI("Wayland self-test client committed %dx%d wl_shm frame successfully", width, height);
    return true;
}
