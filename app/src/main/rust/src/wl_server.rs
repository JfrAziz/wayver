use std::collections::{HashMap, VecDeque};
use std::ffi::CString;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Instant;

use log::{error, info};
use wayland_protocols::xdg::shell::server::{
    xdg_positioner, xdg_surface, xdg_toplevel, xdg_wm_base,
};
use wayland_server::backend::{ClientData, ClientId, DisconnectReason, ObjectId};
use wayland_server::protocol::{
    wl_buffer, wl_callback, wl_compositor, wl_data_device, wl_data_device_manager, wl_data_source,
    wl_keyboard, wl_output, wl_pointer, wl_region, wl_seat, wl_shm, wl_shm_pool, wl_subcompositor,
    wl_subsurface, wl_surface, wl_touch,
};
use wayland_server::{
    DataInit, Dispatch, Display, DisplayHandle, GlobalDispatch, ListeningSocket, New, Resource,
    WEnum,
};

macro_rules! delegate_noop {
    ($iface:ty) => {
        impl Dispatch<$iface, ()> for CompositorState {
            fn request(
                _state: &mut Self,
                _client: &wayland_server::Client,
                _resource: &$iface,
                _request: <$iface as Resource>::Request,
                _data: &(),
                _dhandle: &DisplayHandle,
                _data_init: &mut DataInit<'_, Self>,
            ) {
            }
        }
    };
}

use crate::vk_presenter::{DirtyRect, VkPresenter};

