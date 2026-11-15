package dev.wayver.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light")
    ;

    fun next(): ThemeMode = when (this) {
        AUTO -> DARK
        DARK -> LIGHT
        LIGHT -> AUTO
    }

    @Composable
    fun isDark(): Boolean = when (this) {
        AUTO -> isSystemInDarkTheme()
        DARK -> true
        LIGHT -> false
    }

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

data class CatppuccinPalette(
    val isDark: Boolean,
    val crust: Color,
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderBright: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textLog: Color,
    val primary: Color,
    val primaryOn: Color,
    val primarySoft: Color,
    val accent: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color
)

/** Catppuccin Mocha (Dark) */
val mochaPalette =
    CatppuccinPalette(
        isDark = true,
        crust = Color(0xFF11111B),
        bg = Color(0xFF181825),
        surface = Color(0xFF1E1E2E),
        surfaceElevated = Color(0xFF262637),
        border = Color(0xFF313244),
        borderBright = Color(0xFF45475A),
        textPrimary = Color(0xFFCDD6F4),
        textMuted = Color(0xFFA6ADC8),
        textLog = Color(0xFFB4BEFE),
        primary = Color(0xFF89B4FA),
        primaryOn = Color(0xFF11111B),
        primarySoft = Color(0x2689B4FA),
        accent = Color(0xFFCBA6F7),
        success = Color(0xFFA6E3A1),
        successSoft = Color(0x22A6E3A1),
        danger = Color(0xFFF38BA8),
        dangerSoft = Color(0x22F38BA8),
        warning = Color(0xFFF9E2AF)
    )

/** Catppuccin Latte (Light) */
val lattePalette =
    CatppuccinPalette(
        isDark = false,
        crust = Color(0xFFDCE0E8),
        bg = Color(0xFFE6E9EF),
        surface = Color(0xFFEFF1F5),
        surfaceElevated = Color(0xFFFFFFFF),
        border = Color(0xFFCCD0DA),
        borderBright = Color(0xFFBCC0CC),
        textPrimary = Color(0xFF4C4F69),
        textMuted = Color(0xFF6C6F85),
        textLog = Color(0xFF1E66F5),
        primary = Color(0xFF1E66F5),
        primaryOn = Color(0xFFFFFFFF),
        primarySoft = Color(0x1F1E66F5),
        accent = Color(0xFF8839EF),
        success = Color(0xFF40A02B),
        successSoft = Color(0x2240A02B),
        danger = Color(0xFFD20F39),
        dangerSoft = Color(0x1FD20F39),
        warning = Color(0xFFDF8E1D)
    )

private val LocalPalette = staticCompositionLocalOf { mochaPalette }

object AppColors {
    val crust: Color @Composable get() = LocalPalette.current.crust
    val bg: Color @Composable get() = LocalPalette.current.bg
    val surface: Color @Composable get() = LocalPalette.current.surface
    val surfaceElevated: Color @Composable get() = LocalPalette.current.surfaceElevated
    val border: Color @Composable get() = LocalPalette.current.border
    val borderBright: Color @Composable get() = LocalPalette.current.borderBright
    val textPrimary: Color @Composable get() = LocalPalette.current.textPrimary
    val textMuted: Color @Composable get() = LocalPalette.current.textMuted
    val textLog: Color @Composable get() = LocalPalette.current.textLog
    val primary: Color @Composable get() = LocalPalette.current.primary
    val primaryOn: Color @Composable get() = LocalPalette.current.primaryOn
    val primarySoft: Color @Composable get() = LocalPalette.current.primarySoft
    val accent: Color @Composable get() = LocalPalette.current.accent
    val success: Color @Composable get() = LocalPalette.current.success
    val successSoft: Color @Composable get() = LocalPalette.current.successSoft
    val danger: Color @Composable get() = LocalPalette.current.danger
    val dangerSoft: Color @Composable get() = LocalPalette.current.dangerSoft
    val warning: Color @Composable get() = LocalPalette.current.warning
}

@Composable
fun WayverTheme(themeMode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val isDark = themeMode.isDark()
    val palette = if (isDark) mochaPalette else lattePalette
    val colorScheme = palette.toColorScheme(isDark)

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

private fun CatppuccinPalette.toColorScheme(isDark: Boolean) = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = primaryOn,
        secondary = accent,
        background = bg,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textMuted,
        outline = borderBright,
        outlineVariant = border,
        error = danger
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = primaryOn,
        secondary = accent,
        background = bg,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textMuted,
        outline = borderBright,
        outlineVariant = border,
        error = danger
    )
}
