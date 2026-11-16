package dev.wayver.app.distro.opensuse

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object OpenSuseLabwc {
    private val zypperCmd =
        "zypper --gpg-auto-import-keys --non-interactive refresh && zypper --non-interactive install --no-recommends"
    private val packages =
        listOf(
            "labwc", "foot", "pcmanfm", "dejavu-fonts",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus-1", "dbus-1-daemon", "dbus-1-tools", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/opensuse/labwc", File(cfgRoot, "labwc"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
                ctx.runInProot("$zypperCmd $packages") == 0
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