const MINIMAL_XKB_KEYMAP: &[u8] = b"xkb_keymap {
  xkb_keycodes \"evdev\" {
    minimum = 8; maximum = 255;
    <ESC> = 9; <AE01> = 10; <AE02> = 11; <AE03> = 12; <AE04> = 13; <AE05> = 14;
    <AE06> = 15; <AE07> = 16; <AE08> = 17; <AE09> = 18; <AE10> = 19; <AE11> = 20;
    <AE12> = 21; <BKSP> = 22; <TAB> = 23;
    <AD01> = 24; <AD02> = 25; <AD03> = 26; <AD04> = 27; <AD05> = 28; <AD06> = 29;
    <AD07> = 30; <AD08> = 31; <AD09> = 32; <AD10> = 33; <AD11> = 34; <AD12> = 35;
    <RTRN> = 36; <LCTL> = 37;
    <AC01> = 38; <AC02> = 39; <AC03> = 40; <AC04> = 41; <AC05> = 42; <AC06> = 43;
    <AC07> = 44; <AC08> = 45; <AC09> = 46; <AC10> = 47; <AC11> = 48; <TLDE> = 49;
    <LFSH> = 50; <BKSL> = 51;
    <AB01> = 52; <AB02> = 53; <AB03> = 54; <AB04> = 55; <AB05> = 56; <AB06> = 57;
    <AB07> = 58; <AB08> = 59; <AB09> = 60; <AB10> = 61; <RTSH> = 62;
    <LALT> = 64; <SPCE> = 65;
    <UP> = 111; <LEFT> = 113; <RGHT> = 114; <DOWN> = 116;
  };
  xkb_types \"complete\" {
    virtual_modifiers NumLock,Alt;
    type \"ONE_LEVEL\" { modifiers = none; map[none] = Level1; level_name[Level1] = \"Any\"; };
    type \"TWO_LEVEL\" { modifiers = Shift; map[Shift] = Level2; level_name[Level1] = \"Base\"; level_name[Level2] = \"Shift\"; };
    type \"ALPHABETIC\" { modifiers = Shift; map[Shift] = Level2; level_name[Level1] = \"Base\"; level_name[Level2] = \"Caps\"; };
  };
  xkb_compatibility \"complete\" {
    interpret Shift_L { action = SetMods(modifiers=Shift); };
    interpret Shift_R { action = SetMods(modifiers=Shift); };
    interpret Control_L { action = SetMods(modifiers=Control); };
    interpret Alt_L { action = SetMods(modifiers=Mod1); };
  };
  xkb_symbols \"pc+us\" {
    key <ESC>  { [ Escape ] };
    key <AE01> { [ 1, exclam ] }; key <AE02> { [ 2, at ] }; key <AE03> { [ 3, numbersign ] };
    key <AE04> { [ 4, dollar ] }; key <AE05> { [ 5, percent ] }; key <AE06> { [ 6, asciicircum ] };
    key <AE07> { [ 7, ampersand ] }; key <AE08> { [ 8, asterisk ] }; key <AE09> { [ 9, parenleft ] };
    key <AE10> { [ 0, parenright ] }; key <AE11> { [ minus, underscore ] }; key <AE12> { [ equal, plus ] };
    key <BKSP> { [ BackSpace ] }; key <TAB> { [ Tab ] };
    key <AD01> { [ q, Q ] }; key <AD02> { [ w, W ] }; key <AD03> { [ e, E ] }; key <AD04> { [ r, R ] };
    key <AD05> { [ t, T ] }; key <AD06> { [ y, Y ] }; key <AD07> { [ u, U ] }; key <AD08> { [ i, I ] };
    key <AD09> { [ o, O ] }; key <AD10> { [ p, P ] }; key <AD11> { [ bracketleft, braceleft ] }; key <AD12> { [ bracketright, braceright ] };
    key <RTRN> { [ Return ] }; key <LCTL> { [ Control_L ] };
    key <AC01> { [ a, A ] }; key <AC02> { [ s, S ] }; key <AC03> { [ d, D ] }; key <AC04> { [ f, F ] };
    key <AC05> { [ g, G ] }; key <AC06> { [ h, H ] }; key <AC07> { [ j, J ] }; key <AC08> { [ k, K ] };
    key <AC09> { [ l, L ] }; key <AC10> { [ semicolon, colon ] }; key <AC11> { [ apostrophe, quotedbl ] }; key <TLDE> { [ grave, asciitilde ] };
    key <LFSH> { [ Shift_L ] }; key <BKSL> { [ backslash, bar ] };
    key <AB01> { [ z, Z ] }; key <AB02> { [ x, X ] }; key <AB03> { [ c, C ] }; key <AB04> { [ v, V ] };
    key <AB05> { [ b, B ] }; key <AB06> { [ n, N ] }; key <AB07> { [ m, M ] };
    key <AB08> { [ comma, less ] }; key <AB09> { [ period, greater ] }; key <AB10> { [ slash, question ] }; key <RTSH> { [ Shift_R ] };
    key <LALT> { [ Alt_L ] }; key <SPCE> { [ space ] };
    key <UP> { [ Up ] }; key <LEFT> { [ Left ] }; key <RGHT> { [ Right ] }; key <DOWN> { [ Down ] };
    modifier_map Shift { <LFSH>, <RTSH> };
    modifier_map Control { <LCTL> };
    modifier_map Mod1 { <LALT> };
  };
};
\0";

enum InputEvent {
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

struct SharedControl {
    output_w: u32,
    output_h: u32,
    pending_reconfigure: bool,
    input_queue: VecDeque<InputEvent>,
}

struct SharedMetrics {
    surface_w: AtomicU32,
    surface_h: AtomicU32,
    buffer_scale: AtomicU32,
}

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
        if let Ok(c_dir) = CString::new(runtime_dir) {
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

        let thread = thread::spawn(move || {
            run_event_loop(cfg);
        });

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

// ---- Wayland Server State & Resource Data ----

struct WayverClientData;

impl ClientData for WayverClientData {
    fn initialized(&self, _client_id: ClientId) {}
    fn disconnected(&self, _client_id: ClientId, _reason: DisconnectReason) {}
}

struct ShmPoolInner {
    fd: OwnedFd,
    ptr: *mut u8,
    size: usize,
}

unsafe impl Send for ShmPoolInner {}

impl ShmPoolInner {
    fn new(fd: OwnedFd, size: usize) -> Result<Self, ()> {
        if size == 0 {
            return Err(());
        }
        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                size,
                libc::PROT_READ,
                libc::MAP_SHARED,
                fd.as_raw_fd(),
                0,
            )
        };
        if ptr == libc::MAP_FAILED {
            return Err(());
        }
        Ok(Self {
            fd,
            ptr: ptr.cast(),
            size,
        })
    }

