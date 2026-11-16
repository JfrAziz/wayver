//! Wayland compositor state, event loop, input/output dispatch, and surface commits.

use std::collections::{HashMap, VecDeque};
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use log::{error, info};
use wayland_protocols::xdg::shell::server::{xdg_surface, xdg_toplevel, xdg_wm_base};
use wayland_server::backend::{ClientData, ClientId, DisconnectReason, ObjectId};
use wayland_server::protocol::{
    wl_buffer, wl_callback, wl_compositor, wl_data_device, wl_data_device_manager, wl_keyboard,
    wl_output, wl_pointer, wl_seat, wl_shm, wl_subcompositor, wl_surface, wl_touch,
};
use wayland_server::{Display, DisplayHandle, ListeningSocket, Resource};

use crate::clipboard::ClipboardChannel;
use crate::vk_presenter::{DirtyRect, VkPresenter};

use super::present_worker::AsyncPresentWorker;
use super::shm::ShmBufferData;
use super::{DisplayControl, InputEvent, SharedMetrics};

struct WayverClientData;

impl ClientData for WayverClientData {
    fn initialized(&self, _client_id: ClientId) {}
    fn disconnected(&self, _client_id: ClientId, _reason: DisconnectReason) {}
}

#[derive(Default)]
pub(super) struct SurfaceData {
    pub pending_buffer: Option<wl_buffer::WlBuffer>,
    pub pending_damage: Option<DirtyRect>,
    pub frame_cb: Option<wl_callback::WlCallback>,
    pub xdg_surface: Option<xdg_surface::XdgSurface>,
    pub xdg_toplevel: Option<xdg_toplevel::XdgToplevel>,
    pub is_subsurface: bool,
    pub is_toplevel: bool,
    pub configured: bool,
    pub reconfigured: bool,
    pub output_entered: bool,
    pub buffer_scale: u32,
}

pub(crate) struct CompositorState {
    pub(super) async_presenter: AsyncPresentWorker,
    pub(super) runtime_dir: String,
    pub(super) start_time: Instant,
    pub(super) serial_counter: u32,
    pub(super) output_w: u32,
    pub(super) output_h: u32,
    pub clipboard: Arc<ClipboardChannel>,
    pub(super) metrics: Arc<SharedMetrics>,
    pub(super) surfaces: HashMap<ObjectId, SurfaceData>,
    pub(super) buffers: HashMap<ObjectId, ShmBufferData>,
    pub output_resource: Option<wl_output::WlOutput>,
    pub active_surface: Option<wl_surface::WlSurface>,
    pub touch_resource: Option<wl_touch::WlTouch>,
    pub pointer_resource: Option<wl_pointer::WlPointer>,
    pub keyboard_resource: Option<wl_keyboard::WlKeyboard>,
    pub data_devices: Vec<wl_data_device::WlDataDevice>,
    pub android_clipboard: Option<String>,
    pub guest_owns_selection: bool,
    pub pointer_entered: bool,
    pub keyboard_focused: bool,
    pub last_mods: u32,
    pub pressed_keys: Vec<u32>,
}

impl CompositorState {
    pub fn next_serial(&mut self) -> u32 {
        self.serial_counter = self.serial_counter.wrapping_add(1);
        self.serial_counter
    }

    pub fn now_ms(&self) -> u32 {
        self.start_time.elapsed().as_millis() as u32
    }

    pub fn remove_surface(&mut self, surf_id: &ObjectId) {
        self.surfaces.remove(surf_id);
        if self
            .active_surface
            .as_ref()
            .is_some_and(|s| &s.id() == surf_id)
        {
            self.active_surface = None;
        }
    }

    pub fn send_output_mode(&self, out: &wl_output::WlOutput) {
        let w = if self.output_w > 0 {
            self.output_w as i32
        } else {
            1280
        };
        let h = if self.output_h > 0 {
            self.output_h as i32
        } else {
            800
        };
        out.geometry(
            0,
            0,
            w / 8,
            h / 8,
            wl_output::Subpixel::Unknown,
            "Wayver".into(),
            "AndroidSurface".into(),
            wl_output::Transform::Normal,
        );
        out.mode(
            wl_output::Mode::Current | wl_output::Mode::Preferred,
            w,
            h,
            60000,
        );
        if out.version() >= 2 {
            out.scale(1);
            out.done();
        }
    }

