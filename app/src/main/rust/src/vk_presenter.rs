use std::ffi::CStr;
use std::sync::Mutex;

use ash::vk;
use log::{error, info};

use crate::vk_loader::VkLoader;

const MAX_SWAPCHAIN_IMAGES: usize = 8;
// Double-buffered CPU/GPU frame slots: lets the CPU prepare frame N+1 while the GPU finishes frame N.
const FRAMES_IN_FLIGHT: usize = 2;

/// Bounding rectangle of damaged pixels for partial staging-to-swapchain uploads.
#[derive(Clone, Copy, Debug)]
pub struct DirtyRect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

pub struct VkPresenter {
    inner: Mutex<PresenterInner>,
}

struct StagingBuffer {
    buf: vk::Buffer,
    mem: vk::DeviceMemory,
    size: vk::DeviceSize,
    mapped_ptr: *mut u8,
}

unsafe impl Send for StagingBuffer {}

struct FrameSlot {
    cmd_buf: vk::CommandBuffer,
    sem_avail: vk::Semaphore,
    sem_done: vk::Semaphore,
    fence: vk::Fence,
    staging: Option<StagingBuffer>,
}

struct UploadImage {
    img: vk::Image,
    mem: vk::DeviceMemory,
    width: u32,
    height: u32,
    format: vk::Format,
    initialized: bool,
}

struct SwapchainState {
    swapchain: vk::SwapchainKHR,
    _format: vk::Format,
    extent: vk::Extent2D,
    images: Vec<vk::Image>,
}

struct VulkanContext {
    _loader: VkLoader,
    _entry: ash::Entry,
    instance: ash::Instance,
    surface_loader: ash::khr::surface::Instance,
    android_surface_loader: ash::khr::android_surface::Instance,
    swapchain_loader: ash::khr::swapchain::Device,
    phys_device: vk::PhysicalDevice,
    mem_props: vk::PhysicalDeviceMemoryProperties,
    device: ash::Device,
    queue: vk::Queue,
    queue_family: u32,
    cmd_pool: vk::CommandPool,
    frames: [FrameSlot; FRAMES_IN_FLIGHT],
    frame_idx: usize,
    surface: vk::SurfaceKHR,
    swapchain: Option<SwapchainState>,
    upload_img: Option<UploadImage>,
}

struct PresenterInner {
    vk: Option<VulkanContext>,
    info: String,
}

