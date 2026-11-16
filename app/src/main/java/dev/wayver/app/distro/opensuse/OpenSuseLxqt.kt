package dev.wayver.app.distro.opensuse

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object OpenSuseLxqt {
    private val zypperCmd =
        "zypper --gpg-auto-import-keys --non-interactive refresh && zypper --non-interactive install --no-recommends"
    private val packages =
        listOf(
            "labwc", "lxqt-panel", "pcmanfm-qt", "qterminal", "swaybg",
            "adwaita-icon-theme", "dejavu-fonts", "dbus-1", "dbus-1-daemon", "dbus-1-tools", "fastfetch",
            "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "lxqt",
            name = "LXQt (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/opensuse/lxqt", File(cfgRoot, "lxqt"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "lxqt/wallpaper.jpg"))
                ctx.runInProot("$zypperCmd $packages") == 0
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