    pub fn send_toplevel_configure(&mut self, surf_id: &ObjectId) {
        let Some(st) = self.surfaces.get_mut(surf_id) else {
            return;
        };
        let (Some(xdg_surf), Some(xdg_top)) = (st.xdg_surface.clone(), st.xdg_toplevel.clone())
        else {
            return;
        };
        let w = (self.output_w.max(320)) as i32;
        let h = (self.output_h.max(240)) as i32;
        let mut states = Vec::with_capacity(8);
        states.extend_from_slice(&(xdg_toplevel::State::Maximized as u32).to_ne_bytes());
        states.extend_from_slice(&(xdg_toplevel::State::Activated as u32).to_ne_bytes());
        st.configured = true;
        xdg_top.configure(w, h, states);
        let serial = self.next_serial();
        xdg_surf.configure(serial);
    }

    fn dispatch_touch(&mut self, action: i32, id: i32, x: f32, y: f32) {
        let Some(active) = self.active_surface.clone() else {
            return;
        };
        let t = self.now_ms();
        let mut sx = x as f64;
        let mut sy = y as f64;
        let sw = self.metrics.surface_w.load(Ordering::Relaxed);
        let sh = self.metrics.surface_h.load(Ordering::Relaxed);
        if self.output_w > 0 && self.output_h > 0 && sw > 0 && sh > 0 {
            let bscale = self.metrics.buffer_scale.load(Ordering::Relaxed).max(1) as f64;
            let logical_w = (sw as f64) / bscale;
            let logical_h = (sh as f64) / bscale;
            sx = (sx * logical_w / (self.output_w as f64)).clamp(0.0, (logical_w - 1.0).max(0.0));
            sy = (sy * logical_h / (self.output_h as f64)).clamp(0.0, (logical_h - 1.0).max(0.0));
        }

        // Finger 0 drives pointer events (desktop apps expect pointer clicks/hover);
        // multi-touch or fallback uses `wl_touch`.
        if let Some(ptr) = self.pointer_resource.clone().filter(|_| id == 0) {
            if !self.pointer_entered {
                let serial = self.next_serial();
                ptr.enter(serial, &active, sx, sy);
                self.pointer_entered = true;
            }
            ptr.motion(t, sx, sy);
            if action == 0 {
                let serial = self.next_serial();
                ptr.button(serial, t, 0x110, wl_pointer::ButtonState::Pressed);
            } else if action == 2 {
                let serial = self.next_serial();
                ptr.button(serial, t, 0x110, wl_pointer::ButtonState::Released);
            }
            ptr.frame();
        } else if let Some(touch) = self.touch_resource.clone() {
            match action {
                0 => {
                    let serial = self.next_serial();
                    touch.down(serial, t, &active, id, sx, sy);
                    touch.frame();
                }
                1 => {
                    touch.motion(t, id, sx, sy);
                    touch.frame();
                }
                2 => {
                    let serial = self.next_serial();
                    touch.up(serial, t, id);
                    touch.frame();
                }
                _ => {}
            }
        }
    }

    fn dispatch_key(
        &mut self,
        dh: &DisplayHandle,
        evdev_key: u32,
        pressed: bool,
        mods_depressed: u32,
    ) {
        let (Some(kbd), Some(active)) =
            (self.keyboard_resource.clone(), self.active_surface.clone())
        else {
            return;
        };
        if !self.keyboard_focused {
            let serial = self.next_serial();
            kbd.enter(serial, &active, Vec::new());
            self.keyboard_focused = true;
            self.send_android_selection_if_owned(dh);
        }
        if mods_depressed != self.last_mods {
            let serial = self.next_serial();
            kbd.modifiers(serial, mods_depressed, 0, 0, 0);
            self.last_mods = mods_depressed;
        }
        if pressed {
            if self.pressed_keys.contains(&evdev_key) {
                return;
            }
            self.pressed_keys.push(evdev_key);
        } else {
            let Some(pos) = self.pressed_keys.iter().position(|&k| k == evdev_key) else {
                return;
            };
            self.pressed_keys.swap_remove(pos);
        }
        let serial = self.next_serial();
        let state = if pressed {
            wl_keyboard::KeyState::Pressed
        } else {
            wl_keyboard::KeyState::Released
        };
        kbd.key(serial, self.now_ms(), evdev_key, state);
    }

    fn handle_reconfigure(&mut self) {
        let Some(active) = self.active_surface.clone() else {
            return;
        };
        if let Some(out) = self.output_resource.clone() {
            self.send_output_mode(&out);
        }
        let id = active.id();
        if self.surfaces.get(&id).is_some_and(|s| s.is_toplevel) {
            self.send_toplevel_configure(&id);
        }
    }
}

