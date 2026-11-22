package dev.wayver.app.container

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object ScreenScale {
    const val MIN = 0.5f
    const val MAX = 2.0f
    const val STEP = 0.05f
    const val STEPS = 29

    fun clamp(scale: Float): Float {
        val snapped = ((scale / STEP).roundToInt() * STEP)
        return snapped.coerceIn(MIN, MAX)
    }

    fun isNative(scale: Float): Boolean = abs(scale - 1.0f) < 0.01f

    fun formatLabel(scale: Float): String = if (isNative(scale)) {
        "Native (1.00x)"
    } else {
        "Native @ ${String.format(Locale.US, "%.2f", scale)}x"
    }

    fun resolve(nativeW: Int, nativeH: Int, scale: Float): Pair<Int, Int> {
        if (nativeW <= 0 || nativeH <= 0) return 0 to 0
        val s = clamp(scale)
        // Divide native resolution by UI scale and round to even dimensions for swapchain alignment.
        val w = (((nativeW / s).roundToInt() / 2) * 2).coerceIn(320, 4096)
        val h = (((nativeH / s).roundToInt() / 2) * 2).coerceIn(240, 4096)
        return w to h
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
    val resolutionScale: Float = 1.0f
)
