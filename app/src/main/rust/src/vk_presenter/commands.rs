//! Vulkan command buffer recording, staging pixel copy, barriers, and queue submission.

use ash::vk;
use log::error;

use super::DirtyRect;

/// Copies `rect` from Wayland SHM `pixels` into tightly-packed staging memory.
///
/// Uses a single `copy_from_slice` when uploading the entire tightly-strided frame,
/// otherwise copies row-by-row for partial damage or padded strides.
pub(super) fn copy_rect_to_staging(
    dst: &mut [u8],
    pixels: &[u8],
    stride: usize,
    rect: DirtyRect,
    width: u32,
    height: u32,
) {
    let rect_row_bytes = (rect.width as usize) * 4;
    let full_row_bytes = (width as usize) * 4;
    let is_full_tight = rect.x == 0
        && rect.y == 0
        && rect.width == width
        && rect.height == height
        && stride == full_row_bytes
        && pixels.len() >= dst.len();

    if is_full_tight {
        dst.copy_from_slice(&pixels[..dst.len()]);
        return;
    }

    let x_off = (rect.x as usize) * 4;
    for row in 0..rect.height as usize {
        let src_off = (rect.y as usize + row) * stride + x_off;
        let dst_off = row * rect_row_bytes;
        if src_off + rect_row_bytes <= pixels.len() && dst_off + rect_row_bytes <= dst.len() {
            dst[dst_off..dst_off + rect_row_bytes]
                .copy_from_slice(&pixels[src_off..src_off + rect_row_bytes]);
        }
    }
}

/// Records a solid-color clear pass onto `swap_img` and transitions it to `PRESENT_SRC_KHR`.
pub(super) unsafe fn record_clear_commands(
    device: &ash::Device,
    cmd_buf: vk::CommandBuffer,
    swap_img: vk::Image,
    r: f32,
    g: f32,
    b: f32,
    a: f32,
) {
    let _ = device.reset_command_buffer(cmd_buf, vk::CommandBufferResetFlags::empty());
    let cbbi =
        vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    let _ = device.begin_command_buffer(cmd_buf, &cbbi);

    let range = color_subresource_range();
    let to_clear = image_barrier(
        swap_img,
        vk::AccessFlags::empty(),
        vk::AccessFlags::TRANSFER_WRITE,
        vk::ImageLayout::UNDEFINED,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        range,
    );
    device.cmd_pipeline_barrier(
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
    device.cmd_clear_color_image(
        cmd_buf,
        swap_img,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        &color,
        &[range],
    );

    // Transition `swap_img` to PRESENT_SRC_KHR before handing it back to Android SurfaceFlinger.
    let to_present = image_barrier(
        swap_img,
        vk::AccessFlags::TRANSFER_WRITE,
        vk::AccessFlags::empty(),
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        vk::ImageLayout::PRESENT_SRC_KHR,
        range,
    );
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::BOTTOM_OF_PIPE,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &[to_present],
    );
    let _ = device.end_command_buffer(cmd_buf);
}

/// Records the GPU upload and presentation pipeline for one frame:
/// 1. Transition `upload_img` and `swap_img` into `TRANSFER_DST_OPTIMAL`.
/// 2. Copy the dirty `rect` from `staging_buf` into `upload_img`.
/// 3. Transition `upload_img` to `TRANSFER_SRC_OPTIMAL`.
/// 4. Copy (1:1 same format) or blit (format swizzle / scaling) `upload_img` -> `swap_img`.
/// 5. Transition `swap_img` to `PRESENT_SRC_KHR` for display scanout.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn record_present_commands(
    device: &ash::Device,
    cmd_buf: vk::CommandBuffer,
    staging_buf: vk::Buffer,
    upload_img: vk::Image,
    upload_was_initialized: bool,
    swap_img: vk::Image,
    rect: DirtyRect,
    width: u32,
    height: u32,
    src_fmt: vk::Format,
    extent: vk::Extent2D,
    swap_fmt: vk::Format,
) {
    let _ = device.reset_command_buffer(cmd_buf, vk::CommandBufferResetFlags::empty());
    let cbbi =
        vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    let _ = device.begin_command_buffer(cmd_buf, &cbbi);

    let range = color_subresource_range();
    // Preserve prior contents of `upload_img` when patching a partial dirty rect;
    // `swap_img` is always overwritten in full, so `UNDEFINED` lets the GPU discard old data.
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
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::TRANSFER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &barriers_before,
    );

    // Upload only the damaged region from the staging buffer into `upload_img`.
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
    device.cmd_copy_buffer_to_image(
        cmd_buf,
        staging_buf,
        upload_img,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        &[region],
    );

    // Transition `upload_img` so the GPU copy/blit stage can read from it.
    let upload_to_src = image_barrier(
        upload_img,
        vk::AccessFlags::TRANSFER_WRITE,
        vk::AccessFlags::TRANSFER_READ,
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
        range,
    );
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::TRANSFER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &[upload_to_src],
    );

    // Use hardware vkCmdCopyImage for 1:1 same-format frames to avoid blit/filter overhead.
    if width == extent.width && height == extent.height && src_fmt == swap_fmt {
        let copy_region = vk::ImageCopy::default()
            .src_subresource(vk::ImageSubresourceLayers {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                mip_level: 0,
                base_array_layer: 0,
                layer_count: 1,
            })
            .dst_subresource(vk::ImageSubresourceLayers {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                mip_level: 0,
                base_array_layer: 0,
                layer_count: 1,
            })
            .extent(vk::Extent3D {
                width,
                height,
                depth: 1,
            });
        device.cmd_copy_image(
            cmd_buf,
            upload_img,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            swap_img,
            vk::ImageLayout::TRANSFER_DST_OPTIMAL,
            &[copy_region],
        );
    } else {
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
        device.cmd_blit_image(
            cmd_buf,
            upload_img,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            swap_img,
            vk::ImageLayout::TRANSFER_DST_OPTIMAL,
            &[blit],
            filter,
        );
    }

    // Transition `swap_img` to PRESENT_SRC_KHR before handing it back to Android SurfaceFlinger.
    let to_present = image_barrier(
        swap_img,
        vk::AccessFlags::TRANSFER_WRITE,
        vk::AccessFlags::empty(),
        vk::ImageLayout::TRANSFER_DST_OPTIMAL,
        vk::ImageLayout::PRESENT_SRC_KHR,
        range,
    );
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::BOTTOM_OF_PIPE,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &[to_present],
    );
    let _ = device.end_command_buffer(cmd_buf);
}

/// Submits `cmd_buf` (waiting on `sem_avail`, signaling `sem_done` and `fence`),
/// then queues `img_idx` for presentation once `sem_done` is signaled.
#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn submit_and_present(
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
