package dev.wayver.app.distro.chimera

/**
 * Chimera-specific container and per-app environment overrides.
 */
object ChimeraEnv {
    val containerEnv: Map<String, String> =
        mapOf(
            "GLYCIN_DISABLE_SANDBOX" to "i-know-the-risks"
        )
    val appEnvs: Map<String, Map<String, String>> = emptyMap()
}
