package dev.wayver.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val accent: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val logText: Color
)

/** Catppuccin Mocha (Dark) */
val MochaPalette =
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
        primary = Color(0xFF89B4FA),
        onPrimary = Color(0xFF11111B),
        primarySoft = Color(0x2689B4FA),
        accent = Color(0xFFCBA6F7),
        success = Color(0xFFA6E3A1),
        successSoft = Color(0x22A6E3A1),
        danger = Color(0xFFF38BA8),
        dangerSoft = Color(0x22F38BA8),
        warning = Color(0xFFF9E2AF),
        logText = Color(0xFFB4BEFE)
    )

/** Catppuccin Latte (Light) */
val LattePalette =
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
        primary = Color(0xFF1E66F5),
        onPrimary = Color(0xFFFFFFFF),
        primarySoft = Color(0x1F1E66F5),
        accent = Color(0xFF8839EF),
        success = Color(0xFF40A02B),
        successSoft = Color(0x2240A02B),
        danger = Color(0xFFD20F39),
        dangerSoft = Color(0x1FD20F39),
        warning = Color(0xFFDF8E1D),
        logText = Color(0xFF1E66F5)
    )

private val LocalPalette = staticCompositionLocalOf { MochaPalette }

object AppColors {
    val Crust: Color @Composable get() = LocalPalette.current.crust
    val Bg: Color @Composable get() = LocalPalette.current.bg
    val Surface: Color @Composable get() = LocalPalette.current.surface
    val SurfaceElevated: Color @Composable get() = LocalPalette.current.surfaceElevated
    val Border: Color @Composable get() = LocalPalette.current.border
    val BorderBright: Color @Composable get() = LocalPalette.current.borderBright
    val TextPrimary: Color @Composable get() = LocalPalette.current.textPrimary
    val TextMuted: Color @Composable get() = LocalPalette.current.textMuted
    val Primary: Color @Composable get() = LocalPalette.current.primary
    val OnPrimary: Color @Composable get() = LocalPalette.current.onPrimary
    val PrimarySoft: Color @Composable get() = LocalPalette.current.primarySoft
    val Accent: Color @Composable get() = LocalPalette.current.accent
    val Success: Color @Composable get() = LocalPalette.current.success
    val SuccessSoft: Color @Composable get() = LocalPalette.current.successSoft
    val Danger: Color @Composable get() = LocalPalette.current.danger
    val DangerSoft: Color @Composable get() = LocalPalette.current.dangerSoft
    val Warning: Color @Composable get() = LocalPalette.current.warning
    val LogText: Color @Composable get() = LocalPalette.current.logText
}

@Composable
fun LinuxDesktopTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    syncSystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark =
        when (themeMode) {
            ThemeMode.AUTO -> isSystemInDarkTheme()
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    val palette = if (isDark) MochaPalette else LattePalette
    val colorScheme =
        if (isDark) {
            darkColorScheme(
                primary = palette.primary,
                onPrimary = palette.onPrimary,
                secondary = palette.accent,
                background = palette.bg,
                onBackground = palette.textPrimary,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.surfaceElevated,
                onSurfaceVariant = palette.textMuted,
                outline = palette.border,
                error = palette.danger
            )
        } else {
            lightColorScheme(
                primary = palette.primary,
                onPrimary = palette.onPrimary,
                secondary = palette.accent,
                background = palette.bg,
                onBackground = palette.textPrimary,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.surfaceElevated,
                onSurfaceVariant = palette.textMuted,
                outline = palette.border,
                error = palette.danger
            )
        }

    val view = LocalView.current
    if (syncSystemBars && !view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = palette.bg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = palette.bg.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
