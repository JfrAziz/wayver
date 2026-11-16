package dev.wayver.app.distro.opensuse

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object OpenSuseXfce {
    private val zypperCmd =
        "zypper --gpg-auto-import-keys --non-interactive refresh && zypper --non-interactive install --no-recommends"
    private val packages =
        listOf(
            "labwc", "xfce4-panel", "xfce4-session", "xfconf", "thunar",
            "xfce4-terminal", "swaybg", "adwaita-icon-theme", "dejavu-fonts",
            "dbus-1", "dbus-1-daemon", "dbus-1-tools", "fastfetch", "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "xfce",
            name = "XFCE (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/opensuse/xfce", File(cfgRoot, "xfce"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "xfce/wallpaper.jpg"))
                ctx.runInProot("$zypperCmd $packages") == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export XDG_CURRENT_DESKTOP=XFCE
                exec dbus-run-session -- labwc -C /root/.config/xfce
            """.trimIndent()
        )
}
