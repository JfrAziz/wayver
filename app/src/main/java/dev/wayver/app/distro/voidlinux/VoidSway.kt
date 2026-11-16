package dev.wayver.app.distro.voidlinux

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object VoidSway {
    private val packages =
        listOf(
            "sway", "autotiling", "fuzzel", "foot", "pcmanfm", "dejavu-fonts-ttf",
            "Waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulseaudio"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "sway",
            name = "Sway",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/void/sway", File(cfgRoot, "sway"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "sway/wallpaper.jpg"))
                ctx.runInProot("xbps-install -Suy xbps && xbps-install -Suy && xbps-install -Sy $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=sway
                exec dbus-run-session -- sway
            """.trimIndent()
        )
}
