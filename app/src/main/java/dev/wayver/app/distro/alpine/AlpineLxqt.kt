package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineLxqt {
    private val packages =
        listOf(
            "labwc", "lxqt-panel", "pcmanfm-qt", "qterminal", "swaybg",
            "adwaita-icon-theme", "font-dejavu", "dbus", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "lxqt",
            name = "LXQt (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/alpine/lxqt", File(cfgRoot, "lxqt"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "lxqt/wallpaper.jpg"))
                ctx.runInProot("apk update && apk add --no-cache $packages") == 0
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
