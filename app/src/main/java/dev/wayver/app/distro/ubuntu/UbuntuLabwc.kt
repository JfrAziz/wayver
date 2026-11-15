package dev.wayver.app.distro.ubuntu

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object UbuntuLabwc {
    private val aptPrefix = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"
    private val packages =
        listOf(
            "labwc",
            "foot",
            "pcmanfm",
            "fonts-dejavu-core",
            "waybar",
            "swaybg",
            "adwaita-icon-theme",
            "dbus-x11",
            "pulseaudio-utils",
            "libasound2-plugins"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/ubuntu/labwc", cfgRoot)
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
                ctx.runInProot("$aptPrefix update && $aptPrefix install -y --no-install-recommends $packages") == 0
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
