//! Bridges clipboard between Android and nested wlroots compositors (`wayland-1`)
//! via `zwlr_data_control_manager_v1`.

use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::FileTypeExt;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use log::{info, warn};
use wayland_client::backend::ObjectId;
use wayland_client::protocol::{wl_registry, wl_seat};
use wayland_client::{Connection, Dispatch, EventQueue, Proxy, QueueHandle};
use wayland_protocols_wlr::data_control::v1::client::{
    zwlr_data_control_device_v1, zwlr_data_control_manager_v1, zwlr_data_control_offer_v1,
    zwlr_data_control_source_v1,
};

use super::ClipboardChannel;

const TEXT_MIME_TYPES: &[&str] = &["text/plain;charset=utf-8", "text/plain", "UTF8_STRING"];
const MAX_CLIPBOARD_BYTES: usize = 1_048_576;

pub struct ClipboardBridge {
    running: Arc<AtomicBool>,
    wakeup_write: OwnedFd,
    thread: Option<JoinHandle<()>>,
}

impl ClipboardBridge {
    pub fn start(runtime_dir: String, clipboard: Arc<ClipboardChannel>) -> Option<Self> {
        let mut fds = [0i32; 2];
        if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
            warn!("clipboard bridge pipe() failed");
            return None;
        }
        unsafe {
            libc::fcntl(fds[0], libc::F_SETFL, libc::O_NONBLOCK);
            libc::fcntl(fds[1], libc::F_SETFL, libc::O_NONBLOCK);
        }
        let wakeup_read = unsafe { OwnedFd::from_raw_fd(fds[0]) };
        let wakeup_write = unsafe { OwnedFd::from_raw_fd(fds[1]) };
        let running = Arc::new(AtomicBool::new(true));
        let thread_running = Arc::clone(&running);

        let thread = thread::spawn(move || {
            let mut last_android_text: Option<String> = None;
            while thread_running.load(Ordering::SeqCst) {
                if let Some(socket_path) = find_nested_socket(&runtime_dir) {
                    match UnixStream::connect(&socket_path) {
                        Ok(stream) => {
                            info!(
                                "nested clipboard bridge connected to {}",
                                socket_path.display()
                            );
                            run_client_loop(
                                stream,
                                &runtime_dir,
                                &socket_path,
                                &thread_running,
                                &clipboard,
                                &wakeup_read,
                                &mut last_android_text,
                            );
                            info!(
                                "nested clipboard bridge disconnected from {}",
                                socket_path.display()
                            );
                        }
                        Err(e) => {
                            warn!(
                                "nested clipboard connect({}) failed: {e}",
                                socket_path.display()
                            );
                        }
                    }
                }
                // Drain any stale wakeup bytes while waiting for nested compositor socket.
                drain_fd(&wakeup_read);
                thread::sleep(Duration::from_millis(500));
            }
        });

        Some(Self {
            running,
            wakeup_write,
            thread: Some(thread),
        })
    }

    pub fn wake(&self) {
        let b = 1u8;
        unsafe {
            libc::write(self.wakeup_write.as_raw_fd(), (&b as *const u8).cast(), 1);
        }
    }
}

impl Drop for ClipboardBridge {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        self.wake();
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}

fn find_nested_socket(runtime_dir: &str) -> Option<PathBuf> {
    let entries = fs::read_dir(Path::new(runtime_dir)).ok()?;
    let mut best: Option<(std::time::SystemTime, u32, PathBuf)> = None;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name_str) = name.to_str() else {
            continue;
        };
        let Some(num) = name_str
            .strip_prefix("wayland-")
            .and_then(|s| s.parse::<u32>().ok())
        else {
            continue;
        };
        if num == 0 {
            continue;
        }
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        if !meta.file_type().is_socket() {
            continue;
        }
        let mtime = meta.modified().unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        let candidate = (mtime, num, entry.path());
        if best
            .as_ref()
            .is_none_or(|(t, n, _)| candidate.0 > *t || (candidate.0 == *t && candidate.1 > *n))
        {
            best = Some(candidate);
        }
    }
    best.map(|(_, _, p)| p)
}

struct BridgeState {
    clipboard: Arc<ClipboardChannel>,
    seat: Option<wl_seat::WlSeat>,
    manager: Option<zwlr_data_control_manager_v1::ZwlrDataControlManagerV1>,
    device: Option<zwlr_data_control_device_v1::ZwlrDataControlDeviceV1>,
    active_source: Option<zwlr_data_control_source_v1::ZwlrDataControlSourceV1>,
    offer_mimes: HashMap<ObjectId, Vec<String>>,
    last_from_android: Option<String>,
}

