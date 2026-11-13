#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "vk_presenter.h"
#include "wl_compositor_server.h"
#include "wl_selftest.h"

#define LOG_TAG "LinuxDesktopMVP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef struct {
    ANativeWindow *window;
    VkPresenter *presenter;
    WlCompositorServer *server;
    char status[768];
} NativeSession;

static NativeSession *from_handle(jlong handle) {
    return (NativeSession *)(intptr_t)handle;
}

static void ensure_presenter(NativeSession *s) {
    if (s && !s->presenter) {
        s->presenter = vk_presenter_create(NULL);
    }
}

JNIEXPORT jlong JNICALL Java_dev_wayver_app_core_NativeBridge_nativeCreateSession(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    NativeSession *s = (NativeSession *)calloc(1, sizeof(NativeSession));
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeDestroySession(JNIEnv *env, jobject thiz,
                                                                                  jlong handle) {
    (void)env;
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return;
    if (s->server) {
        wl_server_stop(s->server);
        s->server = NULL;
    }
    if (s->presenter) {
        vk_presenter_destroy(s->presenter);
        s->presenter = NULL;
    }
    if (s->window) {
        ANativeWindow_release(s->window);
        s->window = NULL;
    }
    free(s);
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeInitRuntime(JNIEnv *env, jobject thiz, jlong handle,
                                                                               jstring runtimeDir) {
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return;
    ensure_presenter(s);
    if (!s->server && runtimeDir) {
        const char *dir = (*env)->GetStringUTFChars(env, runtimeDir, NULL);
        s->server = wl_server_start(dir, s->presenter, 1280, 800);
        (*env)->ReleaseStringUTFChars(env, runtimeDir, dir);
    }
}

JNIEXPORT jstring JNICALL Java_dev_wayver_app_core_NativeBridge_nativeGetStatus(JNIEnv *env, jobject thiz,
                                                                                jlong handle) {
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return (*env)->NewStringUTF(env, "No session");
    ensure_presenter(s);
    uint32_t sw = 0, sh = 0, sc = 1;
    wl_server_get_surface_size(s->server, &sw, &sh, &sc);
    snprintf(s->status, sizeof(s->status), "%s\nWayland: %s | Client: %ux%u (scale %ux)",
             vk_presenter_get_info(s->presenter), s->server ? wl_server_get_socket_path(s->server) : "Not started", sw,
             sh, sc);
    return (*env)->NewStringUTF(env, s->status);
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeOnSurfaceCreated(JNIEnv *env, jobject thiz,
                                                                                    jlong handle, jobject surface) {
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return;
    ensure_presenter(s);
    if (s->window != NULL) {
        ANativeWindow_release(s->window);
    }
    s->window = ANativeWindow_fromSurface(env, surface);
    LOGI("Surface created: %p (session %p)", (void *)s->window, (void *)s);
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeOnSurfaceChanged(JNIEnv *env, jobject thiz,
                                                                                    jlong handle, jint width,
                                                                                    jint height) {
    (void)env;
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return;
    ensure_presenter(s);
    LOGI("Surface changed: %dx%d (session %p)", width, height, (void *)s);
    if (s->server) {
        wl_server_set_output_size(s->server, (uint32_t)width, (uint32_t)height);
    }
    if (s->window && s->presenter) {
        if (vk_presenter_set_window(s->presenter, s->window, width, height)) {
            vk_presenter_draw_clear(s->presenter, 0.118f, 0.161f, 0.231f, 1.0f);
        }
    }
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeOnSurfaceDestroyed(JNIEnv *env, jobject thiz,
                                                                                      jlong handle) {
    (void)env;
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return;
    if (s->presenter) {
        vk_presenter_set_window(s->presenter, NULL, 0, 0);
    }
    if (s->window != NULL) {
        ANativeWindow_release(s->window);
        s->window = NULL;
    }
    LOGI("Surface destroyed (session %p)", (void *)s);
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeOnTouch(JNIEnv *env, jobject thiz, jlong handle,
                                                                           jint action, jint id, jfloat x, jfloat y) {
    (void)env;
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (s && s->server) {
        wl_server_send_touch(s->server, action, id, x, y);
    }
}

JNIEXPORT void JNICALL Java_dev_wayver_app_core_NativeBridge_nativeOnKey(JNIEnv *env, jobject thiz, jlong handle,
                                                                         jint evdevKey, jboolean pressed, jint mods) {
    (void)env;
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (s && s->server) {
        wl_server_send_key(s->server, (uint32_t)evdevKey, pressed == JNI_TRUE, (uint32_t)mods);
    }
}

JNIEXPORT jboolean JNICALL Java_dev_wayver_app_core_NativeBridge_nativeRunSelfTest(JNIEnv *env, jobject thiz,
                                                                                   jstring runtimeDir, jint w, jint h) {
    (void)thiz;
    const char *dir = (*env)->GetStringUTFChars(env, runtimeDir, NULL);
    bool ok = wl_selftest_send_frame(dir, w, h);
    (*env)->ReleaseStringUTFChars(env, runtimeDir, dir);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_dev_wayver_app_core_NativeBridge_nativeReloadDriver(JNIEnv *env, jobject thiz,
                                                                                   jlong handle, jstring driverPath,
                                                                                   jint w, jint h) {
    (void)thiz;
    NativeSession *s = from_handle(handle);
    if (!s)
        return (*env)->NewStringUTF(env, "No session");
    const char *path = driverPath ? (*env)->GetStringUTFChars(env, driverPath, NULL) : NULL;
    if (s->server) {
        wl_server_stop(s->server);
        s->server = NULL;
    }
    if (s->presenter) {
        vk_presenter_destroy(s->presenter);
        s->presenter = NULL;
    }
    s->presenter = vk_presenter_create(path);
    if (path)
        (*env)->ReleaseStringUTFChars(env, driverPath, path);

    if (s->window && s->presenter && w > 0 && h > 0) {
        if (vk_presenter_set_window(s->presenter, s->window, w, h)) {
            vk_presenter_draw_clear(s->presenter, 0.118f, 0.161f, 0.231f, 1.0f);
        }
    }
    return (*env)->NewStringUTF(env, vk_presenter_get_info(s->presenter));
}
