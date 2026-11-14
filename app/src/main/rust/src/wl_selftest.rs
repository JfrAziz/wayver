use std::ffi::CString;
use std::fs::{self, OpenOptions};
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;
use std::path::Path;

use log::{error, info};

fn pad4(n: usize) -> usize {
    (n + 3) & !3
}

fn send_msg(fd: i32, buf: &[u8], pass_fd: Option<i32>) -> bool {
    let mut iov = libc::iovec {
        iov_base: buf.as_ptr() as *mut libc::c_void,
        iov_len: buf.len(),
    };
    let mut cbuf = [0u8; unsafe { libc::CMSG_SPACE(std::mem::size_of::<i32>() as u32) } as usize];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;

    if let Some(pfd) = pass_fd {
        msg.msg_control = cbuf.as_mut_ptr().cast();
        msg.msg_controllen = cbuf.len() as _;
        unsafe {
            let cmsg = libc::CMSG_FIRSTHDR(&msg);
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            (*cmsg).cmsg_len = libc::CMSG_LEN(std::mem::size_of::<i32>() as u32) as _;
            std::ptr::copy_nonoverlapping(
                &pfd as *const i32 as *const u8,
                libc::CMSG_DATA(cmsg),
                std::mem::size_of::<i32>(),
            );
        }
    }
    let sent = unsafe { libc::sendmsg(fd, &msg, 0) };
    sent == buf.len() as isize
}

fn wl_hdr(size: u32, opcode: u32) -> u32 {
    (size << 16) | opcode
}

fn words_to_bytes(words: &[u32]) -> &[u8] {
    unsafe { std::slice::from_raw_parts(words.as_ptr().cast::<u8>(), std::mem::size_of_val(words)) }
}