impl VkPresenter {
    pub fn new(custom_driver_path: Option<&str>) -> Self {
        match init_vulkan(custom_driver_path) {
            Ok((ctx, info_str)) => {
                info!("{info_str}");
                Self {
                    inner: Mutex::new(PresenterInner {
                        vk: Some(ctx),
                        info: info_str,
                    }),
                }
            }
            Err(err) => {
                error!("{err}");
                Self {
                    inner: Mutex::new(PresenterInner {
                        vk: None,
                        info: err,
                    }),
                }
            }
        }
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
            destroy_surface(vk);
            if window.is_null() || width <= 0 || height <= 0 {
                return true;
            }

            let asci = vk::AndroidSurfaceCreateInfoKHR::default().window(window.cast());
            let surface = match vk
                .android_surface_loader
                .create_android_surface(&asci, None)
            {
                Ok(s) => s,
                Err(e) => {
                    error!("vkCreateAndroidSurfaceKHR failed: {e:?}");
                    return false;
                }
            };
            vk.surface = surface;

            let supported = vk
                .surface_loader
                .get_physical_device_surface_support(vk.phys_device, vk.queue_family, surface)
                .unwrap_or(false);
            if !supported {
                error!(
                    "Queue family {} does not support Android surface",
                    vk.queue_family
                );
                return false;
            }

            let Ok(caps) = vk
                .surface_loader
                .get_physical_device_surface_capabilities(vk.phys_device, surface)
            else {
                return false;
            };
            let Ok(fmts) = vk
                .surface_loader
                .get_physical_device_surface_formats(vk.phys_device, surface)
            else {
                return false;
            };
            if fmts.is_empty() {
                return false;
            }

            let chosen = fmts
                .iter()
                .copied()
                .find(|f| f.format == vk::Format::R8G8B8A8_UNORM)
                .unwrap_or(fmts[0]);

            let extent = if caps.current_extent.width != u32::MAX {
                caps.current_extent
            } else {
                vk::Extent2D {
                    width: width as u32,
                    height: height as u32,
                }
            };

            let mut min_images = caps.min_image_count + 1;
            if caps.max_image_count > 0 && min_images > caps.max_image_count {
                min_images = caps.max_image_count;
            }

            let swci = vk::SwapchainCreateInfoKHR::default()
                .surface(surface)
                .min_image_count(min_images)
                .image_format(chosen.format)
                .image_color_space(chosen.color_space)
                .image_extent(extent)
                .image_array_layers(1)
                .image_usage(
                    vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::COLOR_ATTACHMENT,
                )
                .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
                .pre_transform(vk::SurfaceTransformFlagsKHR::IDENTITY)
                .composite_alpha(vk::CompositeAlphaFlagsKHR::INHERIT)
                .present_mode(vk::PresentModeKHR::FIFO)
                .clipped(true);

            let swapchain = match vk.swapchain_loader.create_swapchain(&swci, None) {
                Ok(s) => s,
                Err(e) => {
                    error!("vkCreateSwapchainKHR failed: {e:?}");
                    return false;
                }
            };

            let mut images = vk
                .swapchain_loader
                .get_swapchain_images(swapchain)
                .unwrap_or_default();
            images.truncate(MAX_SWAPCHAIN_IMAGES);

            info!(
                "Swapchain ready: {}x{} ({} images, fmt={:?})",
                extent.width,
                extent.height,
                images.len(),
                chosen.format
            );

            vk.swapchain = Some(SwapchainState {
                swapchain,
                _format: chosen.format,
                extent,
                images,
            });
            true
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
            let _ = vk.device.wait_for_fences(&[fence], true, u64::MAX);
            let _ = vk.device.reset_fences(&[fence]);

            let img_idx = match vk.swapchain_loader.acquire_next_image(
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
            let _ = vk
                .device
                .reset_command_buffer(cmd_buf, vk::CommandBufferResetFlags::empty());
            let cbbi = vk::CommandBufferBeginInfo::default()
                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
            let _ = vk.device.begin_command_buffer(cmd_buf, &cbbi);

            let range = color_subresource_range();
            let to_clear = image_barrier(
                swap_img,
                vk::AccessFlags::empty(),
                vk::AccessFlags::TRANSFER_WRITE,
                vk::ImageLayout::UNDEFINED,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                range,
            );
            vk.device.cmd_pipeline_barrier(
                cmd_buf,
                vk::PipelineStageFlags::TOP_OF_PIPE,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[to_clear],
            );

            let color = vk::ClearColorValue {
                float32: [r, g, b, a],
            };
            vk.device.cmd_clear_color_image(
                cmd_buf,
                swap_img,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                &color,
                &[range],
            );

            let to_present = image_barrier(
                swap_img,
                vk::AccessFlags::TRANSFER_WRITE,
                vk::AccessFlags::empty(),
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                vk::ImageLayout::PRESENT_SRC_KHR,
                range,
            );
            vk.device.cmd_pipeline_barrier(
                cmd_buf,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[to_present],
            );
            let _ = vk.device.end_command_buffer(cmd_buf);

            if !submit_and_present(
                &vk.device,
                &vk.swapchain_loader,
                vk.queue,
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
            let _ = vk.device.wait_for_fences(&[fence], true, u64::MAX);
            let _ = vk.device.reset_fences(&[fence]);

            let src_fmt = if is_bgra {
                vk::Format::B8G8R8A8_UNORM
            } else {
                vk::Format::R8G8B8A8_UNORM
            };

            if !ensure_upload_image(vk, width, height, src_fmt) {
                error!("ensure_upload_image failed for {width}x{height}");
                return false;
            }

            let upload_was_initialized = vk.upload_img.as_ref().unwrap().initialized;
            let rect = match dirty {
                Some(d) if upload_was_initialized && d.width > 0 && d.height > 0 => {
                    let x = d.x.min(width.saturating_sub(1));
                    let y = d.y.min(height.saturating_sub(1));
                    let w = d.width.min(width - x).max(1);
                    let h = d.height.min(height - y).max(1);
                    DirtyRect {
                        x,
                        y,
                        width: w,
                        height: h,
                    }
                }
                _ => DirtyRect {
                    x: 0,
                    y: 0,
                    width,
                    height,
                },
            };

            let copy_bytes = (rect.width as vk::DeviceSize) * (rect.height as vk::DeviceSize) * 4;
            let full_bytes = (width as vk::DeviceSize) * (height as vk::DeviceSize) * 4;
            if !ensure_staging_buffer(
                &vk.device,
                &vk.mem_props,
                &mut vk.frames[slot_idx].staging,
                full_bytes.max(copy_bytes),
            ) {
                error!("ensure_staging_buffer failed");
                return false;
            }

            let staging = vk.frames[slot_idx].staging.as_ref().unwrap();
            let dst = std::slice::from_raw_parts_mut(staging.mapped_ptr, copy_bytes as usize);
            let stride = stride_bytes as usize;
            let rect_row_bytes = (rect.width as usize) * 4;
            let full_row_bytes = (width as usize) * 4;

            if rect.x == 0
                && rect.y == 0
                && rect.width == width
                && rect.height == height
                && stride == full_row_bytes
                && pixels.len() >= copy_bytes as usize
            {
                dst.copy_from_slice(&pixels[..copy_bytes as usize]);
            } else {
                let x_off = (rect.x as usize) * 4;
                for row in 0..rect.height as usize {
                    let src_off = (rect.y as usize + row) * stride + x_off;
                    let dst_off = row * rect_row_bytes;
                    if src_off + rect_row_bytes <= pixels.len() {
                        dst[dst_off..dst_off + rect_row_bytes]
                            .copy_from_slice(&pixels[src_off..src_off + rect_row_bytes]);
                    }
                }
            }

            let sw = vk.swapchain.as_ref().unwrap();
            let Ok((img_idx, _)) = vk.swapchain_loader.acquire_next_image(
                sw.swapchain,
                u64::MAX,
                sem_avail,
                vk::Fence::null(),
            ) else {
                return false;
            };

            let swap_img = sw.images[img_idx as usize];
            let extent = sw.extent;
            let upload_img = vk.upload_img.as_ref().unwrap().img;
            let staging_buf = staging.buf;

            let _ = vk
                .device
                .reset_command_buffer(cmd_buf, vk::CommandBufferResetFlags::empty());
            let cbbi = vk::CommandBufferBeginInfo::default()
                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
            let _ = vk.device.begin_command_buffer(cmd_buf, &cbbi);

            let range = color_subresource_range();
            let (old_upload_layout, src_access) = if upload_was_initialized {
                (
                    vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                    vk::AccessFlags::TRANSFER_READ,
                )
            } else {
                (vk::ImageLayout::UNDEFINED, vk::AccessFlags::empty())
            };
            let barriers_before = [
                image_barrier(
                    upload_img,
                    src_access,
                    vk::AccessFlags::TRANSFER_WRITE,
                    old_upload_layout,
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    range,
                ),
                image_barrier(
                    swap_img,
                    vk::AccessFlags::empty(),
                    vk::AccessFlags::TRANSFER_WRITE,
                    vk::ImageLayout::UNDEFINED,
                    vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                    range,
                ),
            ];
            vk.device.cmd_pipeline_barrier(
                cmd_buf,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &barriers_before,
            );

            let region = vk::BufferImageCopy::default()
                .buffer_offset(0)
                .buffer_row_length(0)
                .buffer_image_height(0)
                .image_subresource(vk::ImageSubresourceLayers {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    mip_level: 0,
                    base_array_layer: 0,
                    layer_count: 1,
                })
                .image_offset(vk::Offset3D {
                    x: rect.x as i32,
                    y: rect.y as i32,
                    z: 0,
                })
                .image_extent(vk::Extent3D {
                    width: rect.width,
                    height: rect.height,
                    depth: 1,
                });
            vk.device.cmd_copy_buffer_to_image(
                cmd_buf,
                staging_buf,
                upload_img,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                &[region],
            );

            let upload_to_src = image_barrier(
                upload_img,
                vk::AccessFlags::TRANSFER_WRITE,
                vk::AccessFlags::TRANSFER_READ,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                range,
            );
            vk.device.cmd_pipeline_barrier(
                cmd_buf,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::TRANSFER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[upload_to_src],
            );

            let blit = vk::ImageBlit::default()
                .src_subresource(vk::ImageSubresourceLayers {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    mip_level: 0,
                    base_array_layer: 0,
                    layer_count: 1,
                })
                .src_offsets([
                    vk::Offset3D { x: 0, y: 0, z: 0 },
                    vk::Offset3D {
                        x: width as i32,
                        y: height as i32,
                        z: 1,
                    },
                ])
                .dst_subresource(vk::ImageSubresourceLayers {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    mip_level: 0,
                    base_array_layer: 0,
                    layer_count: 1,
                })
                .dst_offsets([
                    vk::Offset3D { x: 0, y: 0, z: 0 },
                    vk::Offset3D {
                        x: extent.width as i32,
                        y: extent.height as i32,
                        z: 1,
                    },
                ]);
            let filter = if width == extent.width && height == extent.height {
                vk::Filter::NEAREST
            } else {
                vk::Filter::LINEAR
            };
            vk.device.cmd_blit_image(
                cmd_buf,
                upload_img,
                vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
                swap_img,
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                &[blit],
                filter,
            );

            let to_present = image_barrier(
                swap_img,
                vk::AccessFlags::TRANSFER_WRITE,
                vk::AccessFlags::empty(),
                vk::ImageLayout::TRANSFER_DST_OPTIMAL,
                vk::ImageLayout::PRESENT_SRC_KHR,
                range,
            );
            vk.device.cmd_pipeline_barrier(
                cmd_buf,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[to_present],
            );
            let _ = vk.device.end_command_buffer(cmd_buf);

            let ok = submit_and_present(
                &vk.device,
                &vk.swapchain_loader,
                vk.queue,
                cmd_buf,
                sem_avail,
                sem_done,
                fence,
                sw.swapchain,
                img_idx,
            );
            if ok {
                if let Some(u) = vk.upload_img.as_mut() {
                    u.initialized = true;
                }
                vk.frame_idx = (vk.frame_idx + 1) % FRAMES_IN_FLIGHT;
            }
            ok
        }
    }
}

impl Drop for VulkanContext {
    fn drop(&mut self) {
        unsafe {
            let _ = self.device.device_wait_idle();
            destroy_surface(self);
            destroy_upload_image(self);
            for slot in &mut self.frames {
                destroy_staging_buffer(&self.device, &mut slot.staging);
                self.device.destroy_fence(slot.fence, None);
                self.device.destroy_semaphore(slot.sem_avail, None);
                self.device.destroy_semaphore(slot.sem_done, None);
            }
            self.device.destroy_command_pool(self.cmd_pool, None);
            self.device.destroy_device(None);
            self.instance.destroy_instance(None);
        }
    }
}

fn init_vulkan(custom_driver_path: Option<&str>) -> Result<(VulkanContext, String), String> {
    let loader = VkLoader::open(custom_driver_path)
        .ok_or_else(|| "Failed to open Vulkan loader".to_string())?;
    let entry = unsafe {
        ash::Entry::from_static_fn(ash::StaticFn {
            get_instance_proc_addr: loader.get_instance_proc_addr,
        })
    };

    let app_name = c"LinuxDesktopMVP";
    let engine_name = c"LinuxDesktopCompositor";
    let app_info = vk::ApplicationInfo::default()
        .application_name(app_name)
        .application_version(vk::make_api_version(0, 0, 1, 0))
        .engine_name(engine_name)
        .engine_version(vk::make_api_version(0, 0, 1, 0))
        .api_version(vk::API_VERSION_1_0);

    let exts = [
        ash::khr::surface::NAME.as_ptr(),
        ash::khr::android_surface::NAME.as_ptr(),
    ];
    let ici = vk::InstanceCreateInfo::default()
        .application_info(&app_info)
        .enabled_extension_names(&exts);

    let instance = unsafe { entry.create_instance(&ici, None) }
        .map_err(|e| format!("vkCreateInstance failed: {e:?}"))?;

    let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
    let android_surface_loader = ash::khr::android_surface::Instance::new(&entry, &instance);

    let phys_devices = unsafe { instance.enumerate_physical_devices() }
        .map_err(|_| "No Vulkan physical devices found".to_string())?;
    let phys_device = *phys_devices
        .first()
        .ok_or_else(|| "No Vulkan physical devices found".to_string())?;

    let props = unsafe { instance.get_physical_device_properties(phys_device) };
    let mem_props = unsafe { instance.get_physical_device_memory_properties(phys_device) };
    let qfs = unsafe { instance.get_physical_device_queue_family_properties(phys_device) };
    let queue_family = qfs
        .iter()
        .position(|q| q.queue_flags.contains(vk::QueueFlags::GRAPHICS))
        .unwrap_or(0) as u32;

    let prios = [1.0f32];
    let qci = [vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family)
        .queue_priorities(&prios)];
    let dev_exts = [ash::khr::swapchain::NAME.as_ptr()];
    let dci = vk::DeviceCreateInfo::default()
        .queue_create_infos(&qci)
        .enabled_extension_names(&dev_exts);