impl BridgeState {
    fn maybe_bind_device(&mut self, qh: &QueueHandle<Self>) {
        if self.device.is_some() {
            return;
        }
        let (Some(mgr), Some(seat)) = (&self.manager, &self.seat) else {
            return;
        };
        let dev = mgr.get_data_device(seat, qh, ());
        self.device = Some(dev);
        info!("nested clipboard: bound zwlr_data_control_device_v1");
    }

    fn push_android_text(&mut self, qh: &QueueHandle<Self>, text: String) {
        let (Some(mgr), Some(dev)) = (&self.manager, &self.device) else {
            return;
        };
        if let Some(old) = self.active_source.take() {
            old.destroy();
        }
        let source = mgr.create_data_source(qh, text.clone());
        for mime in TEXT_MIME_TYPES {
            source.offer((*mime).to_string());
        }
        dev.set_selection(Some(&source));
        self.last_from_android = Some(text);
        self.active_source = Some(source);
    }
}

fn run_client_loop(
    stream: UnixStream,
    runtime_dir: &str,
    socket_path: &Path,
    running: &AtomicBool,
    clipboard: &Arc<ClipboardChannel>,
    wakeup_read: &OwnedFd,
    last_android_text: &mut Option<String>,
) {
    let Ok(conn) = Connection::from_socket(stream) else {
        return;
    };
    let mut event_queue: EventQueue<BridgeState> = conn.new_event_queue();
    let qh = event_queue.handle();
    let display = conn.display();
    let _registry = display.get_registry(&qh, ());

    let mut state = BridgeState {
        clipboard: Arc::clone(clipboard),
        seat: None,
        manager: None,
        device: None,
        active_source: None,
        offer_mimes: HashMap::new(),
        last_from_android: None,
    };

    if let Err(e) = event_queue.roundtrip(&mut state) {
        warn!("nested clipboard: initial roundtrip failed: {e}");
        return;
    }

    // Push any pending (or previously synced) Android clipboard text to this compositor.
    if let Some(initial) = clipboard.take_to_bridge() {
        *last_android_text = Some(initial.clone());
        state.push_android_text(&qh, initial);
    } else if let Some(prev) = last_android_text.clone() {
        state.push_android_text(&qh, prev);
    }

    while running.load(Ordering::SeqCst) {
        if let Some(pending) = clipboard.take_to_bridge() {
            *last_android_text = Some(pending.clone());
            state.push_android_text(&qh, pending);
        }

        if event_queue.dispatch_pending(&mut state).is_err() {
            break;
        }
        if conn.flush().is_err() {
            break;
        }

        // Switch immediately if a newer nested compositor socket appeared.
        if let Some(newest) = find_nested_socket(runtime_dir) {
            if newest != socket_path {
                info!(
                    "nested clipboard switching from {} to {}",
                    socket_path.display(),
                    newest.display()
                );
                break;
            }
        }

        let Some(guard) = conn.prepare_read() else {
            continue;
        };
        let mut fds = [
            libc::pollfd {
                fd: guard.connection_fd().as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: wakeup_read.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let ret = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as _, 500) };
        if ret < 0 {
            let err = std::io::Error::last_os_error();
            drop(guard);
            if err.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            break;
        }
        if (fds[1].revents & libc::POLLIN) != 0 {
            drain_fd(wakeup_read);
        }
        if (fds[0].revents & (libc::POLLIN | libc::POLLERR | libc::POLLHUP)) != 0 {
            if guard.read().is_err() {
                break;
            }
        } else {
            drop(guard);
        }
    }
}

fn drain_fd(fd: &OwnedFd) {
    let mut buf = [0u8; 64];
    while unsafe { libc::read(fd.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len()) } > 0 {}
}

impl Dispatch<wl_registry::WlRegistry, ()> for BridgeState {
    fn event(
        state: &mut Self,
        registry: &wl_registry::WlRegistry,
        event: wl_registry::Event,
        _: &(),
        _: &Connection,
        qh: &QueueHandle<Self>,
    ) {
        if let wl_registry::Event::Global {
            name,
            interface,
            version,
        } = event
        {
            match interface.as_str() {
                "wl_seat" if state.seat.is_none() => {
                    state.seat =
                        Some(registry.bind::<wl_seat::WlSeat, _, _>(name, version.min(5), qh, ()));
                    state.maybe_bind_device(qh);
                }
                "zwlr_data_control_manager_v1" if state.manager.is_none() => {
                    state.manager = Some(
                        registry
                            .bind::<zwlr_data_control_manager_v1::ZwlrDataControlManagerV1, _, _>(
                                name,
                                version.min(2),
                                qh,
                                (),
                            ),
                    );
                    state.maybe_bind_device(qh);
                }
                _ => {}
            }
        }
    }
}

