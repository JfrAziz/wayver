# Wayver

Native Wayland + Vulkan + PRoot Linux container launcher for Android (`arm64-v8a`).

Wayver embeds an in-process Wayland compositor (`wl_compositor`, `xdg_shell`, `wl_shm`, `wl_seat`, `wl_output`, `wl_subcompositor`, `wl_data_device_manager`) backed by an Android `SurfaceView` and a Vulkan swapchain presenter, running rootless Linux distributions inside PRoot without VNC or X11.

## Features

- **Native Wayland + Vulkan Display**: Zero-copy `wl_shm` to Vulkan image presentation (`vk_presenter.c`, `wl_compositor_server.c`), with support for System Vulkan, bundled Maleoon Turnip (`Maleoon-Turnip-Display-v0.2.0.zip`), or user-imported Adreno/Turnip `.zip` drivers.
- **Multi-Container Management**: Create, configure, launch, switch, and stop multiple isolated Linux containers, each opening as its own Android Recents task labeled with the container name.
- **Supported Distributions & Desktops**:
  - **Alpine 3.20** (`LabWC Desktop`, `Foot Terminal`)
  - **Arch Linux ARM** (`LabWC Desktop`, `Foot Terminal`)
  - **Debian Trixie** (`LabWC Desktop`, `Foot Terminal`)
  - **Ubuntu Noble** (`LabWC Desktop`, `Foot Terminal`)
- **Reusable Distro Images**: Download once (or redownload/import `.tar` / `.tar.gz` rootfs archives) and reuse across containers.
- **Display & Input**:
  - Resolution presets (`Native`, `Native @ 0.75x`, `Native @ 0.5x`, `1080p`, `720p`, `WXGA`, or custom `WxH`) with live rotation/resize handling on `Native` presets.
  - Pointer/touch and hardware/soft-keyboard forwarding (`KeyMapper.kt`).
- **Jetpack Compose UI**:
  - Catppuccin Mocha (Dark), Catppuccin Latte (Light), and Auto system theme modes.
  - Responsive 1–5 column adaptive grid for containers and distro images.
  - Right-side **Quick Menu** overlay sheet (`Active`, `Switch`, `Logs`, `Device`) during container sessions.

## Project Structure

```text
app/src/main/
├── assets/
│   ├── distros/{alpine,arch,debian,ubuntu}/   # Per-distro & DE dotfiles (labwc, waybar, foot)
│   ├── Maleoon-Turnip-Display-v0.2.0.zip      # Bundled Maleoon Turnip ICD archive
│   └── wallpaper.jpg                          # Default LabWC wallpaper
├── cpp/                                       # Native Wayland compositor, Vulkan loader/presenter, JNI
├── java/dev/wayver/app/
│   ├── container/                             # ContainerConfig, ContainerStore, ContainerManager
│   ├── core/                                  # AppPrefs, NativeBridge
│   ├── distro/                                # Distro specs, RootfsManager, ProotRunner
│   ├── driver/                                # DriverManager (.zip ICD importer)
│   ├── input/                                 # KeyMapper (Android KeyEvent -> Linux evdev)
│   ├── ui/                                    # Compose Theme, LauncherScreen, QuickMenuOverlay
│   └── utils/                                 # AssetCopy, ContainerLogger, HttpDownloader, TarExtractor
├── jniLibs/arm64-v8a/                         # libproot.so, libproot-loader.so, libwayland-server.so, libffi.so
└── res/                                       # Adaptive launcher icon & distro vector icons
```

## Build & Tooling

Requirements: Android SDK (API 34, `minSdk` 26), Android NDK `27.2.12479018`, CMake, JDK 17+.

```bash
# Build debug APK
./gradlew assembleDebug

# Kotlin formatting & linting (ktlint)
./gradlew ktlintFormat
./gradlew ktlintCheck

# C/C++ formatting & linting (clang-format / clang-tidy)
./gradlew clangFormat
./gradlew clangFormatCheck
./gradlew clangTidy
```
