package dev.wayver.app.distro.voidlinux

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object VoidXfce {
    private val packages =
        listOf(
            "labwc", "xfce4-panel", "xfce4-session", "xfconf", "Thunar",
            "xfce4-terminal", "swaybg", "adwaita-icon-theme", "dejavu-fonts-ttf",
            "dbus", "fastfetch", "pulseaudio-utils", "alsa-plugins-pulseaudio"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "xfce",
            name = "XFCE (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/void/xfce", File(cfgRoot, "xfce"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "xfce/wallpaper.jpg"))
                ctx.runInProot("xbps-install -Suy xbps && xbps-install -Suy && xbps-install -Sy $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export XDG_CURRENT_DESKTOP=XFCE
                exec dbus-run-session -- labwc -C /root/.config/xfce
            """.trimIndent()
        )
}
