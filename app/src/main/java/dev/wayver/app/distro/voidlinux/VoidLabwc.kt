package dev.wayver.app.distro.voidlinux

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object VoidLabwc {
    private val packages =
        listOf(
            "labwc", "foot", "pcmanfm", "dejavu-fonts-ttf",
            "Waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulseaudio"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/void/labwc", File(cfgRoot, "labwc"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
                ctx.runInProot("xbps-install -Suy xbps && xbps-install -Suy && xbps-install -Sy $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export XDG_CURRENT_DESKTOP=wlroots
                exec dbus-run-session -- labwc
            """.trimIndent()
        )
}
