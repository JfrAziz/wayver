package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineLabwc {
    private val packages =
        listOf(
            "labwc", "foot", "pcmanfm", "font-dejavu",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { context, rootfsDir ->
                val cfgRoot = File(rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(context, "distros/alpine/labwc", cfgRoot)
                AssetCopy.copyAssetFileIfMissing(context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
            },
            launch =
            """
                if [ ! -x /usr/bin/labwc ] || [ ! -x /usr/bin/waybar ] || [ ! -x /usr/bin/swaybg ] || [ ! -x /usr/bin/dbus-run-session ] || [ ! -x /usr/bin/pactl ]; then
                    apk update && apk add --no-cache $packages
                fi
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export XDG_CURRENT_DESKTOP=wlroots
                exec dbus-run-session -- labwc
            """.trimIndent()
        )
}