    let device_name = unsafe { CStr::from_ptr(props.device_name.as_ptr()) }
        .to_string_lossy()
        .into_owned();
    let device = unsafe { instance.create_device(phys_device, &dci, None) }
        .map_err(|e| format!("vkCreateDevice failed: {e:?} ({device_name})"))?;

    let swapchain_loader = ash::khr::swapchain::Device::new(&instance, &device);
    let queue = unsafe { device.get_device_queue(queue_family, 0) };

    let cpci = vk::CommandPoolCreateInfo::default()
        .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER)
        .queue_family_index(queue_family);
    let cmd_pool = unsafe { device.create_command_pool(&cpci, None) }
        .map_err(|e| format!("vkCreateCommandPool failed: {e:?}"))?;

    let cbai = vk::CommandBufferAllocateInfo::default()
        .command_pool(cmd_pool)
        .level(vk::CommandBufferLevel::PRIMARY)
        .command_buffer_count(FRAMES_IN_FLIGHT as u32);
    let cmd_bufs = unsafe { device.allocate_command_buffers(&cbai) }
        .map_err(|e| format!("vkAllocateCommandBuffers failed: {e:?}"))?;

    let sci = vk::SemaphoreCreateInfo::default();
    let fci = vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);

    let mut frame_vec = Vec::with_capacity(FRAMES_IN_FLIGHT);
    for cmd_buf in cmd_bufs {
        let sem_avail = unsafe { device.create_semaphore(&sci, None) }
            .map_err(|e| format!("vkCreateSemaphore failed: {e:?}"))?;
        let sem_done = unsafe { device.create_semaphore(&sci, None) }
            .map_err(|e| format!("vkCreateSemaphore failed: {e:?}"))?;
        let fence = unsafe { device.create_fence(&fci, None) }
            .map_err(|e| format!("vkCreateFence failed: {e:?}"))?;
        frame_vec.push(FrameSlot {
            cmd_buf,
            sem_avail,
            sem_done,
            fence,
            staging: None,
        });
    }
    let frames: [FrameSlot; FRAMES_IN_FLIGHT] = frame_vec.try_into().ok().unwrap();

    let info_str = format!(
        "GPU: {} | Vulkan {}.{}.{} | Driver: {}",
        device_name,
        vk::api_version_major(props.api_version),
        vk::api_version_minor(props.api_version),
        vk::api_version_patch(props.api_version),
        if loader.is_custom_driver {
            "Custom"
        } else {
            "System"
        }
    );

    let ctx = VulkanContext {
        _loader: loader,
        _entry: entry,
        instance,
        surface_loader,
        android_surface_loader,
        swapchain_loader,
        phys_device,
        mem_props,
        device,
        queue,
        queue_family,
        cmd_pool,
        frames,
        frame_idx: 0,
        surface: vk::SurfaceKHR::null(),
        swapchain: None,
        upload_img: None,
    };
    Ok((ctx, info_str))
}

