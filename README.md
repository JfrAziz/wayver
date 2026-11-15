# Wayver

Native Wayland + Vulkan + PRoot Linux container launcher for Android (`arm64-v8a`).

Wayver embeds an in-process Rust Wayland compositor and Vulkan presenter (`libwayver.so`) backed by an Android `SurfaceView`, running rootless Linux distributions inside PRoot with **no VNC, no X11, and no root required**.

---

## Architecture

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Android App (Kotlin + Jetpack Compose)                                  │
│  ├─ LauncherScreen ── Container/Image management, theme & env editor    │
│  ├─ DisplayActivity ─ SurfaceView, input forwarding, Setup & Quick Menu │
│  └─ ContainerNotificationService ─ Foreground service (Open / Kill)     │
├─────────────────────────────────────────────────────────────────────────┤
│ Rust Native Engine (libwayver.so via JNI)                               │
│  ├─ wl_server    ── Wayland compositor (wl_shm, xdg_shell, wl_seat...)  │
│  ├─ vk_presenter ── Vulkan swapchain (MAILBOX, vkCmdCopyImage 1:1)      │
│  └─ audio        ── PulseAudio native socket server -> Android AAudio    │
├─────────────────────────────────────────────────────────────────────────┤
│ PRoot Guest Containers (files/containers/<id>/rootfs)                   │
│  └─ Sway / LabWC / Foot ── connects to wayland-0 & pulse-native         │
└─────────────────────────────────────────────────────────────────────────┘
```

1. **In-Process Wayland Compositor**: Implements `wl_compositor`, `xdg_shell`, `wl_shm`, `wl_seat`, `wl_output`, `wl_subcompositor`, and `wl_data_device_manager` in Rust (`wl_server`).
2. **Vulkan Presentation**: Renders client `wl_shm` buffers directly onto the Android `SurfaceView` Vulkan swapchain (`vk_presenter`), preferring `MAILBOX` present mode and `vkCmdCopyImage` for 1:1 frames.
3. **Native Audio**: Exposes a PulseAudio native socket inside each container's `XDG_RUNTIME_DIR` and streams PCM audio directly to Android `AAudio` (`audio/pulse_audio.rs`, `audio/aaudio_sink.rs`).

---

## Features

- **Multi-Container Lifecycle**:
    - Create, edit, launch, switch, stop, and delete (with confirmation dialog) multiple isolated Linux containers.
    - Each active container opens in its own Android Recents task labeled with the container name and maintains a persistent foreground notification with **Open** and **Kill** actions (`ContainerNotificationService`).
- **Per-Distro-Desktop Config Isolation**:
    - `XDG_CONFIG_HOME=/root/.config` keeps user app configs (`nvim`, `btop`, `opencode`, etc.) shared across desktops.
    - Desktop-specific configs live in separate directories (`/root/.config/sway/`, `/root/.config/labwc/`, `/root/.config/foot/`) so switching desktops in the same container never overwrites another desktop's customized dotfiles.
- **Supported Distributions & Desktops**:
    - **Alpine 3.23**: `Sway`, `LabWC Desktop`, `Foot Terminal`
    - **Arch Linux ARM**: `Sway`, `LabWC Desktop`, `Foot Terminal`
    - **Debian Trixie**: `LabWC Desktop`, `Foot Terminal`
    - **Ubuntu Noble**: `LabWC Desktop`, `Foot Terminal`
- **Reusable Distro Images**: Download once (or redownload / import `.tar`, `.tar.gz`, `.tar.xz` rootfs archives) and reuse across any number of containers.
- **Per-Distro App Env Presets**: Built-in `AppEnvPresets` and editable per-container `EnvConfig` overrides.
- **Display & Input**:
    - Resolution presets (`Native`, `Native @ 0.75x`, `Native @ 0.5x`, `1080p`, `720p`, `WXGA`, or custom `WxH`) with live rotation/resize handling on `Native` presets.
    - Pointer/touch and hardware/soft-keyboard forwarding
- **Jetpack Compose UI**:
    - Catppuccin Mocha (Dark), Catppuccin Latte (Light), and Auto system theme modes.
    - Responsive 1–5 column adaptive grid for containers and distro images.
    - Center-right vertical **Quick Menu** pill (keyboard toggle + menu) opening the tabbed Quick Menu sheet (`Active`, `Switch`, `Logs`, `Device`) during container sessions.

---

## Desktop Environments

| Desktop                         | Included Tools & Styling                                              | Highlights                                                                                                                                                                        |
| :------------------------------ | :-------------------------------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Sway** (Alpine, Arch)         | `sway`, `waybar`, `fuzzel`, `foot`, `autotiling`, `pcmanfm`, `swaybg` | Tiling WM with Catppuccin-styled top Waybar, Fuzzel app launcher (`Alt+Space`), and isolated `/root/.config/sway/` configs                                                        |
| **LabWC** (All distros)         | `labwc`, `waybar`, `foot`, `pcmanfm`, `swaybg`                        | Floating stacking WM with macOS-style edge-to-edge top Waybar, minimal right-click menu (`Terminal`, `File Manager`, `Open Windows`), and isolated `/root/.config/labwc/` configs |
| **Foot Terminal** (All distros) | `foot`                                                                | Fast, lightweight standalone Wayland terminal session (`/root/.config/foot/foot.ini`)                                                                                             |

---

## Project Structure

```text
app/src/main/
├── assets/
│   ├── distros/{alpine,arch,debian,ubuntu}/   # Isolated per-DE dotfiles (sway/, labwc/, foot/)
│   └── wallpaper.jpg                          # Default desktop wallpaper
├── java/dev/wayver/app/
│   ├── container/                             # ContainerConfig, ContainerStore, ContainerManager, EnvConfig
│   ├── core/                                  # AppPrefs, NativeBridge, ContainerNotificationService
│   ├── distro/                                # Distro specs, RootfsManager, ProotRunner, ProcSysBindings
│   ├── driver/                                # DriverManager (.zip ICD importer)
│   ├── input/                                 # KeyMapper (Android KeyEvent -> Linux evdev)
│   ├── ui/                                    # Compose Theme, LauncherScreen, SetupOverlay, QuickMenuOverlay
│   └── utils/                                 # AssetCopy, ContainerLogger, HttpDownloader, TarExtractor
├── jniLibs/arm64-v8a/                         # libproot.so, libproot-loader.so, libwayland-server.so, libffi.so
├── res/                                       # Adaptive launcher icon & distro vector icons
└── rust/src/                                  # Rust native library (libwayver.so)
    ├── audio/                                 # pulse_audio.rs, aaudio_sink.rs
    ├── vk_presenter/                          # Vulkan context, commands, swapchain presenter
    └── wl_server/                             # Wayland protocols, shm, state, present_worker
```

---

## Build & Tooling

Requirements:

- Android SDK (API 34, `minSdk` 26)
- Android NDK `27.2.12479018`
- Rust toolchain with `aarch64-linux-android` target
- JDK 17+

```bash
# Build debug APK (automatically builds libwayver.so via Cargo)
./gradlew assembleDebug

# Kotlin formatting & linting (ktlint)
./gradlew ktlintFormat
./gradlew ktlintCheck

# Install onto a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
