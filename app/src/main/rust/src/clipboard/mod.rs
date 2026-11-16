//! Two-way clipboard synchronization between Android and Wayland guests
//! (`wayland-0` server + nested `wayland-1+` compositor bridge).

mod bridge;
mod server;

use std::sync::{Arc, Mutex};

pub use bridge::ClipboardBridge;

#[derive(Default)]
struct ClipboardState {
    to_server: Option<String>,
    to_bridge: Option<String>,
    from_guest: Option<String>,
}

/// Thread-safe mailbox connecting Android `ClipboardManager`, `wayland-0`, and `ClipboardBridge`.
pub struct ClipboardChannel {
    inner: Mutex<ClipboardState>,
}

impl ClipboardChannel {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(ClipboardState::default()),
        })
    }

    pub fn push_from_android(&self, text: String) {
        let mut s = self.inner.lock().unwrap();
        s.to_server = Some(text.clone());
        s.to_bridge = Some(text);
    }

    pub fn take_to_server(&self) -> Option<String> {
        self.inner.lock().ok()?.to_server.take()
    }

    pub fn take_to_bridge(&self) -> Option<String> {
        self.inner.lock().ok()?.to_bridge.take()
    }

    pub fn push_from_guest(&self, text: String) {
        if let Ok(mut s) = self.inner.lock() {
            s.from_guest = Some(text);
        }
    }

    pub fn poll_from_guest(&self) -> Option<String> {
        self.inner.lock().unwrap().from_guest.take()
    }
}
