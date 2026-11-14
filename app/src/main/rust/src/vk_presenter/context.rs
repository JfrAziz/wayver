//! Vulkan device, surface, swapchain, and GPU/staging memory lifecycle.

use std::ffi::CStr;

use ash::vk;
use log::{error, info};

pub(super) const MAX_SWAPCHAIN_IMAGES: usize = 8;
/// Double-buffered CPU/GPU frame slots: lets the CPU prepare frame N+1 while the GPU finishes frame N.
pub(super) const FRAMES_IN_FLIGHT: usize = 2;

/// Host-visible, persistently mapped buffer used to stage CPU pixels before GPU upload.
pub(super) struct StagingBuffer {
    pub buf: vk::Buffer,
    pub mem: vk::DeviceMemory,
    pub size: vk::DeviceSize,
    pub mapped_ptr: *mut u8,
}

unsafe impl Send for StagingBuffer {}

/// Per-in-flight-frame synchronization objects, command buffer, and reusable staging buffer.
pub(super) struct FrameSlot {
    pub cmd_buf: vk::CommandBuffer,
    pub sem_avail: vk::Semaphore,
    pub sem_done: vk::Semaphore,
    pub fence: vk::Fence,
    pub staging: Option<StagingBuffer>,
}

/// Persistent device-local 2D image that retains previous frame contents so dirty-rect
/// uploads only overwrite changed regions before copying/blitting to the swapchain.
pub(super) struct UploadImage {
    pub img: vk::Image,
    pub mem: vk::DeviceMemory,
    pub width: u32,
    pub height: u32,
    pub format: vk::Format,
    pub initialized: bool,
}

/// Active Android surface, swapchain, and its presentable images.
pub(super) struct SwapchainState {
    pub surface: vk::SurfaceKHR,
    pub swapchain: vk::SwapchainKHR,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    pub images: Vec<vk::Image>,
}

/// Immutable Vulkan instance/device handles initialized once at startup.
pub(super) struct VkCore {
    // Keep `_entry` alive until after `VulkanContext::drop` destroys device and instance.
    pub _entry: ash::Entry,
    pub instance: ash::Instance,
    pub surface_loader: ash::khr::surface::Instance,
    pub android_surface_loader: ash::khr::android_surface::Instance,
    pub swapchain_loader: ash::khr::swapchain::Device,
    pub phys_device: vk::PhysicalDevice,
    pub mem_props: vk::PhysicalDeviceMemoryProperties,
    pub device: ash::Device,
    pub queue: vk::Queue,
    pub queue_family: u32,
    pub cmd_pool: vk::CommandPool,
}

/// Top-level Vulkan state: immutable `core` plus per-frame, swapchain, and upload-image state.
pub(super) struct VulkanContext {
    pub core: VkCore,
    pub frames: [FrameSlot; FRAMES_IN_FLIGHT],
    pub frame_idx: usize,
    pub swapchain: Option<SwapchainState>,
    pub upload_img: Option<UploadImage>,
}

impl Drop for VulkanContext {
    fn drop(&mut self) {
        unsafe {
            let _ = self.core.device.device_wait_idle();
            destroy_swapchain(self);
            destroy_upload_image(self);
            for slot in &mut self.frames {
                destroy_staging_buffer(&self.core.device, &mut slot.staging);
                self.core.device.destroy_fence(slot.fence, None);
                self.core.device.destroy_semaphore(slot.sem_avail, None);
                self.core.device.destroy_semaphore(slot.sem_done, None);
            }
            self.core
                .device
                .destroy_command_pool(self.core.cmd_pool, None);
            self.core.device.destroy_device(None);
            self.core.instance.destroy_instance(None);
        }
    }
}

pub(super) fn init_vulkan() -> Result<(VulkanContext, String), String> {
    // 1. Load Android's system libvulkan.so via ash::Entry::load().
    let entry =
        unsafe { ash::Entry::load() }.map_err(|e| format!("Failed to load libvulkan.so: {e:?}"))?;

    // 2. Create VkInstance with Android surface extensions enabled.
    let app_name = c"Wayver";
    let engine_name = c"WayverCompositor";
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

    // 3. Pick the first physical GPU and a graphics-capable queue family.
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

    // 4. Create the logical device with KHR_swapchain and fetch the submission queue.
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

    // 5. Allocate a resettable command pool and double-buffered FrameSlots (fences start SIGNALED).
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
        "GPU: {} | Vulkan {}.{}.{}",
        device_name,
        vk::api_version_major(props.api_version),
        vk::api_version_minor(props.api_version),
        vk::api_version_patch(props.api_version),
    );

    let ctx = VulkanContext {
        core: VkCore {
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
        },
        frames,
        frame_idx: 0,
        swapchain: None,
        upload_img: None,
    };
    Ok((ctx, info_str))
}