#[allow(clippy::too_many_arguments)]
unsafe fn submit_and_present(
    device: &ash::Device,
    swapchain_loader: &ash::khr::swapchain::Device,
    queue: vk::Queue,
    cmd_buf: vk::CommandBuffer,
    sem_avail: vk::Semaphore,
    sem_done: vk::Semaphore,
    fence: vk::Fence,
    swapchain: vk::SwapchainKHR,
    img_idx: u32,
) -> bool {
    let wait_stages = [vk::PipelineStageFlags::TRANSFER];
    let wait_sems = [sem_avail];
    let cmd_bufs = [cmd_buf];
    let sig_sems = [sem_done];
    let si = vk::SubmitInfo::default()
        .wait_semaphores(&wait_sems)
        .wait_dst_stage_mask(&wait_stages)
        .command_buffers(&cmd_bufs)
        .signal_semaphores(&sig_sems);
    if let Err(e) = device.queue_submit(queue, &[si], fence) {
        error!("vkQueueSubmit failed: {e:?}");
        return false;
    }

    let swapchains = [swapchain];
    let indices = [img_idx];
    let pi = vk::PresentInfoKHR::default()
        .wait_semaphores(&sig_sems)
        .swapchains(&swapchains)
        .image_indices(&indices);
    match swapchain_loader.queue_present(queue, &pi) {
        Ok(_) => true,
        Err(e) => {
            error!("vkQueuePresentKHR failed: {e:?}");
            false
        }
    }
}

