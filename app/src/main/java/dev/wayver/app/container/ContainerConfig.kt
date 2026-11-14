package dev.wayver.app.container

import kotlin.math.roundToInt

enum class ResolutionPreset(
    val id: String,
    val label: String,
    val nativeScale: Float? = null,
    val fixedWidth: Int = 0,
    val fixedHeight: Int = 0
) {
    NATIVE("native", "Native (1x)", nativeScale = 1.0f),
    NATIVE_0_5("native_0_5", "Native @ 0.5x", nativeScale = 0.5f),
    NATIVE_0_75("native_0_75", "Native @ 0.75x", nativeScale = 0.75f),
    NATIVE_1_25("native_1_25", "Native @ 1.25x", nativeScale = 1.25f),
    NATIVE_1_5("native_1_5", "Native @ 1.50x", nativeScale = 1.5f),
    NATIVE_1_75("native_1_75", "Native @ 1.75x", nativeScale = 1.75f),
    NATIVE_2("native_2", "Native @ 2x", nativeScale = 2.0f),
    FHD("1080p", "FHD (1920 x 1080)", fixedWidth = 1920, fixedHeight = 1080),
    WXGA("800p", "WXGA (1280 x 800)", fixedWidth = 1280, fixedHeight = 800),
    HD("720p", "HD (1280 x 720)", fixedWidth = 1280, fixedHeight = 720)
    ;

    fun resolve(nativeW: Int, nativeH: Int): Pair<Int, Int> {
        val scale = nativeScale ?: return fixedWidth to fixedHeight
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
 * Discriminated union for container display resolution.
 * Wayland output scale is always 1; presets and custom entries control the virtual display size directly.
 */
sealed interface ResolutionConfig {
    data class Preset(val preset: ResolutionPreset) : ResolutionConfig

    data class Custom(val width: Int, val height: Int) : ResolutionConfig

    fun resolve(nativeW: Int, nativeH: Int): Pair<Int, Int> = when (this) {
        is Preset -> preset.resolve(nativeW, nativeH)
        is Custom -> width.coerceIn(320, 4096) to height.coerceIn(240, 4096)
    }

    fun label(): String = when (this) {
        is Preset -> preset.label
        is Custom -> "${width}x$height"
    }
}

/**
 * Discriminated union for container Vulkan driver selection.
 * Only [Imported] carries a `driverPath` — [System] and [BundledTurnip] carry no unused fields.
 */
sealed interface DriverConfig {
    data object System : DriverConfig

    data object BundledTurnip : DriverConfig

    data class Imported(val driverPath: String?) : DriverConfig

    fun label(): String = when (this) {
        is System -> "System Vulkan"
        is BundledTurnip -> "Bundled Maleoon-Turnip (Experimental)"
        is Imported -> "Imported Driver (.zip)"
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
    val driver: DriverConfig = DriverConfig.System,
    val resolution: ResolutionConfig = ResolutionConfig.Preset(ResolutionPreset.NATIVE)
)
