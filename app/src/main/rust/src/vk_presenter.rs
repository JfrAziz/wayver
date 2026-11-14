use std::ffi::CStr;
use std::sync::Mutex;

use ash::vk;
use log::{error, info};

use crate::vk_loader::VkLoader;

const MAX_SWAPCHAIN_IMAGES: usize = 8;

pub struct VkPresenter {
    inner: Mutex<PresenterInner>,
}

struct UploadResources {
    staging_buf: vk::Buffer,
    staging_mem: vk::DeviceMemory,
    staging_size: vk::DeviceSize,
    upload_img: vk::Image,
    upload_mem: vk::DeviceMemory,
    width: u32,
    height: u32,
    format: vk::Format,
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
    cmd_buf: vk::CommandBuffer,
    sem_avail: vk::Semaphore,
    sem_done: vk::Semaphore,
    fence: vk::Fence,
    surface: vk::SurfaceKHR,
    swapchain: Option<SwapchainState>,
    upload: Option<UploadResources>,
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

        unsafe {
            let _ = vk.device.wait_for_fences(&[vk.fence], true, u64::MAX);
            let _ = vk.device.reset_fences(&[vk.fence]);

            let img_idx = match vk.swapchain_loader.acquire_next_image(
                sw.swapchain,
                u64::MAX,
                vk.sem_avail,
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
                .reset_command_buffer(vk.cmd_buf, vk::CommandBufferResetFlags::empty());
            let cbbi = vk::CommandBufferBeginInfo::default()
                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
            let _ = vk.device.begin_command_buffer(vk.cmd_buf, &cbbi);

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
                vk.cmd_buf,
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
                vk.cmd_buf,
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
                vk.cmd_buf,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[to_present],
            );
            let _ = vk.device.end_command_buffer(vk.cmd_buf);

            if !submit_and_present(vk, sw.swapchain, img_idx) {
                return false;
            }
            info!("Presented clear frame #{img_idx}");
            true
        }
    }

    pub fn present_pixels(
        &self,
        pixels: &[u8],
        width: u32,
        height: u32,
        stride_bytes: u32,
        is_bgra: bool,
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

        unsafe {
            let _ = vk.device.wait_for_fences(&[vk.fence], true, u64::MAX);
            let _ = vk.device.reset_fences(&[vk.fence]);

            let src_fmt = if is_bgra {
                vk::Format::B8G8R8A8_UNORM
            } else {
                vk::Format::R8G8B8A8_UNORM
            };
            if !ensure_upload_resources(vk, width, height, src_fmt) {
                error!("ensure_upload_resources failed for {width}x{height}");
                return false;
            }

            let upload = vk.upload.as_ref().unwrap();
            let total_bytes = (width as vk::DeviceSize) * (height as vk::DeviceSize) * 4;
            let Ok(mapped) = vk.device.map_memory(
                upload.staging_mem,
                0,
                total_bytes,
                vk::MemoryMapFlags::empty(),
            ) else {
                return false;
            };

            let dst = std::slice::from_raw_parts_mut(mapped.cast::<u8>(), total_bytes as usize);
            let row_bytes = (width * 4) as usize;
            let stride = stride_bytes as usize;
            if stride == row_bytes && pixels.len() >= row_bytes * height as usize {
                dst.copy_from_slice(&pixels[..row_bytes * height as usize]);
            } else {
                for y in 0..height as usize {
                    let src_off = y * stride;
                    let dst_off = y * row_bytes;
                    if src_off + row_bytes <= pixels.len() {
                        dst[dst_off..dst_off + row_bytes]
                            .copy_from_slice(&pixels[src_off..src_off + row_bytes]);
                    }
                }
            }
            vk.device.unmap_memory(upload.staging_mem);

            let sw = vk.swapchain.as_ref().unwrap();
            let Ok((img_idx, _)) = vk.swapchain_loader.acquire_next_image(
                sw.swapchain,
                u64::MAX,
                vk.sem_avail,
                vk::Fence::null(),
            ) else {
                return false;
            };

            let swap_img = sw.images[img_idx as usize];
            let extent = sw.extent;
            let upload_img = upload.upload_img;
            let staging_buf = upload.staging_buf;

            let _ = vk
                .device
                .reset_command_buffer(vk.cmd_buf, vk::CommandBufferResetFlags::empty());
            let cbbi = vk::CommandBufferBeginInfo::default()
                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
            let _ = vk.device.begin_command_buffer(vk.cmd_buf, &cbbi);

            let range = color_subresource_range();
            let barriers_before = [
                image_barrier(
                    upload_img,
                    vk::AccessFlags::empty(),
                    vk::AccessFlags::TRANSFER_WRITE,
                    vk::ImageLayout::UNDEFINED,
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
                vk.cmd_buf,
                vk::PipelineStageFlags::TOP_OF_PIPE,
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
                .image_offset(vk::Offset3D { x: 0, y: 0, z: 0 })
                .image_extent(vk::Extent3D {
                    width,
                    height,
                    depth: 1,
                });
            vk.device.cmd_copy_buffer_to_image(
                vk.cmd_buf,
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
                vk.cmd_buf,
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
                vk.cmd_buf,
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
                vk.cmd_buf,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &[to_present],
            );
            let _ = vk.device.end_command_buffer(vk.cmd_buf);

            submit_and_present(vk, sw.swapchain, img_idx)
        }
    }
}

impl Drop for VulkanContext {
    fn drop(&mut self) {
        unsafe {
            destroy_surface(self);
            destroy_upload_resources(self);
            let _ = self.device.device_wait_idle();
            self.device.destroy_fence(self.fence, None);
            self.device.destroy_semaphore(self.sem_avail, None);
            self.device.destroy_semaphore(self.sem_done, None);
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
        .command_buffer_count(1);
    let cmd_buf = unsafe { device.allocate_command_buffers(&cbai) }
        .map_err(|e| format!("vkAllocateCommandBuffers failed: {e:?}"))?[0];

    let sci = vk::SemaphoreCreateInfo::default();
    let sem_avail = unsafe { device.create_semaphore(&sci, None) }
        .map_err(|e| format!("vkCreateSemaphore failed: {e:?}"))?;
    let sem_done = unsafe { device.create_semaphore(&sci, None) }
        .map_err(|e| format!("vkCreateSemaphore failed: {e:?}"))?;

    let fci = vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
    let fence = unsafe { device.create_fence(&fci, None) }
        .map_err(|e| format!("vkCreateFence failed: {e:?}"))?;

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
        cmd_buf,
        sem_avail,
        sem_done,
        fence,
        surface: vk::SurfaceKHR::null(),
        swapchain: None,
        upload: None,
    };
    Ok((ctx, info_str))
}

unsafe fn submit_and_present(
    vk: &VulkanContext,
    swapchain: vk::SwapchainKHR,
    img_idx: u32,
) -> bool {
    let wait_stages = [vk::PipelineStageFlags::TRANSFER];
    let wait_sems = [vk.sem_avail];
    let cmd_bufs = [vk.cmd_buf];
    let sig_sems = [vk.sem_done];
    let si = vk::SubmitInfo::default()
        .wait_semaphores(&wait_sems)
        .wait_dst_stage_mask(&wait_stages)
        .command_buffers(&cmd_bufs)
        .signal_semaphores(&sig_sems);
    if let Err(e) = vk.device.queue_submit(vk.queue, &[si], vk.fence) {
        error!("vkQueueSubmit failed: {e:?}");
        return false;
    }

    let swapchains = [swapchain];
    let indices = [img_idx];
    let pi = vk::PresentInfoKHR::default()
        .wait_semaphores(&sig_sems)
        .swapchains(&swapchains)
        .image_indices(&indices);
    match vk.swapchain_loader.queue_present(vk.queue, &pi) {
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

unsafe fn destroy_upload_resources(vk: &mut VulkanContext) {
    if let Some(u) = vk.upload.take() {
        vk.device.destroy_image(u.upload_img, None);
        vk.device.free_memory(u.upload_mem, None);
        vk.device.destroy_buffer(u.staging_buf, None);
        vk.device.free_memory(u.staging_mem, None);
    }
}

unsafe fn ensure_upload_resources(vk: &mut VulkanContext, w: u32, h: u32, fmt: vk::Format) -> bool {
    let needed = (w as vk::DeviceSize) * (h as vk::DeviceSize) * 4;
    if let Some(u) = vk.upload.as_ref() {
        if u.staging_size >= needed && u.width == w && u.height == h && u.format == fmt {
            return true;
        }
    }

    let _ = vk.device.device_wait_idle();
    destroy_upload_resources(vk);

    let bci = vk::BufferCreateInfo::default()
        .size(needed)
        .usage(vk::BufferUsageFlags::TRANSFER_SRC)
        .sharing_mode(vk::SharingMode::EXCLUSIVE);
    let Ok(staging_buf) = vk.device.create_buffer(&bci, None) else {
        return false;
    };
    let breq = vk.device.get_buffer_memory_requirements(staging_buf);
    let Some(btype) = find_mem_type(
        &vk.mem_props,
        breq.memory_type_bits,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    ) else {
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    };
    let bai = vk::MemoryAllocateInfo::default()
        .allocation_size(breq.size)
        .memory_type_index(btype);
    let Ok(staging_mem) = vk.device.allocate_memory(&bai, None) else {
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    };
    if vk
        .device
        .bind_buffer_memory(staging_buf, staging_mem, 0)
        .is_err()
    {
        vk.device.free_memory(staging_mem, None);
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    }

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
    let Ok(upload_img) = vk.device.create_image(&ici, None) else {
        vk.device.free_memory(staging_mem, None);
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    };
    let ireq = vk.device.get_image_memory_requirements(upload_img);
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
        vk.device.destroy_image(upload_img, None);
        vk.device.free_memory(staging_mem, None);
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    };
    let iai = vk::MemoryAllocateInfo::default()
        .allocation_size(ireq.size)
        .memory_type_index(itype);
    let Ok(upload_mem) = vk.device.allocate_memory(&iai, None) else {
        vk.device.destroy_image(upload_img, None);
        vk.device.free_memory(staging_mem, None);
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    };
    if vk
        .device
        .bind_image_memory(upload_img, upload_mem, 0)
        .is_err()
    {
        vk.device.free_memory(upload_mem, None);
        vk.device.destroy_image(upload_img, None);
        vk.device.free_memory(staging_mem, None);
        vk.device.destroy_buffer(staging_buf, None);
        return false;
    }

    vk.upload = Some(UploadResources {
        staging_buf,
        staging_mem,
        staging_size: needed,
        upload_img,
        upload_mem,
        width: w,
        height: h,
        format: fmt,
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
