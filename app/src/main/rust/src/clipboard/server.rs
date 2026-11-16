//! Two-way Wayland clipboard (`wl_data_device_manager`) implementation.

use std::fs::File;
use std::io::{Read, Write};
use std::os::fd::{AsFd, FromRawFd, OwnedFd};
use std::sync::{Arc, Mutex};
use std::thread;

use log::{info, warn};
use wayland_server::protocol::{
    wl_data_device, wl_data_device_manager, wl_data_offer, wl_data_source,
};
use wayland_server::{Client, DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource};

use super::ClipboardChannel;
use crate::wl_server::state::CompositorState;

const TEXT_MIME_TYPES: &[&str] = &["text/plain;charset=utf-8", "text/plain", "UTF8_STRING"];
const MAX_CLIPBOARD_BYTES: usize = 1_048_576;

pub(crate) type DataSourceState = Arc<Mutex<Vec<String>>>;

impl CompositorState {
    /// Pushes `self.android_clipboard` as a fresh `wl_data_offer` to all active `wl_data_device`s.
    pub(crate) fn send_android_selection(&mut self, dh: &DisplayHandle) {
        self.data_devices.retain(|d| d.is_alive());
        let Some(text) = self.android_clipboard.clone() else {
            return;
        };
        info!(
            "clipboard: offering {} bytes from Android to {} data_device(s)",
            text.len(),
            self.data_devices.len()
        );
        for device in &self.data_devices {
            self.send_selection_to_device(dh, device, &text);
        }
    }

    pub(crate) fn send_android_selection_if_owned(&mut self, dh: &DisplayHandle) {
        if !self.guest_owns_selection && self.android_clipboard.is_some() {
            self.send_android_selection(dh);
        }
    }

    pub(super) fn send_selection_to_device(
        &self,
        dh: &DisplayHandle,
        device: &wl_data_device::WlDataDevice,
        text: &str,
    ) {
        let Some(client) = device.client() else {
            return;
        };
        let Ok(offer) = client
            .create_resource::<wl_data_offer::WlDataOffer, String, CompositorState>(
                dh,
                device.version().min(3),
                text.to_string(),
            )
        else {
            return;
        };
        device.data_offer(&offer);
        for mime in TEXT_MIME_TYPES {
            offer.offer((*mime).to_string());
        }
        device.selection(Some(&offer));
    }
}

impl GlobalDispatch<wl_data_device_manager::WlDataDeviceManager, ()> for CompositorState {
    fn bind(
        _state: &mut Self,
        _dh: &DisplayHandle,
        _client: &Client,
        resource: New<wl_data_device_manager::WlDataDeviceManager>,
        _global_data: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        data_init.init(resource, ());
    }
}

impl Dispatch<wl_data_device_manager::WlDataDeviceManager, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &wl_data_device_manager::WlDataDeviceManager,
        request: wl_data_device_manager::Request,
        _data: &(),
        dh: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device_manager::Request::CreateDataSource { id } => {
                data_init.init(id, Arc::new(Mutex::new(Vec::new())));
            }
            wl_data_device_manager::Request::GetDataDevice { id, .. } => {
                let device = data_init.init(id, ());
                state.data_devices.retain(|d| d.is_alive());
                state.data_devices.push(device.clone());
                if !state.guest_owns_selection {
                    if let Some(text) = state.android_clipboard.clone() {
                        state.send_selection_to_device(dh, &device, &text);
                    }
                }
            }
            _ => {}
        }
    }
}

impl Dispatch<wl_data_source::WlDataSource, DataSourceState> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &wl_data_source::WlDataSource,
        request: wl_data_source::Request,
        data: &DataSourceState,
        _dh: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_data_source::Request::Offer { mime_type } = request {
            if let Ok(mut mimes) = data.lock() {
                if !mimes.contains(&mime_type) {
                    mimes.push(mime_type);
                }
            }
        }
    }
}

impl Dispatch<wl_data_offer::WlDataOffer, String> for CompositorState {
    fn request(
        _state: &mut Self,
        _client: &Client,
        _resource: &wl_data_offer::WlDataOffer,
        request: wl_data_offer::Request,
        data: &String,
        _dh: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_data_offer::Request::Receive { mime_type, fd } = request {
            info!(
                "clipboard: guest receive(mime={mime_type}, {} bytes)",
                data.len()
            );
            if TEXT_MIME_TYPES.contains(&mime_type.as_str()) || mime_type.starts_with("text/") {
                write_clipboard_to_fd(data.clone(), fd);
            }
        }
    }
}

impl Dispatch<wl_data_device::WlDataDevice, ()> for CompositorState {
    fn request(
        state: &mut Self,
        _client: &Client,
        _resource: &wl_data_device::WlDataDevice,
        request: wl_data_device::Request,
        _data: &(),
        _dh: &DisplayHandle,
        _data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_data_device::Request::SetSelection { source, .. } = request {
            match source {
                Some(src) => {
                    state.guest_owns_selection = true;
                    let offered = src
                        .data::<DataSourceState>()
                        .and_then(|d| d.lock().ok().map(|v| v.clone()))
                        .unwrap_or_default();
                    if let Some(mime) = TEXT_MIME_TYPES
                        .iter()
                        .copied()
                        .find(|m| offered.iter().any(|o| o == m))
                    {
                        info!("clipboard: guest set_selection(mime={mime})");
                        read_source_to_clipboard(&src, mime, Arc::clone(&state.clipboard));
                    } else {
                        info!("clipboard: guest set_selection ignored (offered={offered:?})");
                    }
                }
                None => {
                    state.guest_owns_selection = false;
                }
            }
        }
    }
}

fn read_source_to_clipboard(
    src: &wl_data_source::WlDataSource,
    mime: &str,
    clipboard: Arc<ClipboardChannel>,
) {
    let mut fds = [0i32; 2];
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        warn!("clipboard pipe() failed");
        return;
    }
    let read_fd = unsafe { OwnedFd::from_raw_fd(fds[0]) };
    let write_fd = unsafe { OwnedFd::from_raw_fd(fds[1]) };

    src.send(mime.to_string(), write_fd.as_fd());
    drop(write_fd);

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
            info!("clipboard: read {} bytes from guest", buf.len());
            let text = String::from_utf8_lossy(&buf).into_owned();
            clipboard.push_from_guest(text);
        }
    });
}

fn write_clipboard_to_fd(text: String, fd: OwnedFd) {
    thread::spawn(move || {
        let mut file = File::from(fd);
        let _ = file.write_all(text.as_bytes());
    });
}