unsafe fn destroy_swapchain(vk: &mut VulkanContext) {
    let _ = vk.device.device_wait_idle();
    if let Some(sw) = vk.swapchain.take() {
        vk.swapchain_loader.destroy_swapchain(sw.swapchain, None);
    }
}

unsafe fn destroy_surface(vk: &mut VulkanContext) {
    destroy_swapchain(vk);
    if vk.surface != vk::SurfaceKHR::null() {
        vk.surface_loader.destroy_surface(vk.surface, None);
        vk.surface = vk::SurfaceKHR::null();
    }
}

unsafe fn destroy_staging_buffer(device: &ash::Device, staging: &mut Option<StagingBuffer>) {
    if let Some(s) = staging.take() {
        if !s.mapped_ptr.is_null() {
            device.unmap_memory(s.mem);
        }
        device.destroy_buffer(s.buf, None);
        device.free_memory(s.mem, None);
    }
}

unsafe fn ensure_staging_buffer(
    device: &ash::Device,
    mem_props: &vk::PhysicalDeviceMemoryProperties,
    staging: &mut Option<StagingBuffer>,
    needed: vk::DeviceSize,
) -> bool {
    if let Some(s) = staging.as_ref() {
        if s.size >= needed {
            return true;
        }
    }
    destroy_staging_buffer(device, staging);

    let bci = vk::BufferCreateInfo::default()
        .size(needed)
        .usage(vk::BufferUsageFlags::TRANSFER_SRC)
        .sharing_mode(vk::SharingMode::EXCLUSIVE);
    let Ok(buf) = device.create_buffer(&bci, None) else {
        return false;
    };
    let breq = device.get_buffer_memory_requirements(buf);
    let Some(btype) = find_mem_type(
        mem_props,
        breq.memory_type_bits,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    ) else {
        device.destroy_buffer(buf, None);
        return false;
    };
    let bai = vk::MemoryAllocateInfo::default()
        .allocation_size(breq.size)
        .memory_type_index(btype);
    let Ok(mem) = device.allocate_memory(&bai, None) else {
        device.destroy_buffer(buf, None);
        return false;
    };
    if device.bind_buffer_memory(buf, mem, 0).is_err() {
        device.free_memory(mem, None);
        device.destroy_buffer(buf, None);
        return false;
    }
    let Ok(ptr) = device.map_memory(mem, 0, needed, vk::MemoryMapFlags::empty()) else {
        device.free_memory(mem, None);
        device.destroy_buffer(buf, None);
        return false;
    };

    *staging = Some(StagingBuffer {
        buf,
        mem,
        size: needed,
        mapped_ptr: ptr.cast(),
    });
    true
}

