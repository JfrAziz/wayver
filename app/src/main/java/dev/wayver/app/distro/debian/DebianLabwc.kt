package dev.wayver.app.distro.debian

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object DebianLabwc {
    private val aptPrefix = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"
    private val packages =
        listOf(
            "labwc", "foot", "pcmanfm", "fonts-dejavu-core",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus-x11", "fastfetch",
            "pulseaudio-utils", "libasound2-plugins"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { context, rootfsDir ->
                val cfgRoot = File(rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(context, "distros/debian/labwc", cfgRoot)
                AssetCopy.copyAssetFileIfMissing(context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
            },
            launch =
            """
                if [ ! -x /usr/bin/labwc ] || [ ! -x /usr/bin/waybar ] || [ ! -x /usr/bin/swaybg ] || [ ! -x /usr/bin/dbus-run-session ] || [ ! -x /usr/bin/pactl ]; then
                    $aptPrefix update && $aptPrefix install -y --no-install-recommends $packages
                fi
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export XDG_CURRENT_DESKTOP=wlroots
                exec dbus-run-session -- labwc
            """.trimIndent()
        )
}