impl Dispatch<wl_seat::WlSeat, ()> for BridgeState {
    fn event(
        _: &mut Self,
        _: &wl_seat::WlSeat,
        _: wl_seat::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<zwlr_data_control_manager_v1::ZwlrDataControlManagerV1, ()> for BridgeState {
    fn event(
        _: &mut Self,
        _: &zwlr_data_control_manager_v1::ZwlrDataControlManagerV1,
        _: zwlr_data_control_manager_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<zwlr_data_control_offer_v1::ZwlrDataControlOfferV1, ()> for BridgeState {
    fn event(
        state: &mut Self,
        offer: &zwlr_data_control_offer_v1::ZwlrDataControlOfferV1,
        event: zwlr_data_control_offer_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let zwlr_data_control_offer_v1::Event::Offer { mime_type } = event {
            state
                .offer_mimes
                .entry(offer.id())
                .or_default()
                .push(mime_type);
        }
    }
}

impl Dispatch<zwlr_data_control_device_v1::ZwlrDataControlDeviceV1, ()> for BridgeState {
    wayland_client::event_created_child!(BridgeState, zwlr_data_control_device_v1::ZwlrDataControlDeviceV1, [
        zwlr_data_control_device_v1::EVT_DATA_OFFER_OPCODE => (zwlr_data_control_offer_v1::ZwlrDataControlOfferV1, ()),
    ]);

    fn event(
        state: &mut Self,
        _: &zwlr_data_control_device_v1::ZwlrDataControlDeviceV1,
        event: zwlr_data_control_device_v1::Event,
        _: &(),
        conn: &Connection,
        _: &QueueHandle<Self>,
    ) {
        match event {
            zwlr_data_control_device_v1::Event::Selection { id: Some(offer) } => {
                let mimes = state.offer_mimes.remove(&offer.id()).unwrap_or_default();
                let Some(mime) = TEXT_MIME_TYPES
                    .iter()
                    .copied()
                    .find(|m| mimes.iter().any(|o| o == m))
                else {
                    offer.destroy();
                    return;
                };
                let mut fds = [0i32; 2];
                if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
                    warn!("nested clipboard pipe() failed");
                    offer.destroy();
                    return;
                }
                let read_fd = unsafe { OwnedFd::from_raw_fd(fds[0]) };
                let write_fd = unsafe { OwnedFd::from_raw_fd(fds[1]) };
                offer.receive(mime.to_string(), write_fd.as_fd());
                drop(write_fd);
                let _ = conn.flush();
                offer.destroy();

                let clipboard = Arc::clone(&state.clipboard);
                let last_android = state.last_from_android.clone();
                thread::spawn(move || {
                    let mut file = File::from(read_fd);
                    let mut buf = Vec::with_capacity(1024);
                    let mut chunk = [0u8; 4096];
                    while buf.len() < MAX_CLIPBOARD_BYTES {
                        match file.read(&mut chunk) {
                            Ok(0) => break,
                            Ok(n) => {
                                let take = n.min(MAX_CLIPBOARD_BYTES - buf.len());
                                buf.extend_from_slice(&chunk[..take]);
                            }
                            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                            Err(_) => break,
                        }
                    }
                    if !buf.is_empty() {
                        let text = String::from_utf8_lossy(&buf).into_owned();
                        if last_android.as_deref() != Some(text.as_str()) {
                            info!("nested clipboard: read {} bytes from wayland-1", text.len());
                            clipboard.push_from_guest(text);
                        }
                    }
                });
            }
            zwlr_data_control_device_v1::Event::Selection { id: None } => {
                state.offer_mimes.clear();
            }
            _ => {}
        }
    }
}

impl Dispatch<zwlr_data_control_source_v1::ZwlrDataControlSourceV1, String> for BridgeState {
    fn event(
        state: &mut Self,
        source: &zwlr_data_control_source_v1::ZwlrDataControlSourceV1,
        event: zwlr_data_control_source_v1::Event,
        text: &String,
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        match event {
            zwlr_data_control_source_v1::Event::Send { mime_type, fd } => {
                if TEXT_MIME_TYPES.contains(&mime_type.as_str()) || mime_type.starts_with("text/") {
                    let payload = text.clone();
                    thread::spawn(move || {
                        let mut file = File::from(fd);
                        let _ = file.write_all(payload.as_bytes());
                    });
                }
            }
            zwlr_data_control_source_v1::Event::Cancelled => {
                if state
                    .active_source
                    .as_ref()
                    .is_some_and(|s| s.id() == source.id())
                {
                    state.active_source = None;
                }
                source.destroy();
            }
            _ => {}
        }
    }
}