    fn resize(&mut self, new_size: usize) {
        if new_size <= self.size {
            return;
        }
        let new_ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                new_size,
                libc::PROT_READ,
                libc::MAP_SHARED,
                self.fd.as_raw_fd(),
                0,
            )
        };
        if new_ptr != libc::MAP_FAILED {
            unsafe {
                libc::munmap(self.ptr.cast(), self.size);
            }
            self.ptr = new_ptr.cast();
            self.size = new_size;
        }
    }
}

impl Drop for ShmPoolInner {
    fn drop(&mut self) {
        if !self.ptr.is_null() && self.size > 0 {
            unsafe {
                libc::munmap(self.ptr.cast(), self.size);
            }
        }
    }
}

struct ShmBufferData {
    pool: Arc<Mutex<ShmPoolInner>>,
    offset: i32,
    width: i32,
    height: i32,
    stride: i32,
    format: wl_shm::Format,
}

#[derive(Default)]
struct SurfaceData {
    pending_buffer: Option<wl_buffer::WlBuffer>,
    pending_damage: Option<DirtyRect>,
    frame_cb: Option<wl_callback::WlCallback>,
    xdg_surface: Option<xdg_surface::XdgSurface>,
    xdg_toplevel: Option<xdg_toplevel::XdgToplevel>,
    is_subsurface: bool,
    is_toplevel: bool,
    configured: bool,
    reconfigured: bool,
    output_entered: bool,
    buffer_scale: u32,
}

fn union_damage(cur: Option<DirtyRect>, x: i32, y: i32, w: i32, h: i32) -> Option<DirtyRect> {
    if w <= 0 || h <= 0 {
        return cur;
    }
    let rx = x.max(0) as u32;
    let ry = y.max(0) as u32;
    let rw = w as u32;
    let rh = h as u32;
    Some(match cur {
        None => DirtyRect {
            x: rx,
            y: ry,
            width: rw,
            height: rh,
        },
        Some(c) => {
            let x1 = c.x.min(rx);
            let y1 = c.y.min(ry);
            let x2 = c.x.saturating_add(c.width).max(rx.saturating_add(rw));
            let y2 = c.y.saturating_add(c.height).max(ry.saturating_add(rh));
            DirtyRect {
                x: x1,
                y: y1,
                width: x2 - x1,
                height: y2 - y1,
            }
        }
    })
}

struct XdgSurfaceData {
    wl_surface: wl_surface::WlSurface,
}

struct CompositorState {
    presenter: Arc<VkPresenter>,
    runtime_dir: String,
    start_time: Instant,
    serial_counter: u32,
    output_w: u32,
    output_h: u32,
    metrics: Arc<SharedMetrics>,
    surfaces: HashMap<ObjectId, SurfaceData>,
    buffers: HashMap<ObjectId, ShmBufferData>,
    output_resource: Option<wl_output::WlOutput>,
    active_surface: Option<wl_surface::WlSurface>,
    touch_resource: Option<wl_touch::WlTouch>,
    pointer_resource: Option<wl_pointer::WlPointer>,
    keyboard_resource: Option<wl_keyboard::WlKeyboard>,
    pointer_entered: bool,
    keyboard_focused: bool,
    last_mods: u32,
}

impl CompositorState {
    fn next_serial(&mut self) -> u32 {
        self.serial_counter = self.serial_counter.wrapping_add(1);
        self.serial_counter
    }

