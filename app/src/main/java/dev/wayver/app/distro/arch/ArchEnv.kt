package dev.wayver.app.distro.arch

/**
 * Arch-specific container and per-app environment overrides.
 */
object ArchEnv {
    val containerEnv: Map<String, String> = emptyMap()

    val appEnvs: Map<String, Map<String, String>> =
        mapOf(
            // Force Wayland and disable seccomp/namespace child sandboxes (content, OpenH264 GMP,
            // GPU, FFmpeg RDD, socket, media utility) because glibc Firefox's sandbox conflicts with PRoot ptrace.
            "firefox" to
                mapOf(
                    "MOZ_ENABLE_WAYLAND" to "1",
                    "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
                    "MOZ_DISABLE_GMP_SANDBOX" to "1",
                    "MOZ_DISABLE_GPU_SANDBOX" to "1",
                    "MOZ_DISABLE_RDD_SANDBOX" to "1",
                    "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX" to "1",
                    "MOZ_DISABLE_UTILITY_SANDBOX" to "1"
                ),
        )
}
