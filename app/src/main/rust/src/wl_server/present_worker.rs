//! Asynchronous, refresh-paced frame presenter with dirty-row coalescing.
//!
//! Decouples the Wayland event loop from Vulkan presentation so clients aren't
//! stalled on swapchain/fence waits, while pacing `wl_callback.done()` to the
//! display's active refresh rate.

use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Instant;

use wayland_server::protocol::wl_callback;
use wayland_server::DisplayHandle;

use crate::vk_presenter::{DirtyRect, VkPresenter};

struct PendingFrame {
    pixels: Vec<u8>,
    width: u32,
    height: u32,
    stride: u32,
    is_bgra: bool,
    dirty: Option<DirtyRect>,
    partial_only: bool,
    callbacks: Vec<wl_callback::WlCallback>,
}

struct PresentSlot {
    pending: Option<PendingFrame>,
    spare_buf: Vec<u8>,
    /// Geometry `(width, height, stride, is_bgra)` confirmed uploaded to `VkPresenter::upload_img`.
    upload_ready_geom: Option<(u32, u32, u32, bool)>,
    stop: bool,
}

pub(super) struct AsyncPresentWorker {
    shared: Arc<(Mutex<PresentSlot>, Condvar)>,
    thread: Option<JoinHandle<()>>,
}

/// Expands `cur` to include the damaged `(x, y, w, h)` rectangle.
pub(super) fn union_damage(
    cur: Option<DirtyRect>,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
) -> Option<DirtyRect> {
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

fn merge_dirty_opt(a: Option<DirtyRect>, b: Option<DirtyRect>) -> Option<DirtyRect> {
    match (a, b) {
        (Some(da), Some(db)) => {
            let x1 = da.x.min(db.x);
            let y1 = da.y.min(db.y);
            let x2 = (da.x + da.width).max(db.x + db.width);
            let y2 = (da.y + da.height).max(db.y + db.height);
            Some(DirtyRect {
                x: x1,
                y: y1,
                width: x2 - x1,
                height: y2 - y1,
            })
        }
        _ => None,
    }
}

fn copy_dirty_rows(dst: &mut [u8], src: &[u8], stride: u32, height: u32, rect: DirtyRect) {
    let stride_u = stride as usize;
    let y0 = (rect.y as usize).min(height as usize);
    let y1 = ((rect.y + rect.height) as usize).min(height as usize);
    let off0 = y0 * stride_u;
    let off1 = (y1 * stride_u).min(src.len()).min(dst.len());
    if off0 < off1 {
        dst[off0..off1].copy_from_slice(&src[off0..off1]);
    }
}

impl AsyncPresentWorker {
    pub fn new(presenter: Arc<VkPresenter>, mut dh: DisplayHandle, start_time: Instant) -> Self {
        let shared = Arc::new((
            Mutex::new(PresentSlot {
                pending: None,
                spare_buf: Vec::new(),
                upload_ready_geom: None,
                stop: false,
            }),
            Condvar::new(),
        ));
        let worker_shared = Arc::clone(&shared);
        let thread = thread::spawn(move || {
            let mut last_done = Instant::now() - presenter.frame_interval();

            loop {
                let mut frame = {
                    let (lock, cv) = &*worker_shared;
                    let mut guard = lock.lock().unwrap();
                    while guard.pending.is_none() && !guard.stop {
                        guard = cv.wait(guard).unwrap();
                    }
                    if guard.stop && guard.pending.is_none() {
                        break;
                    }
                    guard.pending.take().unwrap()
                };

                let ok = presenter.present_pixels_damaged(
                    &frame.pixels,
                    frame.width,
                    frame.height,
                    frame.stride,
                    frame.is_bgra,
                    frame.dirty,
                );

                // Pace frame callbacks to the display's active refresh rate.
                let min_interval = presenter.frame_interval();
                let since_last = last_done.elapsed();
                if since_last < min_interval {
                    thread::sleep(min_interval - since_last);
                }
                last_done = Instant::now();

                if !frame.callbacks.is_empty() {
                    let ms = start_time.elapsed().as_millis() as u32;
                    for cb in frame.callbacks.drain(..) {
                        cb.done(ms);
                    }
                    let _ = dh.flush_clients();
                }

                // Return the pixel buffer to `spare_buf` without clearing `len`, so
                // same-size partial-damage frames avoid reallocating or zero-filling.
                let (lock, _) = &*worker_shared;
                let mut guard = lock.lock().unwrap();
                guard.upload_ready_geom = if ok {
                    Some((frame.width, frame.height, frame.stride, frame.is_bgra))
                } else {
                    None
                };
                if guard.spare_buf.capacity() < frame.pixels.capacity() {
                    guard.spare_buf = frame.pixels;
                }
            }
        });
        Self {
            shared,
            thread: Some(thread),
        }
    }

    pub fn submit(
        &self,
        pixels: &[u8],
        width: u32,
        height: u32,
        stride: u32,
        is_bgra: bool,
        dirty: Option<DirtyRect>,
        frame_cb: Option<wl_callback::WlCallback>,
    ) {
        let (lock, cv) = &*self.shared;
        let mut guard = lock.lock().unwrap();
        if let Some(existing) = guard.pending.as_mut() {
            // Coalesce onto the queued frame if geometry matches, updating only dirty rows.
            let same_geom = existing.width == width
                && existing.height == height
                && existing.stride == stride
                && existing.is_bgra == is_bgra
                && existing.pixels.len() == pixels.len();
            if same_geom {
                if let Some(d) = dirty.filter(|_| !existing.partial_only) {
                    copy_dirty_rows(&mut existing.pixels, pixels, stride, height, d);
                } else {
                    existing.pixels.copy_from_slice(pixels);
                    existing.partial_only = false;
                }
                existing.dirty = merge_dirty_opt(existing.dirty, dirty);
            } else {
                existing.pixels.resize(pixels.len(), 0);
                existing.pixels.copy_from_slice(pixels);
                existing.width = width;
                existing.height = height;
                existing.stride = stride;
                existing.is_bgra = is_bgra;
                existing.dirty = None;
                existing.partial_only = false;
            }
            if let Some(cb) = frame_cb {
                existing.callbacks.push(cb);
            }
        } else {
            let can_partial = guard.upload_ready_geom == Some((width, height, stride, is_bgra));
            let mut buf = std::mem::take(&mut guard.spare_buf);
            let len_changed = buf.len() != pixels.len();
            if len_changed {
                buf.resize(pixels.len(), 0);
            }
            let partial_only = match dirty {
                Some(d) if can_partial && !len_changed => {
                    copy_dirty_rows(&mut buf, pixels, stride, height, d);
                    true
                }
                _ => {
                    buf.copy_from_slice(pixels);
                    false
                }
            };
            let mut callbacks = Vec::new();
            if let Some(cb) = frame_cb {
                callbacks.push(cb);
            }
            guard.pending = Some(PendingFrame {
                pixels: buf,
                width,
                height,
                stride,
                is_bgra,
                dirty,
                partial_only,
                callbacks,
            });
        }
        cv.notify_one();
    }
}

impl Drop for AsyncPresentWorker {
    fn drop(&mut self) {
        {
            let (lock, cv) = &*self.shared;
            let mut guard = lock.lock().unwrap();
            guard.stop = true;
            cv.notify_one();
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}