    fn now_ms(&self) -> u32 {
        self.start_time.elapsed().as_millis() as u32
    }

    fn send_toplevel_configure(&mut self, surf_id: &ObjectId) {
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

    fn dispatch_key(&mut self, evdev_key: u32, pressed: bool, mods_depressed: u32) {
        let (Some(kbd), Some(active)) =
            (self.keyboard_resource.clone(), self.active_surface.clone())
        else {
            return;
        };
        if !self.keyboard_focused {
            let serial = self.next_serial();
            kbd.enter(serial, &active, Vec::new());
            self.keyboard_focused = true;
        }
        if mods_depressed != self.last_mods {
            let serial = self.next_serial();
            kbd.modifiers(serial, mods_depressed, 0, 0, 0);
            self.last_mods = mods_depressed;
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
                "LinuxDesktop".into(),
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
        let id = active.id();
        if self.surfaces.get(&id).is_some_and(|s| s.is_toplevel) {
            self.send_toplevel_configure(&id);
        }
    }
}

struct EventLoopConfig {
    listener: ListeningSocket,
    wakeup_read: OwnedFd,
    running: Arc<AtomicBool>,
    control: Arc<Mutex<SharedControl>>,
    metrics: Arc<SharedMetrics>,
    presenter: Arc<VkPresenter>,
    runtime_dir: String,
    socket_path: String,
}

fn run_event_loop(cfg: EventLoopConfig) {
    let EventLoopConfig {
        listener,
        wakeup_read,
        running,
        control,
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
        let c = control.lock().unwrap();
        (c.output_w, c.output_h)
    };

    let mut state = CompositorState {
        presenter,
        runtime_dir,
        start_time: Instant::now(),
        serial_counter: 1,
        output_w: init_w,
        output_h: init_h,
        metrics,
        surfaces: HashMap::new(),
        buffers: HashMap::new(),
        output_resource: None,
        active_surface: None,
        touch_resource: None,
        pointer_resource: None,
        keyboard_resource: None,
        pointer_entered: false,
        keyboard_focused: false,
        last_mods: 0,
    };

    info!("Wayland event loop started on {socket_path}");

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

            let (do_reconf, events) = {
                let mut ctrl = control.lock().unwrap();
                state.output_w = ctrl.output_w;
                state.output_h = ctrl.output_h;
                let reconf = ctrl.pending_reconfigure;
                ctrl.pending_reconfigure = false;
                let evs: Vec<InputEvent> = ctrl.input_queue.drain(..).collect();
                (reconf, evs)
            };

            for ev in events {
                match ev {
                    InputEvent::Touch { action, id, x, y } => {
                        state.dispatch_touch(action, id, x, y);
                    }
                    InputEvent::Key {
                        evdev_key,
                        pressed,
                        mods,
                    } => {
                        state.dispatch_key(evdev_key, pressed, mods);
                    }
                }
            }
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

// ---- wl_compositor, wl_surface, wl_region ----

impl GlobalDispatch<wl_compositor::WlCompositor, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<wl_compositor::WlCompositor>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<wl_compositor::WlCompositor, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_compositor::WlCompositor,
        request: wl_compositor::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_compositor::Request::CreateSurface { id } => {
                let surf = data_init.init(id, ());
                state.surfaces.insert(
                    surf.id(),
                    SurfaceData {
                        buffer_scale: 1,
                        ..Default::default()
                    },
                );
            }
            wl_compositor::Request::CreateRegion { id } => {
                data_init.init(id, ());
            }
            _ => {}
        }
    }
}

delegate_noop!(wl_region::WlRegion);
delegate_noop!(wl_callback::WlCallback);

