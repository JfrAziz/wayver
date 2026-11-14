package dev.wayver.app.distro.alpine

/**
 * Alpine-specific container and per-app environment overrides.
 * Alpine's musl Firefox build runs without needing PRoot sandbox workarounds.
 */
object AlpineEnv {
    val containerEnv: Map<String, String> = emptyMap()
    val appEnvs: Map<String, Map<String, String>> = emptyMap()
}
