package dev.wayver.app.distro.arch

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ArchLabwc {
    private val packages =
        listOf(
            "labwc", "foot", "pcmanfm", "ttf-dejavu",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "libpulse", "pulseaudio-alsa"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/arch/labwc", File(cfgRoot, "labwc"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
                ctx.runInProot("${Arch.pacmanPrep} && pacman -Syu --noconfirm --needed $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export XDG_CURRENT_DESKTOP=wlroots
                export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
                exec dbus-run-session -- labwc
            """.trimIndent()
        )
}
