//! Wayland shared-memory (`wl_shm`, `wl_shm_pool`, `wl_buffer`) management.

use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::{Arc, Mutex};

use wayland_server::backend::ClientId;
use wayland_server::protocol::{wl_buffer, wl_shm, wl_shm_pool};
use wayland_server::{DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource, WEnum};

use super::state::CompositorState;

/// Read-only `mmap` view of a client's shared-memory pool fd.
pub(super) struct ShmPoolInner {
    fd: OwnedFd,
    pub ptr: *mut u8,
    pub size: usize,
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

pub(super) struct ShmBufferData {
    pub pool: Arc<Mutex<ShmPoolInner>>,
    pub offset: i32,
    pub width: i32,
    pub height: i32,
    pub stride: i32,
    pub format: wl_shm::Format,
}

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
