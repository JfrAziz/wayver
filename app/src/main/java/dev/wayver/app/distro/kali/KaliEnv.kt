package dev.wayver.app.distro.kali

/**
 * Kali-specific container and per-app environment overrides.
 */
object KaliEnv {
    val containerEnv: Map<String, String> =
        mapOf(
            "GLYCIN_DISABLE_SANDBOX" to "i-know-the-risks"
        )

    val appEnvs: Map<String, Map<String, String>> =
        mapOf(
            // Force Wayland and disable seccomp/namespace child sandboxes because glibc Firefox's
            // sandbox conflicts with PRoot ptrace.
            "firefox" to
                mapOf(
                    "MOZ_ENABLE_WAYLAND" to "1",
                    "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
                    "MOZ_DISABLE_GMP_SANDBOX" to "1",
                    "MOZ_DISABLE_GPU_SANDBOX" to "1",
                    "MOZ_DISABLE_RDD_SANDBOX" to "1",
                    "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX" to "1",
                    "MOZ_DISABLE_UTILITY_SANDBOX" to "1"
                )
        )
}
