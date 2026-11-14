//! Vulkan swapchain presenter for Android `ANativeWindow` surfaces.
//!
//! Organized into three focused modules:
//! - [`mod@self`]: public `VkPresenter` API, refresh pacing, and frame-slot orchestration
//! - `context`: Vulkan instance/device initialization, swapchain lifecycle, and staging/upload memory
//! - `commands`: staging copies, command buffer recording, barriers, and queue submission

mod commands;
mod context;

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use ash::vk;
use log::{error, info};

use commands::{
    copy_rect_to_staging, record_clear_commands, record_present_commands, submit_and_present,
};
use context::{
    create_swapchain, destroy_swapchain, ensure_staging_buffer, ensure_upload_image, init_vulkan,
    VulkanContext, FRAMES_IN_FLIGHT,
};

/// Bounding rectangle of damaged pixels for partial staging-to-swapchain uploads.
#[derive(Clone, Copy, Debug)]
pub struct DirtyRect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

/// Thread-safe Vulkan presenter that uploads Wayland SHM buffers to an Android swapchain.
///
/// Rendering calls serialize on `inner`, while `frame_interval_us` is lock-free so the
/// Wayland pacing loop can read the target frame period without contending on Vulkan work.
pub struct VkPresenter {
    inner: Mutex<PresenterInner>,
    frame_interval_us: AtomicU64,
}

struct PresenterInner {
    vk: Option<VulkanContext>,
    info: String,
}

impl VkPresenter {
    pub fn new() -> Self {
        match init_vulkan() {
            Ok((ctx, info_str)) => {
                info!("{info_str}");
                Self {
                    inner: Mutex::new(PresenterInner {
                        vk: Some(ctx),
                        info: info_str,
                    }),
                    frame_interval_us: AtomicU64::new(15_833),
                }
            }
            Err(err) => {
                error!("{err}");
                Self {
                    inner: Mutex::new(PresenterInner {
                        vk: None,
                        info: err,
                    }),
                    frame_interval_us: AtomicU64::new(15_833),
                }
            }
        }
    }

    pub fn set_refresh_rate(&self, hz: f32) {
        let clamped = hz.clamp(30.0, 240.0);
        // Pace at 95% of VSync period so frame_cb.done() lands just ahead of the next VSync.
        let us = (950_000.0 / clamped) as u64;
        self.frame_interval_us.store(us, Ordering::Relaxed);
        info!(
            "Display refresh rate set to {:.1} Hz (pace={} us)",
            clamped, us
        );
    }

    pub fn frame_interval(&self) -> Duration {
        Duration::from_micros(self.frame_interval_us.load(Ordering::Relaxed))
    }

    pub fn get_info(&self) -> String {
        self.inner.lock().unwrap().info.clone()
    }

    pub fn set_window(&self, window: *mut ndk_sys::ANativeWindow, width: i32, height: i32) -> bool {
        let mut guard = self.inner.lock().unwrap();
        let Some(vk) = guard.vk.as_mut() else {
            return false;
        };

        unsafe {
            // Destroy any previous swapchain/surface before creating a new one for the new window.
            destroy_swapchain(vk);
            if window.is_null() || width <= 0 || height <= 0 {
                return true;
            }
            create_swapchain(vk, window, width, height)
        }
    }

    pub fn draw_clear(&self, r: f32, g: f32, b: f32, a: f32) -> bool {
        let mut guard = self.inner.lock().unwrap();
        let Some(vk) = guard.vk.as_mut() else {
            return false;
        };
        let Some(sw) = vk.swapchain.as_ref() else {
            return false;
        };

        let slot_idx = vk.frame_idx;
        let fence = vk.frames[slot_idx].fence;
        let sem_avail = vk.frames[slot_idx].sem_avail;
        let sem_done = vk.frames[slot_idx].sem_done;
        let cmd_buf = vk.frames[slot_idx].cmd_buf;

        unsafe {
            let _ = vk.core.device.wait_for_fences(&[fence], true, u64::MAX);
            let _ = vk.core.device.reset_fences(&[fence]);

            let img_idx = match vk.core.swapchain_loader.acquire_next_image(
                sw.swapchain,
                u64::MAX,
                sem_avail,
                vk::Fence::null(),
            ) {
                Ok((idx, _)) => idx,
                Err(e) => {
                    error!("vkAcquireNextImageKHR failed: {e:?}");
                    return false;
                }
            };

            let swap_img = sw.images[img_idx as usize];
            record_clear_commands(&vk.core.device, cmd_buf, swap_img, r, g, b, a);

            if !submit_and_present(
                &vk.core.device,
                &vk.core.swapchain_loader,
                vk.core.queue,
                cmd_buf,
                sem_avail,
                sem_done,
                fence,
                sw.swapchain,
                img_idx,
            ) {
                return false;
            }
            vk.frame_idx = (vk.frame_idx + 1) % FRAMES_IN_FLIGHT;
            info!("Presented clear frame #{img_idx}");
            true
        }
    }

