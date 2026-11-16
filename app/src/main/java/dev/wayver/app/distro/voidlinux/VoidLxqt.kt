package dev.wayver.app.distro.voidlinux

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object VoidLxqt {
    private val packages =
        listOf(
            "labwc", "lxqt-panel", "pcmanfm-qt", "qterminal", "swaybg",
            "adwaita-icon-theme", "dejavu-fonts-ttf", "dbus", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulseaudio"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "lxqt",
            name = "LXQt (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/void/lxqt", File(cfgRoot, "lxqt"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "lxqt/wallpaper.jpg"))
                ctx.runInProot("xbps-install -Suy xbps && xbps-install -Suy && xbps-install -Sy $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export QT_QPA_PLATFORM=wayland
                export XDG_CURRENT_DESKTOP=LXQt
                exec dbus-run-session -- labwc -C /root/.config/lxqt
            """.trimIndent()
        )
}