impl Dispatch<wl_surface::WlSurface, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        resource: &wl_surface::WlSurface,
        request: wl_surface::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        let surf_id = resource.id();
        match request {
            wl_surface::Request::Destroy => {
                state.surfaces.remove(&surf_id);
                if state
                    .active_surface
                    .as_ref()
                    .is_some_and(|s| s.id() == surf_id)
                {
                    state.active_surface = None;
                }
            }
            wl_surface::Request::Attach { buffer, .. } => {
                if let Some(st) = state.surfaces.get_mut(&surf_id) {
                    st.pending_buffer = buffer;
                }
            }
            wl_surface::Request::Damage {
                x,
                y,
                width,
                height,
            } => {
                if let Some(st) = state.surfaces.get_mut(&surf_id) {
                    let s = st.buffer_scale.max(1) as i32;
                    st.pending_damage = union_damage(
                        st.pending_damage,
                        x.saturating_mul(s),
                        y.saturating_mul(s),
                        width.saturating_mul(s),
                        height.saturating_mul(s),
                    );
                }
            }
            wl_surface::Request::DamageBuffer {
                x,
                y,
                width,
                height,
            } => {
                if let Some(st) = state.surfaces.get_mut(&surf_id) {
                    st.pending_damage = union_damage(st.pending_damage, x, y, width, height);
                }
            }
            wl_surface::Request::Frame { callback } => {
                let cb = data_init.init(callback, ());
                if let Some(st) = state.surfaces.get_mut(&surf_id) {
                    st.frame_cb = Some(cb);
                }
            }
            wl_surface::Request::SetBufferScale { scale } => {
                if let Some(st) = state.surfaces.get_mut(&surf_id) {
                    st.buffer_scale = if scale > 0 { scale as u32 } else { 1 };
                }
            }
            wl_surface::Request::Commit => {
                handle_surface_commit(state, resource);
            }
            _ => {}
        }
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &wl_surface::WlSurface,
        _data: &(),
    ) {
        let surf_id = resource.id();
        state.surfaces.remove(&surf_id);
        if state
            .active_surface
            .as_ref()
            .is_some_and(|s| s.id() == surf_id)
        {
            state.active_surface = None;
        }
    }
}

fn handle_surface_commit(state: &mut CompositorState, resource: &wl_surface::WlSurface) {
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
                        state.presenter.present_pixels_damaged(
                            pixels,
                            w,
                            h,
                            bdata.stride as u32,
                            is_bgra,
                            pending_damage,
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

    if let Some(cb) = frame_cb {
        cb.done(state.now_ms());
    }
}

// ---- wl_shm, wl_shm_pool, wl_buffer ----

impl GlobalDispatch<wl_shm::WlShm, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<wl_shm::WlShm>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let shm = data_init.init(resource, ());
        shm.format(wl_shm::Format::Argb8888);
        shm.format(wl_shm::Format::Xrgb8888);
    }
}

impl Dispatch<wl_shm::WlShm, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_shm::WlShm,
        request: wl_shm::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_shm::Request::CreatePool { id, fd, size } = request {
            if let Ok(pool) = ShmPoolInner::new(fd, size.max(0) as usize) {
                data_init.init(id, Arc::new(Mutex::new(pool)));
            }
        }
    }
}

impl Dispatch<wl_shm_pool::WlShmPool, Arc<Mutex<ShmPoolInner>>> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_shm_pool::WlShmPool,
        request: wl_shm_pool::Request,
        data: &Arc<Mutex<ShmPoolInner>>,
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_shm_pool::Request::CreateBuffer {
                id,
                offset,
                width,
                height,
                stride,
                format,
            } => {
                let buf = data_init.init(id, ());
                let fmt = match format {
                    WEnum::Value(f) => f,
                    WEnum::Unknown(_) => wl_shm::Format::Xrgb8888,
                };
                state.buffers.insert(
                    buf.id(),
                    ShmBufferData {
                        pool: Arc::clone(data),
                        offset,
                        width,
                        height,
                        stride,
                        format: fmt,
                    },
                );
            }
            wl_shm_pool::Request::Resize { size } if size > 0 => {
                data.lock().unwrap().resize(size as usize);
            }
            _ => {}
        }
    }
}

