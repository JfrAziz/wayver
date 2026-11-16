package dev.wayver.app.distro.voidlinux

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object VoidRiver {
    private val packages =
        listOf(
            "river",
            "fuzzel",
            "foot",
            "pcmanfm",
            "dejavu-fonts-ttf",
            "Waybar",
            "swaybg",
            "adwaita-icon-theme",
            "dbus",
            "fastfetch",
            "pulseaudio-utils",
            "alsa-plugins-pulseaudio"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "river",
            name = "River",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/void/river", File(cfgRoot, "river"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "river/wallpaper.jpg"))
                File(cfgRoot, "river/init").setExecutable(true, false)
                ctx.runInProot("xbps-install -Suy xbps && xbps-install -Suy && xbps-install -Sy $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=river
                exec dbus-run-session -- river -c /root/.config/river/init
            """.trimIndent()
        )
}
