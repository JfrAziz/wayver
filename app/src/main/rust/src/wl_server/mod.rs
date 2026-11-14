//! Wayland compositor server for Wayver.
//!
//! Organized into five focused modules:
//! - [`mod@self`]: public `WlCompositorServer` API, input queue, and shared metrics
//! - `state`: compositor state, event loop, and surface commit pipeline
//! - `present_worker`: async paced presentation thread with dirty-rect coalescing
//! - `shm`: `wl_shm` / `wl_shm_pool` / `wl_buffer` mmap management
//! - `protocols`: Wayland protocol `GlobalDispatch` / `Dispatch` handlers

mod present_worker;
mod protocols;
mod shm;
mod state;

use std::collections::VecDeque;
use std::fs;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use log::error;
use wayland_server::ListeningSocket;

use crate::vk_presenter::VkPresenter;
use state::{run_event_loop, EventLoopConfig};

pub(super) enum InputEvent {
    Touch {
        action: i32,
        id: i32,
        x: f32,
        y: f32,
    },
    Key {
        evdev_key: u32,
        pressed: bool,
        mods: u32,
    },
}

pub(super) struct SharedControl {
    pub output_w: u32,
    pub output_h: u32,
    pub pending_reconfigure: bool,
    pub input_queue: VecDeque<InputEvent>,
}

pub(super) struct SharedMetrics {
    pub surface_w: AtomicU32,
    pub surface_h: AtomicU32,
    pub buffer_scale: AtomicU32,
}

/// Embedded Wayland compositor listening on `$XDG_RUNTIME_DIR/wayland-0`.
pub struct WlCompositorServer {
    socket_path: String,
    running: Arc<AtomicBool>,
    wakeup_write: OwnedFd,
    control: Arc<Mutex<SharedControl>>,
    metrics: Arc<SharedMetrics>,
    thread: Option<JoinHandle<()>>,
}

impl WlCompositorServer {
    pub fn start(
        runtime_dir: &str,
        presenter: Arc<VkPresenter>,
        output_w: u32,
        output_h: u32,
    ) -> Option<Self> {
        if runtime_dir.is_empty() {
            return None;
        }
        let socket_path = format!("{runtime_dir}/wayland-0");
        let _ = fs::remove_file(&socket_path);
        if let Ok(c_dir) = std::ffi::CString::new(runtime_dir) {
            unsafe {
                libc::setenv(c"XDG_RUNTIME_DIR".as_ptr(), c_dir.as_ptr(), 1);
            }
        }

        let listener = match ListeningSocket::bind(&socket_path) {
            Ok(l) => l,
            Err(e) => {
                error!("ListeningSocket::bind({socket_path}) failed: {e}");
                return None;
            }
        };

        // Non-blocking self-pipe wakes `poll()` on input, resize, or shutdown.
        let mut fds = [0i32; 2];
        if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
            error!("pipe() failed");
            return None;
        }
        unsafe {
            libc::fcntl(fds[0], libc::F_SETFL, libc::O_NONBLOCK);
            libc::fcntl(fds[1], libc::F_SETFL, libc::O_NONBLOCK);
        }
        let wakeup_read = unsafe { OwnedFd::from_raw_fd(fds[0]) };
        let wakeup_write = unsafe { OwnedFd::from_raw_fd(fds[1]) };

        let running = Arc::new(AtomicBool::new(true));
        let control = Arc::new(Mutex::new(SharedControl {
            output_w,
            output_h,
            pending_reconfigure: false,
            input_queue: VecDeque::with_capacity(128),
        }));
        let metrics = Arc::new(SharedMetrics {
            surface_w: AtomicU32::new(0),
            surface_h: AtomicU32::new(0),
            buffer_scale: AtomicU32::new(1),
        });

        let cfg = EventLoopConfig {
            listener,
            wakeup_read,
            running: Arc::clone(&running),
            control: Arc::clone(&control),
            metrics: Arc::clone(&metrics),
            presenter,
            runtime_dir: runtime_dir.to_string(),
            socket_path: socket_path.clone(),
        };

        let thread = thread::spawn(move || run_event_loop(cfg));

        Some(Self {
            socket_path,
            running,
            wakeup_write,
            control,
            metrics,
            thread: Some(thread),
        })
    }

    pub fn set_output_size(&self, output_w: u32, output_h: u32) {
        {
            let mut ctrl = self.control.lock().unwrap();
            ctrl.output_w = output_w;
            ctrl.output_h = output_h;
            ctrl.pending_reconfigure = true;
        }
        self.wake();
    }

    pub fn get_surface_size(&self) -> (u32, u32, u32) {
        (
            self.metrics.surface_w.load(Ordering::Relaxed),
            self.metrics.surface_h.load(Ordering::Relaxed),
            self.metrics.buffer_scale.load(Ordering::Relaxed).max(1),
        )
    }

    pub fn send_touch(&self, action: i32, id: i32, x: f32, y: f32) {
        {
            let mut ctrl = self.control.lock().unwrap();
            if ctrl.input_queue.len() < 128 {
                ctrl.input_queue
                    .push_back(InputEvent::Touch { action, id, x, y });
            }
        }
        self.wake();
    }

    pub fn send_key(&self, evdev_key: u32, pressed: bool, mods_depressed: u32) {
        {
            let mut ctrl = self.control.lock().unwrap();
            if ctrl.input_queue.len() < 128 {
                ctrl.input_queue.push_back(InputEvent::Key {
                    evdev_key,
                    pressed,
                    mods: mods_depressed,
                });
            }
        }
        self.wake();
    }

    pub fn socket_path(&self) -> &str {
        &self.socket_path
    }

    fn wake(&self) {
        let b = 1u8;
        unsafe {
            libc::write(self.wakeup_write.as_raw_fd(), (&b as *const u8).cast(), 1);
        }
    }
}

impl Drop for WlCompositorServer {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        self.wake();
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
        let _ = fs::remove_file(&self.socket_path);
    }
}