/// Wraps an Android `ANativeWindow` in a `VkSurfaceKHR` and creates a swapchain for it.
pub(super) unsafe fn create_swapchain(
    vk: &mut VulkanContext,
    window: *mut ndk_sys::ANativeWindow,
    width: i32,
    height: i32,
) -> bool {
    let core = &vk.core;

    // 1. Create the Android surface and verify our graphics queue can present to it.
    let asci = vk::AndroidSurfaceCreateInfoKHR::default().window(window.cast());
    let surface = match core
        .android_surface_loader
        .create_android_surface(&asci, None)
    {
        Ok(s) => s,
        Err(e) => {
            error!("vkCreateAndroidSurfaceKHR failed: {e:?}");
            return false;
        }
    };

    let supported = core
        .surface_loader
        .get_physical_device_surface_support(core.phys_device, core.queue_family, surface)
        .unwrap_or(false);
    if !supported {
        error!(
            "Queue family {} does not support Android surface",
            core.queue_family
        );
        core.surface_loader.destroy_surface(surface, None);
        return false;
    }

    let Ok(caps) = core
        .surface_loader
        .get_physical_device_surface_capabilities(core.phys_device, surface)
    else {
        core.surface_loader.destroy_surface(surface, None);
        return false;
    };
    let Ok(fmts) = core
        .surface_loader
        .get_physical_device_surface_formats(core.phys_device, surface)
    else {
        core.surface_loader.destroy_surface(surface, None);
        return false;
    };
    if fmts.is_empty() {
        core.surface_loader.destroy_surface(surface, None);
        return false;
    }

    // 2. Choose R8G8B8A8_UNORM when available, plus surface extent and triple-buffered image count.
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

    // Prefer MAILBOX for tear-free low-latency presentation, falling back to FIFO_RELAXED then FIFO.
    let present_modes = core
        .surface_loader
        .get_physical_device_surface_present_modes(core.phys_device, surface)
        .unwrap_or_default();
    let present_mode = if present_modes.contains(&vk::PresentModeKHR::MAILBOX) {
        vk::PresentModeKHR::MAILBOX
    } else if present_modes.contains(&vk::PresentModeKHR::FIFO_RELAXED) {
        vk::PresentModeKHR::FIFO_RELAXED
    } else {
        vk::PresentModeKHR::FIFO
    };

    let swci = vk::SwapchainCreateInfoKHR::default()
        .surface(surface)
        .min_image_count(min_images)
        .image_format(chosen.format)
        .image_color_space(chosen.color_space)
        .image_extent(extent)
        .image_array_layers(1)
        .image_usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::COLOR_ATTACHMENT)
        .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
        .pre_transform(vk::SurfaceTransformFlagsKHR::IDENTITY)
        .composite_alpha(vk::CompositeAlphaFlagsKHR::INHERIT)
        .present_mode(present_mode)
        .clipped(true);

    let swapchain = match core.swapchain_loader.create_swapchain(&swci, None) {
        Ok(s) => s,
        Err(e) => {
            error!("vkCreateSwapchainKHR failed: {e:?}");
            core.surface_loader.destroy_surface(surface, None);
            return false;
        }
    };

    let mut images = core
        .swapchain_loader
        .get_swapchain_images(swapchain)
        .unwrap_or_default();
    images.truncate(MAX_SWAPCHAIN_IMAGES);

    info!(
        "Swapchain ready: {}x{} ({} images, fmt={:?}, mode={:?})",
        extent.width,
        extent.height,
        images.len(),
        chosen.format,
        present_mode
    );

    vk.swapchain = Some(SwapchainState {
        surface,
        swapchain,
        format: chosen.format,
        extent,
        images,
    });
    true
}

pub(super) unsafe fn destroy_swapchain(vk: &mut VulkanContext) {
    let _ = vk.core.device.device_wait_idle();
    if let Some(sw) = vk.swapchain.take() {
        vk.core
            .swapchain_loader
            .destroy_swapchain(sw.swapchain, None);
        vk.core.surface_loader.destroy_surface(sw.surface, None);
    }
}

pub(super) unsafe fn destroy_staging_buffer(
    device: &ash::Device,
    staging: &mut Option<StagingBuffer>,
) {
    if let Some(s) = staging.take() {
        if !s.mapped_ptr.is_null() {
            device.unmap_memory(s.mem);
        }
        device.destroy_buffer(s.buf, None);
        device.free_memory(s.mem, None);
    }
}

/// Ensures `staging` exists, is at least `needed` bytes, and stays persistently host-mapped.
pub(super) unsafe fn ensure_staging_buffer(
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

pub(super) unsafe fn destroy_upload_image(vk: &mut VulkanContext) {
    if let Some(u) = vk.upload_img.take() {
        vk.core.device.destroy_image(u.img, None);
        vk.core.device.free_memory(u.mem, None);
    }
}

/// Ensures `vk.upload_img` matches `(w, h, fmt)` in device-local optimal-tiling memory.
pub(super) unsafe fn ensure_upload_image(
    vk: &mut VulkanContext,
    w: u32,
    h: u32,
    fmt: vk::Format,
) -> bool {
    if let Some(u) = vk.upload_img.as_ref() {
        if u.width == w && u.height == h && u.format == fmt {
            return true;
        }
    }

    let _ = vk.core.device.device_wait_idle();
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
    let Ok(img) = vk.core.device.create_image(&ici, None) else {
        return false;
    };
    let ireq = vk.core.device.get_image_memory_requirements(img);
    let itype = find_mem_type(
        &vk.core.mem_props,
        ireq.memory_type_bits,
        vk::MemoryPropertyFlags::DEVICE_LOCAL,
    )
    .or_else(|| {
        find_mem_type(
            &vk.core.mem_props,
            ireq.memory_type_bits,
            vk::MemoryPropertyFlags::empty(),
        )
    });
    let Some(itype) = itype else {
        vk.core.device.destroy_image(img, None);
        return false;
    };
    let iai = vk::MemoryAllocateInfo::default()
        .allocation_size(ireq.size)
        .memory_type_index(itype);
    let Ok(mem) = vk.core.device.allocate_memory(&iai, None) else {
        vk.core.device.destroy_image(img, None);
        return false;
    };
    if vk.core.device.bind_image_memory(img, mem, 0).is_err() {
        vk.core.device.free_memory(mem, None);
        vk.core.device.destroy_image(img, None);
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