impl Dispatch<wl_buffer::WlBuffer, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        resource: &wl_buffer::WlBuffer,
        request: wl_buffer::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        if matches!(request, wl_buffer::Request::Destroy) {
            state.buffers.remove(&resource.id());
        }
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &wl_buffer::WlBuffer, _data: &()) {
        state.buffers.remove(&resource.id());
    }
}

// ---- wl_subcompositor, wl_subsurface ----

impl GlobalDispatch<wl_subcompositor::WlSubcompositor, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<wl_subcompositor::WlSubcompositor>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<wl_subcompositor::WlSubcompositor, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_subcompositor::WlSubcompositor,
        request: wl_subcompositor::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_subcompositor::Request::GetSubsurface { id, surface, .. } = request {
            if let Some(st) = state.surfaces.get_mut(&surface.id()) {
                st.is_subsurface = true;
            }
            data_init.init(id, ());
        }
    }
}

delegate_noop!(wl_subsurface::WlSubsurface);

// ---- xdg_shell ----

impl GlobalDispatch<xdg_wm_base::XdgWmBase, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<xdg_wm_base::XdgWmBase>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<xdg_wm_base::XdgWmBase, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &xdg_wm_base::XdgWmBase,
        request: xdg_wm_base::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_wm_base::Request::CreatePositioner { id } => {
                data_init.init(id, ());
            }
            xdg_wm_base::Request::GetXdgSurface { id, surface } => {
                data_init.init(
                    id,
                    XdgSurfaceData {
                        wl_surface: surface,
                    },
                );
            }
            _ => {}
        }
    }
}

delegate_noop!(xdg_positioner::XdgPositioner);
delegate_noop!(xdg_toplevel::XdgToplevel);

impl Dispatch<xdg_surface::XdgSurface, XdgSurfaceData> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        resource: &xdg_surface::XdgSurface,
        request: xdg_surface::Request,
        data: &XdgSurfaceData,
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        if let xdg_surface::Request::GetToplevel { id } = request {
            let toplevel = data_init.init(id, ());
            let wl_id = data.wl_surface.id();
            if let Some(st) = state.surfaces.get_mut(&wl_id) {
                st.is_toplevel = true;
                st.xdg_surface = Some(resource.clone());
                st.xdg_toplevel = Some(toplevel);
                state.send_toplevel_configure(&wl_id);
            }
            state.active_surface = Some(data.wl_surface.clone());
            state.pointer_entered = false;
            state.keyboard_focused = false;
        }
    }
}

// ---- wl_output ----

impl GlobalDispatch<wl_output::WlOutput, ()> for CompositorState {
    fn bind(
        state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<wl_output::WlOutput>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let out = data_init.init(resource, ());
        let w = if state.output_w > 0 {
            state.output_w as i32
        } else {
            1280
        };
        let h = if state.output_h > 0 {
            state.output_h as i32
        } else {
            800
        };
        out.geometry(
            0,
            0,
            w / 8,
            h / 8,
            wl_output::Subpixel::Unknown,
            "LinuxDesktop".into(),
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
        state.output_resource = Some(out);
    }
}

impl Dispatch<wl_output::WlOutput, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_output::WlOutput,
        _request: wl_output::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &wl_output::WlOutput, _data: &()) {
        if state
            .output_resource
            .as_ref()
            .is_some_and(|o| o.id() == resource.id())
        {
            state.output_resource = None;
        }
    }
}

// ---- wl_seat, wl_pointer, wl_keyboard, wl_touch ----

impl GlobalDispatch<wl_seat::WlSeat, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<wl_seat::WlSeat>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let seat = data_init.init(resource, ());
        seat.capabilities(
            wl_seat::Capability::Touch
                | wl_seat::Capability::Pointer
                | wl_seat::Capability::Keyboard,
        );
        if seat.version() >= 2 {
            seat.name("seat0".into());
        }
    }
}

