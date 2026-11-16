package dev.wayver.app.distro.opensuse

/**
 * openSUSE-specific container and per-app environment overrides.
 */
object OpenSuseEnv {
    val containerEnv: Map<String, String> =
        mapOf(
            "ZYPP_CURL2" to "0",
            "GLYCIN_DISABLE_SANDBOX" to "i-know-the-risks"
        )

    val appEnvs: Map<String, Map<String, String>> =
        mapOf(
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
