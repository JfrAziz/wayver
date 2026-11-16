mod audio;
mod clipboard;
mod vk_presenter;
mod wl_server;

use std::sync::{Arc, Once};

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use log::info;

use crate::audio::PulseAudioServer;
use crate::vk_presenter::VkPresenter;
use crate::wl_server::WlCompositorServer;

static INIT_LOG: Once = Once::new();

fn init_logger() {
    INIT_LOG.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("Wayver"),
        );
    });
}

struct NativeSession {
    window: *mut ndk_sys::ANativeWindow,
    presenter: Option<Arc<VkPresenter>>,
    server: Option<WlCompositorServer>,
    audio: Option<PulseAudioServer>,
    refresh_rate_hz: f32,
}

impl NativeSession {
    fn ensure_presenter(&mut self) -> Arc<VkPresenter> {
        if let Some(p) = &self.presenter {
            Arc::clone(p)
        } else {
            let p = Arc::new(VkPresenter::new());
            p.set_refresh_rate(self.refresh_rate_hz);
            self.presenter = Some(Arc::clone(&p));
            p
        }
    }

    fn release_window(&mut self) {
        if !self.window.is_null() {
            unsafe {
                ndk_sys::ANativeWindow_release(self.window);
            }
            self.window = std::ptr::null_mut();
        }
    }
}

impl Drop for NativeSession {
    fn drop(&mut self) {
        self.audio.take();
        self.server.take();
        self.presenter.take();
        self.release_window();
    }
}

unsafe fn from_handle<'a>(handle: jlong) -> Option<&'a mut NativeSession> {
    if handle == 0 {
        None
    } else {
        Some(&mut *(handle as *mut NativeSession))
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeCreateSession(
    _env: JNIEnv,
    _thiz: JClass,
) -> jlong {
    init_logger();
    let session = Box::new(NativeSession {
        window: std::ptr::null_mut(),
        presenter: None,
        server: None,
        audio: None,
        refresh_rate_hz: 60.0,
    });
    Box::into_raw(session) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeDestroySession(
    _env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut NativeSession));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeInitRuntime(
    mut env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
    runtime_dir: JString,
) {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return;
    };
    let presenter = s.ensure_presenter();
    if s.server.is_none() && !runtime_dir.is_null() {
        if let Ok(dir) = env.get_string(&runtime_dir) {
            let dir_str = dir.to_string_lossy();
            s.server = WlCompositorServer::start(&dir_str, presenter, 1280, 800);
            if s.audio.is_none() {
                s.audio = PulseAudioServer::start(&dir_str);
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeGetStatus(
    env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
) -> jstring {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return env
            .new_string("No session")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    };
    let presenter = s.ensure_presenter();
    let (sw, sh, sc) = s
        .server
        .as_ref()
        .map(|srv| srv.get_surface_size())
        .unwrap_or((0, 0, 1));
    let sock = s
        .server
        .as_ref()
        .map(|srv| srv.socket_path())
        .unwrap_or("Not started");
    let status = format!(
        "{}\nWayland: {} | Client: {}x{} (scale {}x)",
        presenter.get_info(),
        sock,
        sw,
        sh,
        sc
    );
    env.new_string(status)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeOnSurfaceCreated(
    env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
    surface: JObject,
) {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return;
    };
    s.ensure_presenter();
    s.release_window();
    s.window =
        unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_raw().cast(), surface.as_raw()) };
    info!("Surface created: {:p} (session {:p})", s.window, s);
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeOnSurfaceChanged(
    _env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return;
    };
    let presenter = s.ensure_presenter();
    info!("Surface changed: {}x{} (session {:p})", width, height, s);
    if let Some(srv) = s.server.as_ref() {
        srv.set_output_size(width.max(0) as u32, height.max(0) as u32);
    }
    if !s.window.is_null() && presenter.set_window(s.window, width, height) {
        presenter.draw_clear(0.118, 0.161, 0.231, 1.0);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeOnSurfaceDestroyed(
    _env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
) {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return;
    };
    if let Some(presenter) = s.presenter.as_ref() {
        presenter.set_window(std::ptr::null_mut(), 0, 0);
    }
    s.release_window();
    info!("Surface destroyed (session {:p})", s);
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeOnTouch(
    _env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
    action: jint,
    id: jint,
    x: jfloat,
    y: jfloat,
) {
    if let Some(s) = unsafe { from_handle(handle) } {
        if let Some(srv) = s.server.as_ref() {
            srv.send_touch(action, id, x, y);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeOnKey(
    _env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
    evdev_key: jint,
    pressed: jboolean,
    mods: jint,
) {
    if let Some(s) = unsafe { from_handle(handle) } {
        if let Some(srv) = s.server.as_ref() {
            srv.send_key(evdev_key as u32, pressed == JNI_TRUE, mods as u32);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeSetRefreshRate(
    _env: JNIEnv,
    _thiz: JClass,
    handle: jlong,
    hz: jfloat,
) {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return;
    };
    s.refresh_rate_hz = hz;
    if let Some(p) = &s.presenter {
        p.set_refresh_rate(hz);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativeSetClipboard(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    text: JString,
) {
    let Ok(text) = env.get_string(&text) else {
        return;
    };
    let text_str: String = text.into();
    if let Some(s) = unsafe { from_handle(handle) } {
        if let Some(srv) = &s.server {
            srv.set_clipboard(text_str);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_wayver_app_core_NativeBridge_nativePollClipboard(
    env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) -> jstring {
    let Some(s) = (unsafe { from_handle(handle) }) else {
        return std::ptr::null_mut();
    };
    let Some(srv) = &s.server else {
        return std::ptr::null_mut();
    };
    let Some(text) = srv.poll_clipboard() else {
        return std::ptr::null_mut();
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