impl Dispatch<wl_seat::WlSeat, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_seat::WlSeat,
        request: wl_seat::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_seat::Request::GetPointer { id } => {
                let ptr = data_init.init(id, ());
                state.pointer_resource = Some(ptr);
                state.pointer_entered = false;
            }
            wl_seat::Request::GetKeyboard { id } => {
                let kbd = data_init.init(id, ());
                let kpath = Path::new(&state.runtime_dir).join("keymap.xkb");
                if let Ok(mut file) = OpenOptions::new()
                    .read(true)
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .open(&kpath)
                {
                    let _ = fs::remove_file(&kpath);
                    if file.write_all(MINIMAL_XKB_KEYMAP).is_ok() {
                        unsafe {
                            libc::lseek(file.as_raw_fd(), 0, libc::SEEK_SET);
                        }
                        kbd.keymap(
                            wl_keyboard::KeymapFormat::XkbV1,
                            file.as_fd(),
                            MINIMAL_XKB_KEYMAP.len() as u32,
                        );
                    }
                }
                if kbd.version() >= 4 {
                    kbd.repeat_info(25, 400);
                }
                if let Some(active) = state.active_surface.clone() {
                    let serial = state.next_serial();
                    kbd.enter(serial, &active, Vec::new());
                    state.keyboard_focused = true;
                } else {
                    state.keyboard_focused = false;
                }
                state.keyboard_resource = Some(kbd);
            }
            wl_seat::Request::GetTouch { id } => {
                let touch = data_init.init(id, ());
                state.touch_resource = Some(touch);
            }
            _ => {}
        }
    }
}

impl Dispatch<wl_pointer::WlPointer, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_pointer::WlPointer,
        _request: wl_pointer::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &wl_pointer::WlPointer,
        _data: &(),
    ) {
        if state
            .pointer_resource
            .as_ref()
            .is_some_and(|p| p.id() == resource.id())
        {
            state.pointer_resource = None;
            state.pointer_entered = false;
        }
    }
}

impl Dispatch<wl_keyboard::WlKeyboard, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_keyboard::WlKeyboard,
        _request: wl_keyboard::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(
        state: &mut Self,
        _client: ClientId,
        resource: &wl_keyboard::WlKeyboard,
        _data: &(),
    ) {
        if state
            .keyboard_resource
            .as_ref()
            .is_some_and(|k| k.id() == resource.id())
        {
            state.keyboard_resource = None;
            state.keyboard_focused = false;
        }
    }
}

impl Dispatch<wl_touch::WlTouch, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_touch::WlTouch,
        _request: wl_touch::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(state: &mut Self, _client: ClientId, resource: &wl_touch::WlTouch, _data: &()) {
        if state
            .touch_resource
            .as_ref()
            .is_some_and(|t| t.id() == resource.id())
        {
            state.touch_resource = None;
        }
    }
}

// ---- wl_data_device_manager ----

impl GlobalDispatch<wl_data_device_manager::WlDataDeviceManager, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _handle: &DisplayHandle,
        _client: &wayland_server::Client,
        resource: New<wl_data_device_manager::WlDataDeviceManager>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<wl_data_device_manager::WlDataDeviceManager, ()> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &wayland_server::Client,
        _resource: &wl_data_device_manager::WlDataDeviceManager,
        request: wl_data_device_manager::Request,
        _data: &(),
        _dhandle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device_manager::Request::CreateDataSource { id } => {
                data_init.init(id, ());
            }
            wl_data_device_manager::Request::GetDataDevice { id, .. } => {
                data_init.init(id, ());
            }
            _ => {}
        }
    }
}

delegate_noop!(wl_data_source::WlDataSource);
delegate_noop!(wl_data_device::WlDataDevice);