unsafe fn destroy_upload_image(vk: &mut VulkanContext) {
    if let Some(u) = vk.upload_img.take() {
        vk.device.destroy_image(u.img, None);
        vk.device.free_memory(u.mem, None);
    }
}

unsafe fn ensure_upload_image(vk: &mut VulkanContext, w: u32, h: u32, fmt: vk::Format) -> bool {
    if let Some(u) = vk.upload_img.as_ref() {
        if u.width == w && u.height == h && u.format == fmt {
            return true;
        }
    }

    let _ = vk.device.device_wait_idle();
    destroy_upload_image(vk);

    let ici = vk::ImageCreateInfo::default()
        .image_type(vk::ImageType::TYPE_2D)
        .format(fmt)
        .extent(vk::Extent3D {
            width: w,
            height: h,
            depth: 1,
        })
        .mip_levels(1)
        .array_layers(1)
        .samples(vk::SampleCountFlags::TYPE_1)
        .tiling(vk::ImageTiling::OPTIMAL)
        .usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::TRANSFER_SRC)
        .sharing_mode(vk::SharingMode::EXCLUSIVE)
        .initial_layout(vk::ImageLayout::UNDEFINED);
    let Ok(img) = vk.device.create_image(&ici, None) else {
        return false;
    };
    let ireq = vk.device.get_image_memory_requirements(img);
    let itype = find_mem_type(
        &vk.mem_props,
        ireq.memory_type_bits,
        vk::MemoryPropertyFlags::DEVICE_LOCAL,
    )
    .or_else(|| {
        find_mem_type(
            &vk.mem_props,
            ireq.memory_type_bits,
            vk::MemoryPropertyFlags::empty(),
        )
    });
    let Some(itype) = itype else {
        vk.device.destroy_image(img, None);
        return false;
    };
    let iai = vk::MemoryAllocateInfo::default()
        .allocation_size(ireq.size)
        .memory_type_index(itype);
    let Ok(mem) = vk.device.allocate_memory(&iai, None) else {
        vk.device.destroy_image(img, None);
        return false;
    };
    if vk.device.bind_image_memory(img, mem, 0).is_err() {
        vk.device.free_memory(mem, None);
        vk.device.destroy_image(img, None);
        return false;
    }

    vk.upload_img = Some(UploadImage {
        img,
        mem,
        width: w,
        height: h,
        format: fmt,
        initialized: false,
    });
    true
}

fn find_mem_type(
    mem_props: &vk::PhysicalDeviceMemoryProperties,
    type_bits: u32,
    req: vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..mem_props.memory_type_count).find(|&i| {
        (type_bits & (1u32 << i)) != 0
            && mem_props.memory_types[i as usize]
                .property_flags
                .contains(req)
    })
}

fn color_subresource_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: 1,
    }
}

fn image_barrier(
    image: vk::Image,
    src_access: vk::AccessFlags,
    dst_access: vk::AccessFlags,
    old_layout: vk::ImageLayout,
    new_layout: vk::ImageLayout,
    range: vk::ImageSubresourceRange,
) -> vk::ImageMemoryBarrier<'static> {
    vk::ImageMemoryBarrier::default()
        .src_access_mask(src_access)
        .dst_access_mask(dst_access)
        .old_layout(old_layout)
        .new_layout(new_layout)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .image(image)
        .subresource_range(range)
}
