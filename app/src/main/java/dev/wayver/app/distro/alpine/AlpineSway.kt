package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineSway {
    private val packages =
        listOf(
            "sway", "autotiling", "fuzzel", "foot", "pcmanfm", "font-dejavu",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "sway",
            name = "Sway",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/alpine/labwc", cfgRoot)
                AssetCopy.copyAssetTree(ctx.context, "distros/alpine/sway", cfgRoot)
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "sway/wallpaper.jpg"))
                ctx.runInProot("apk update && apk add --no-cache $packages") == 0
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
