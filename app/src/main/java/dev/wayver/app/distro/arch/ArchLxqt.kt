package dev.wayver.app.distro.arch

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ArchLxqt {
    private val packages =
        listOf(
            "labwc", "lxqt-panel", "pcmanfm-qt", "qterminal", "swaybg",
            "adwaita-icon-theme", "ttf-dejavu", "dbus", "fastfetch",
            "libpulse", "pulseaudio-alsa"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "lxqt",
            name = "LXQt (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/arch/lxqt", File(cfgRoot, "lxqt"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "lxqt/wallpaper.jpg"))
                ctx.runInProot("${Arch.pacmanPrep} && pacman -Syu --noconfirm --needed $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export QT_QPA_PLATFORM=wayland
                export XDG_CURRENT_DESKTOP=LXQt
                export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
                exec dbus-run-session -- labwc -C /root/.config/lxqt
            """.trimIndent()
        )
}