    pub fn present_pixels_damaged(
        &self,
        pixels: &[u8],
        width: u32,
        height: u32,
        stride_bytes: u32,
        is_bgra: bool,
        dirty: Option<DirtyRect>,
    ) -> bool {
        if pixels.is_empty() || width == 0 || height == 0 {
            return false;
        }
        let mut guard = self.inner.lock().unwrap();
        let Some(vk) = guard.vk.as_mut() else {
            return false;
        };
        if vk.swapchain.is_none() {
            return false;
        }

        let slot_idx = vk.frame_idx;
        let fence = vk.frames[slot_idx].fence;
        let sem_avail = vk.frames[slot_idx].sem_avail;
        let sem_done = vk.frames[slot_idx].sem_done;
        let cmd_buf = vk.frames[slot_idx].cmd_buf;

        unsafe {
            // Wait only on this frame slot's fence (allows CPU+GPU overlap across FRAMES_IN_FLIGHT).
            let _ = vk.core.device.wait_for_fences(&[fence], true, u64::MAX);
            let _ = vk.core.device.reset_fences(&[fence]);

            // 1. Choose GPU source format matching Wayland SHM pixels (GPU handles BGRA->RGBA swizzle).
            let src_fmt = if is_bgra {
                vk::Format::B8G8R8A8_UNORM
            } else {
                vk::Format::R8G8B8A8_UNORM
            };

            // 2. Ensure the persistent device-local upload image matches the source size and format.
            if !ensure_upload_image(vk, width, height, src_fmt) {
                error!("ensure_upload_image failed for {width}x{height}");
                return false;
            }

            // 3. Clamp the dirty region; force full-frame upload if the upload image is brand new.
            let upload_was_initialized = vk.upload_img.as_ref().unwrap().initialized;
            let rect = clamp_dirty_rect(dirty, upload_was_initialized, width, height);

            // 4. Ensure this slot's host-visible staging buffer is large enough and copy dirty pixels.
            let copy_bytes = (rect.width as vk::DeviceSize) * (rect.height as vk::DeviceSize) * 4;
            let full_bytes = (width as vk::DeviceSize) * (height as vk::DeviceSize) * 4;
            if !ensure_staging_buffer(
                &vk.core.device,
                &vk.core.mem_props,
                &mut vk.frames[slot_idx].staging,
                full_bytes.max(copy_bytes),
            ) {
                error!("ensure_staging_buffer failed");
                return false;
            }

            let staging = vk.frames[slot_idx].staging.as_ref().unwrap();
            let dst = std::slice::from_raw_parts_mut(staging.mapped_ptr, copy_bytes as usize);
            copy_rect_to_staging(dst, pixels, stride_bytes as usize, rect, width, height);

            // 5. Acquire an available swapchain image (GPU signals `sem_avail` when ready to write).
            let sw = vk.swapchain.as_ref().unwrap();
            let Ok((img_idx, _)) = vk.core.swapchain_loader.acquire_next_image(
                sw.swapchain,
                u64::MAX,
                sem_avail,
                vk::Fence::null(),
            ) else {
                return false;
            };

            let swap_img = sw.images[img_idx as usize];
            let upload_img = vk.upload_img.as_ref().unwrap().img;

            // 6. Record upload + copy/blit + present barriers, then submit and queue for display.
            record_present_commands(
                &vk.core.device,
                cmd_buf,
                staging.buf,
                upload_img,
                upload_was_initialized,
                swap_img,
                rect,
                width,
                height,
                src_fmt,
                sw.extent,
                sw.format,
            );

            let ok = submit_and_present(
                &vk.core.device,
                &vk.core.swapchain_loader,
                vk.core.queue,
                cmd_buf,
                sem_avail,
                sem_done,
                fence,
                sw.swapchain,
                img_idx,
            );
            if ok {
                // Upload image now holds a valid full frame, so subsequent frames can use partial rects.
                if let Some(u) = vk.upload_img.as_mut() {
                    u.initialized = true;
                }
                vk.frame_idx = (vk.frame_idx + 1) % FRAMES_IN_FLIGHT;
            }
            ok
        }
    }
}

/// Clamps `dirty` to the frame bounds, or falls back to the full frame if `upload_img` has not
/// yet been populated (first frame, resize, or pixel-format change).
fn clamp_dirty_rect(
    dirty: Option<DirtyRect>,
    upload_was_initialized: bool,
    width: u32,
    height: u32,
) -> DirtyRect {
    match dirty {
        Some(d) if upload_was_initialized && d.width > 0 && d.height > 0 => {
            let x = d.x.min(width.saturating_sub(1));
            let y = d.y.min(height.saturating_sub(1));
            DirtyRect {
                x,
                y,
                width: d.width.min(width - x).max(1),
                height: d.height.min(height - y).max(1),
            }
        }
        _ => DirtyRect {
            x: 0,
            y: 0,
            width,
            height,
        },
    }
}
