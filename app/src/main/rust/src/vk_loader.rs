use ash::vk;
use libloading::Library;

pub struct VkLoader {
    _lib: Library,
    pub get_instance_proc_addr: vk::PFN_vkGetInstanceProcAddr,
    pub is_custom_driver: bool,
}

impl VkLoader {
    pub fn open(driver_so_path: Option<&str>) -> Option<Self> {
        if let Some(path) = driver_so_path.filter(|s| !s.is_empty()) {
            if let Ok(lib) = unsafe { Library::new(path) } {
                if let Some(pfn) = load_gipa(&lib, true) {
                    return Some(Self {
                        _lib: lib,
                        get_instance_proc_addr: pfn,
                        is_custom_driver: true,
                    });
                }
            }
        }

        let lib = unsafe { Library::new("libvulkan.so") }.ok()?;
        let pfn = load_gipa(&lib, false)?;
        Some(Self {
            _lib: lib,
            get_instance_proc_addr: pfn,
            is_custom_driver: false,
        })
    }
}

fn load_gipa(lib: &Library, try_icd: bool) -> Option<vk::PFN_vkGetInstanceProcAddr> {
    unsafe {
        if let Ok(sym) = lib.get::<vk::PFN_vkGetInstanceProcAddr>(b"vkGetInstanceProcAddr\0") {
            return Some(*sym);
        }
        if try_icd {
            if let Ok(sym) =
                lib.get::<vk::PFN_vkGetInstanceProcAddr>(b"vk_icdGetInstanceProcAddr\0")
            {
                return Some(*sym);
            }
        }
    }
    None
}
