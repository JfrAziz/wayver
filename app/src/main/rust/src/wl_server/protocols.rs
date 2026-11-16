//! Wayland protocol `GlobalDispatch` and `Dispatch` implementations.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::fd::{AsFd, AsRawFd};
use std::path::Path;

use wayland_protocols::xdg::shell::server::{
    xdg_positioner, xdg_surface, xdg_toplevel, xdg_wm_base,
};
use wayland_server::backend::ClientId;
use wayland_server::protocol::{
    wl_callback, wl_compositor, wl_keyboard, wl_output, wl_pointer, wl_region, wl_seat,
    wl_subcompositor, wl_subsurface, wl_surface, wl_touch,
};
use wayland_server::{DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource};

use super::present_worker::union_damage;
use super::state::{handle_surface_commit, CompositorState, SurfaceData};

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

macro_rules! delegate_global {
    ($iface:ty) => {
        impl GlobalDispatch<$iface, ()> for CompositorState {
            fn bind(
                _state: &mut Self,
                _handle: &DisplayHandle,
                _client: &wayland_server::Client,
                resource: New<$iface>,
                _global_data: &(),
                data_init: &mut DataInit<'_, Self>,
            ) {
                data_init.init(resource, ());
            }
        }
    };
}

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

pub(super) struct XdgSurfaceData {
    wl_surface: wl_surface::WlSurface,
}

// ---- wl_compositor, wl_surface, wl_region ----

delegate_global!(wl_compositor::WlCompositor);
delegate_noop!(wl_region::WlRegion);
delegate_noop!(wl_callback::WlCallback);

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
            wl_surface::Request::Destroy => state.remove_surface(&surf_id),
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
        state.remove_surface(&resource.id());
    }
}

// ---- wl_subcompositor, wl_subsurface ----

delegate_global!(wl_subcompositor::WlSubcompositor);
delegate_noop!(wl_subsurface::WlSubsurface);

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

// ---- xdg_shell ----

delegate_global!(xdg_wm_base::XdgWmBase);
delegate_noop!(xdg_positioner::XdgPositioner);
delegate_noop!(xdg_toplevel::XdgToplevel);

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
        state.send_output_mode(&out);
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