pub(super) struct EventLoopConfig {
    pub listener: ListeningSocket,
    pub wakeup_read: OwnedFd,
    pub running: Arc<AtomicBool>,
    pub display: Arc<Mutex<DisplayControl>>,
    pub input: Arc<Mutex<VecDeque<InputEvent>>>,
    pub clipboard: Arc<ClipboardChannel>,
    pub metrics: Arc<SharedMetrics>,
    pub presenter: Arc<VkPresenter>,
    pub runtime_dir: String,
    pub socket_path: String,
}

pub(super) fn run_event_loop(cfg: EventLoopConfig) {
    let EventLoopConfig {
        listener,
        wakeup_read,
        running,
        display: display_ctrl,
        input,
        clipboard,
        metrics,
        presenter,
        runtime_dir,
        socket_path,
    } = cfg;
    let mut display: Display<CompositorState> = match Display::new() {
        Ok(d) => d,
        Err(e) => {
            error!("Display::new failed: {e}");
            return;
        }
    };
    let mut dh = display.handle();

    dh.create_global::<CompositorState, wl_compositor::WlCompositor, ()>(4, ());
    dh.create_global::<CompositorState, wl_subcompositor::WlSubcompositor, ()>(1, ());
    dh.create_global::<CompositorState, wl_shm::WlShm, ()>(1, ());
    dh.create_global::<CompositorState, xdg_wm_base::XdgWmBase, ()>(2, ());
    dh.create_global::<CompositorState, wl_output::WlOutput, ()>(2, ());
    dh.create_global::<CompositorState, wl_seat::WlSeat, ()>(5, ());
    dh.create_global::<CompositorState, wl_data_device_manager::WlDataDeviceManager, ()>(3, ());

    let (init_w, init_h) = {
        let d = display_ctrl.lock().unwrap();
        (d.output_w, d.output_h)
    };

    let start_time = Instant::now();
    let async_presenter = AsyncPresentWorker::new(presenter, dh.clone(), start_time);
    let mut state = CompositorState {
        async_presenter,
        runtime_dir,
        start_time,
        serial_counter: 1,
        output_w: init_w,
        output_h: init_h,
        clipboard: Arc::clone(&clipboard),
        metrics,
        surfaces: HashMap::new(),
        buffers: HashMap::new(),
        output_resource: None,
        active_surface: None,
        touch_resource: None,
        pointer_resource: None,
        keyboard_resource: None,
        data_devices: Vec::new(),
        android_clipboard: None,
        guest_owns_selection: false,
        pointer_entered: false,
        keyboard_focused: false,
        last_mods: 0,
        pressed_keys: Vec::new(),
    };

    info!("Wayland event loop started on {socket_path}");

    let mut pending_events: Vec<InputEvent> = Vec::with_capacity(32);

    while running.load(Ordering::SeqCst) {
        let mut poll_fds = [
            libc::pollfd {
                fd: listener.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: display.backend().poll_fd().as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: wakeup_read.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
        ];

        let _ = display.flush_clients();
        let ret = unsafe { libc::poll(poll_fds.as_mut_ptr(), poll_fds.len() as _, -1) };
        if ret < 0 {
            let err = std::io::Error::last_os_error();
            if err.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            error!("poll failed: {err}");
            break;
        }
        if !running.load(Ordering::SeqCst) {
            break;
        }

        // Accept any pending Wayland client connections.
        while let Ok(Some(stream)) = listener.accept() {
            if let Err(e) = dh.insert_client(stream, Arc::new(WayverClientData)) {
                error!("insert_client failed: {e}");
            }
        }

        // Drain wakeup pipe and process pending input / output resize.
        if (poll_fds[2].revents & libc::POLLIN) != 0 {
            let mut buf = [0u8; 64];
            while unsafe { libc::read(wakeup_read.as_raw_fd(), buf.as_mut_ptr().cast(), buf.len()) }
                > 0
            {}

            // Drain clipboard updates before input so SetClipboard + Ctrl+V stays ordered.
            if let Some(text) = clipboard.take_to_server() {
                state.guest_owns_selection = false;
                state.android_clipboard = Some(text);
                state.send_android_selection(&dh);
            }

            {
                let mut q = input.lock().unwrap();
                pending_events.clear();
                pending_events.extend(q.drain(..));
            }
            for ev in pending_events.drain(..) {
                match ev {
                    InputEvent::Touch { action, id, x, y } => {
                        state.dispatch_touch(action, id, x, y);
                    }
                    InputEvent::Key {
                        evdev_key,
                        pressed,
                        mods,
                    } => {
                        state.dispatch_key(&dh, evdev_key, pressed, mods);
                    }
                }
            }

            let do_reconf = {
                let mut d = display_ctrl.lock().unwrap();
                state.output_w = d.output_w;
                state.output_h = d.output_h;
                let reconf = d.pending_reconfigure;
                d.pending_reconfigure = false;
                reconf
            };
            if do_reconf {
                state.handle_reconfigure();
            }
        }

        if let Err(e) = display.dispatch_clients(&mut state) {
            error!("dispatch_clients error: {e}");
        }
        let _ = display.flush_clients();
    }

    info!("Wayland event loop exited");
}

/// Handles `wl_surface.commit`: configures new toplevels, submits attached SHM pixels to
/// `AsyncPresentWorker`, and releases the `wl_buffer` back to the client immediately.
pub(super) fn handle_surface_commit(state: &mut CompositorState, resource: &wl_surface::WlSurface) {
    let surf_id = resource.id();
    let Some((
        is_toplevel,
        is_subsurface,
        output_entered,
        configured,
        bscale,
        pending_buf,
        pending_damage,
        frame_cb,
    )) = state.surfaces.get_mut(&surf_id).map(|st| {
        (
            st.is_toplevel,
            st.is_subsurface,
            st.output_entered,
            st.configured,
            st.buffer_scale.max(1),
            st.pending_buffer.take(),
            st.pending_damage.take(),
            st.frame_cb.take(),
        )
    })
    else {
        return;
    };

    if is_toplevel {
        state.active_surface = Some(resource.clone());
        if !output_entered {
            if let Some(out) = state.output_resource.as_ref() {
                if out.client() == resource.client() {
                    resource.enter(out);
                    if let Some(st) = state.surfaces.get_mut(&surf_id) {
                        st.output_entered = true;
                    }
                }
            }
        }
        if pending_buf.is_none() && !configured {
            state.send_toplevel_configure(&surf_id);
        }
        if let Some(kbd) = state.keyboard_resource.clone() {
            if !state.keyboard_focused {
                let serial = state.next_serial();
                kbd.enter(serial, resource, Vec::new());
                state.keyboard_focused = true;
            }
        }
    }

    let mut pending_frame_cb = frame_cb;
    if let Some(buf) = pending_buf {
        if !is_subsurface && (is_toplevel || state.active_surface.is_none()) {
            if let Some(bdata) = state.buffers.get(&buf.id()) {
                let is_bgra = matches!(
                    bdata.format,
                    wl_shm::Format::Argb8888 | wl_shm::Format::Xrgb8888
                );
                if bdata.width > 0 && bdata.height > 0 && bdata.offset >= 0 && bdata.stride > 0 {
                    let prev_w = state.metrics.surface_w.load(Ordering::Relaxed);
                    let prev_h = state.metrics.surface_h.load(Ordering::Relaxed);
                    let prev_s = state.metrics.buffer_scale.load(Ordering::Relaxed);
                    let w = bdata.width as u32;
                    let h = bdata.height as u32;
                    if prev_w != w || prev_h != h || prev_s != bscale {
                        state.metrics.surface_w.store(w, Ordering::Relaxed);
                        state.metrics.surface_h.store(h, Ordering::Relaxed);
                        state.metrics.buffer_scale.store(bscale, Ordering::Relaxed);
                        info!(
                            "Client buffer size: {}x{} (scale={}, output {}x{})",
                            w, h, bscale, state.output_w, state.output_h
                        );
                    }

                    let pool = bdata.pool.lock().unwrap();
                    let off = bdata.offset as usize;
                    let need = (bdata.stride as usize) * (bdata.height as usize);
                    if !pool.ptr.is_null() && off + need <= pool.size {
                        let pixels = unsafe { std::slice::from_raw_parts(pool.ptr.add(off), need) };
                        state.async_presenter.submit(
                            pixels,
                            w,
                            h,
                            bdata.stride as u32,
                            is_bgra,
                            pending_damage,
                            pending_frame_cb.take(),
                        );
                    }
                    drop(pool);

                    let expected_w = state.output_w;
                    let expected_h = state.output_h;
                    let need_reconf = state.surfaces.get_mut(&surf_id).is_some_and(|st| {
                        if st.is_toplevel
                            && !st.reconfigured
                            && expected_w > 0
                            && expected_h > 0
                            && (w != expected_w || h != expected_h)
                        {
                            st.reconfigured = true;
                            true
                        } else {
                            false
                        }
                    });
                    if need_reconf {
                        state.send_toplevel_configure(&surf_id);
                    }
                }
            }
        }
        buf.release();
    }

    if let Some(cb) = pending_frame_cb {
        cb.done(state.now_ms());
    }
}
