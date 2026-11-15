package dev.wayver.app.container

import kotlin.math.roundToInt

enum class ResolutionPreset(val id: String, val label: String, val scale: Float) {
    NATIVE("native", "Native (1x)", 1.0f),
    NATIVE_0_5("native_0_5", "Native @ 0.5x", 0.5f),
    NATIVE_0_75("native_0_75", "Native @ 0.75x", 0.75f),
    NATIVE_1_25("native_1_25", "Native @ 1.25x", 1.25f),
    NATIVE_1_5("native_1_5", "Native @ 1.50x", 1.5f),
    NATIVE_1_75("native_1_75", "Native @ 1.75x", 1.75f),
    NATIVE_2("native_2", "Native @ 2x", 2.0f)
    ;

    fun resolve(nativeW: Int, nativeH: Int): Pair<Int, Int> {
        if (nativeW <= 0 || nativeH <= 0) return 0 to 0
        // Divide native resolution by UI scale and round to even dimensions for swapchain alignment.
        val w = (((nativeW / scale).roundToInt() / 2) * 2).coerceIn(320, 4096)
        val h = (((nativeH / scale).roundToInt() / 2) * 2).coerceIn(240, 4096)
        return w to h
    }

    companion object {
        fun fromId(id: String?): ResolutionPreset = entries.firstOrNull { it.id == id } ?: NATIVE
    }
}

/**
 * Per-container environment configuration layered on top of distro defaults:
 * distro base env -> enabled verified app presets -> user overrides.
 */
data class EnvConfig(
    val overrides: Map<String, String> = emptyMap(),
    val disabledPresets: Set<String> = emptySet(),
    val presetEdits: Map<String, Map<String, String>> = emptyMap()
) {
    fun resolve(
        base: Map<String, String> = emptyMap(),
        presets: Map<String, Map<String, String>> = emptyMap()
    ): Map<String, String> = buildMap {
        putAll(base)
        for ((app, defaultVars) in presets) {
            if (app !in disabledPresets) {
                putAll(presetEdits[app] ?: defaultVars)
            }
        }
        putAll(overrides)
    }
}

data class ContainerConfig(
    val id: String,
    val name: String,
    val distroId: String,
    val desktopId: String = "labwc",
    val env: EnvConfig = EnvConfig(),
    val resolution: ResolutionPreset = ResolutionPreset.NATIVE
)
