package dev.wayver.app.distro.arch

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ArchRiver {
    private val packages =
        listOf(
            "river-classic",
            "fuzzel",
            "foot",
            "pcmanfm",
            "ttf-dejavu",
            "waybar",
            "swaybg",
            "adwaita-icon-theme",
            "dbus",
            "fastfetch",
            "libpulse",
            "pulseaudio-alsa"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "river",
            name = "River",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/arch/river", File(cfgRoot, "river"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "river/wallpaper.jpg"))
                File(cfgRoot, "river/init").setExecutable(true, false)
                ctx.runInProot("${Arch.pacmanPrep} && pacman -Syu --noconfirm --needed $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=river
                export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
                exec dbus-run-session -- river -c /root/.config/river/init
            """.trimIndent()
        )
}
