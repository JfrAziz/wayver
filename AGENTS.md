# Wayver

Native Wayland + Vulkan + PRoot Linux container launcher for Android (`dev.wayver.app`, `arm64-v8a`, `minSdk` 26, `compileSdk` 34). No VNC, no X11, and no root required.

## Build & Tooling

Requirements: Android SDK (API 34, `minSdk` 26), Android NDK `27.2.12479018`, Rust toolchain (`aarch64-linux-android` target), JDK 17+.

```bash
./gradlew assembleDebug    # Builds APK + libwayver.so via Cargo
./gradlew installDebug     # Installs debug APK to connected device
./gradlew ktlintCheck      # Kotlin lint check
./gradlew ktlintFormat     # Kotlin auto-format
```

## Architecture

- **Android App (`app/src/main/java/dev/wayver/app/`)**: Kotlin + Jetpack Compose (`container/`, `core/`, `distro/`, `driver/`, `input/`, `ui/`, `utils/`).
- **Rust Native Engine (`app/src/main/rust/src/` -> `libwayver.so`)**:
    - `wl_server`: In-process Wayland compositor (`wl_compositor`, `xdg_shell`, `wl_shm`, `wl_seat`, `wl_output`, `wl_subcompositor`, `wl_data_device_manager`).
    - `vk_presenter`: Vulkan swapchain presenter backed by Android `SurfaceView` (`ANativeWindow`), supporting system Vulkan or imported Turnip ICD `.zip` drivers.
    - `audio`: In-process PulseAudio native socket server (`pulse_audio.rs`) streaming PCM directly to Android `AAudio` (`aaudio_sink.rs`).
- **PRoot Guest Containers**: Prebuilt `libproot.so` / `libproot-loader.so` in `app/src/main/jniLibs/arm64-v8a/`, with per-distro/desktop dotfiles in `app/src/main/assets/distros/{alpine,arch,debian,ubuntu}/` (Sway, LabWC, Foot).

## Agent skills

### Issue tracker

Issues and specs are tracked as local markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role triage vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo (`GLOSSARY.md` and `docs/adr/` at the repo root, created lazily when needed). See `docs/agents/domain.md`.