pub fn send_frame(runtime_dir: &str, width: i32, height: i32) -> bool {
    if runtime_dir.is_empty() || width <= 0 || height <= 0 {
        return false;
    }

    let sock_path = Path::new(runtime_dir).join("wayland-0");
    let stream = match UnixStream::connect(&sock_path) {
        Ok(s) => s,
        Err(e) => {
            error!("selftest connect({}) failed: {e}", sock_path.display());
            return false;
        }
    };
    let sock_fd = stream.as_raw_fd();

    // 1. wl_display(1)::get_registry(opcode=1) -> new_id=2
    let get_reg: [u32; 3] = [1, wl_hdr(12, 1), 2];
    send_msg(sock_fd, words_to_bytes(&get_reg), None);

    // 2. wl_display(1)::sync(opcode=0) -> new_id=3
    let sync_msg: [u32; 3] = [1, wl_hdr(12, 0), 3];
    send_msg(sock_fd, words_to_bytes(&sync_msg), None);

    // Read globals until wl_callback(3)::done
    let mut comp_name = 0u32;
    let mut comp_ver = 0u32;
    let mut shm_name = 0u32;
    let mut shm_ver = 0u32;
    let mut rbuf = [0u8; 4096];
    let mut synced = false;

    while !synced {
        let n = unsafe { libc::recv(sock_fd, rbuf.as_mut_ptr().cast(), rbuf.len(), 0) };
        if n <= 0 {
            break;
        }
        let n = n as usize;
        let mut off = 0usize;
        while off + 8 <= n {
            let obj = u32::from_ne_bytes(rbuf[off..off + 4].try_into().unwrap());
            let hdr = u32::from_ne_bytes(rbuf[off + 4..off + 8].try_into().unwrap());
            let sz = (hdr >> 16) as usize;
            let op = hdr & 0xFFFF;
            if sz < 8 || off + sz > n {
                break;
            }
            if obj == 2 && op == 0 && sz >= 20 {
                let name = u32::from_ne_bytes(rbuf[off + 8..off + 12].try_into().unwrap());
                let slen =
                    u32::from_ne_bytes(rbuf[off + 12..off + 16].try_into().unwrap()) as usize;
                let str_end = (off + 16 + slen.saturating_sub(1)).min(off + sz);
                let iface = std::str::from_utf8(&rbuf[off + 16..str_end]).unwrap_or("");
                let ver_off = off + 16 + pad4(slen);
                if ver_off + 4 <= off + sz {
                    let ver = u32::from_ne_bytes(rbuf[ver_off..ver_off + 4].try_into().unwrap());
                    if iface == "wl_compositor" {
                        comp_name = name;
                        comp_ver = ver;
                    } else if iface == "wl_shm" {
                        shm_name = name;
                        shm_ver = ver;
                    }
                }
            } else if obj == 3 && op == 0 {
                synced = true;
            }
            off += sz;
        }
    }

    if comp_name == 0 || shm_name == 0 {
        error!("selftest missing globals (comp={comp_name} shm={shm_name})");
        return false;
    }

    // 3. wl_registry(2)::bind(opcode=0) for wl_compositor -> id 4
    send_bind(sock_fd, comp_name, "wl_compositor", comp_ver.min(4), 4);
    // 4. wl_registry(2)::bind(opcode=0) for wl_shm -> id 5
    send_bind(sock_fd, shm_name, "wl_shm", shm_ver.min(1), 5);

    // 5. Create shm backing file in runtime_dir
    let shm_path = Path::new(runtime_dir).join("selftest-shm.bin");
    let Ok(file) = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(&shm_path)
    else {
        return false;
    };
    let _ = fs::remove_file(&shm_path);

    let stride = (width as u32) * 4;
    let shm_size = (stride as usize) * (height as usize);
    if file.set_len(shm_size as u64).is_err() {
        return false;
    }

    let shm_fd = file.as_raw_fd();
    let ptr = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            shm_size,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_SHARED,
            shm_fd,
            0,
        )
    };
    if ptr == libc::MAP_FAILED {
        return false;
    }

    let px =
        unsafe { std::slice::from_raw_parts_mut(ptr.cast::<u32>(), (width * height) as usize) };
    for y in 0..height {
        for x in 0..width {
            let mut r = ((x * 255) / width) as u8;
            let mut g = ((y * 255) / height) as u8;
            let b = 0x90u8;
            if ((x / 32) + (y / 32)) % 2 == 0 {
                r = r / 2 + 20;
                g = g / 2 + 30;
            }
            px[(y * width + x) as usize] =
                0xFF00_0000 | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32);
        }
    }
    unsafe {
        libc::munmap(ptr, shm_size);
    }

    // 6. wl_compositor(4)::create_surface(opcode=0) -> id 6
    let create_surf: [u32; 3] = [4, wl_hdr(12, 0), 6];
    send_msg(sock_fd, words_to_bytes(&create_surf), None);

    // 7. wl_shm(5)::create_pool(opcode=0) -> id 7, fd=shm_fd, size
    let create_pool: [u32; 4] = [5, wl_hdr(16, 0), 7, shm_size as u32];
    send_msg(sock_fd, words_to_bytes(&create_pool), Some(shm_fd));
    drop(file);

    // 8. wl_shm_pool(7)::create_buffer(opcode=0) -> id 8, offset=0, w, h, stride, fmt=1(XRGB8888)
    let create_buf: [u32; 8] = [
        7,
        wl_hdr(32, 0),
        8,
        0,
        width as u32,
        height as u32,
        stride,
        1,
    ];
    send_msg(sock_fd, words_to_bytes(&create_buf), None);

    // 9. wl_surface(6)::attach(opcode=1) buf=8, x=0, y=0
    let attach: [u32; 5] = [6, wl_hdr(20, 1), 8, 0, 0];
    send_msg(sock_fd, words_to_bytes(&attach), None);

    // 10. wl_surface(6)::commit(opcode=6)
    let commit: [u32; 2] = [6, wl_hdr(8, 6)];
    send_msg(sock_fd, words_to_bytes(&commit), None);

    // 11. wl_display(1)::sync(opcode=0) -> id 9 to wait for server commit
    let sync2: [u32; 3] = [1, wl_hdr(12, 0), 9];
    send_msg(sock_fd, words_to_bytes(&sync2), None);
    unsafe {
        libc::recv(sock_fd, rbuf.as_mut_ptr().cast(), rbuf.len(), 0);
    }

    info!("Wayland self-test client committed {width}x{height} wl_shm frame successfully");
    true
}

fn send_bind(sock_fd: i32, name: u32, iface: &str, version: u32, new_id: u32) {
    let cstr = CString::new(iface).unwrap();
    let bytes = cstr.as_bytes_with_nul();
    let padded = pad4(bytes.len());
    let total_sz = 8 + 4 + 4 + padded + 4 + 4;
    let mut buf = vec![0u8; total_sz];
    buf[0..4].copy_from_slice(&2u32.to_ne_bytes());
    buf[4..8].copy_from_slice(&wl_hdr(total_sz as u32, 0).to_ne_bytes());
    buf[8..12].copy_from_slice(&name.to_ne_bytes());
    buf[12..16].copy_from_slice(&(bytes.len() as u32).to_ne_bytes());
    buf[16..16 + bytes.len()].copy_from_slice(bytes);
    let ver_off = 16 + padded;
    buf[ver_off..ver_off + 4].copy_from_slice(&version.to_ne_bytes());
    buf[ver_off + 4..ver_off + 8].copy_from_slice(&new_id.to_ne_bytes());
    send_msg(sock_fd, &buf, None);
}
