package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineXfce {
    private val packages =
        listOf(
            "labwc", "xfce4-panel", "xfce4-session", "xfconf", "thunar",
            "xfce4-terminal", "swaybg", "adwaita-icon-theme", "font-dejavu",
            "dbus", "fastfetch", "pulseaudio-utils", "alsa-plugins-pulse"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "xfce",
            name = "XFCE (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/alpine/xfce", File(cfgRoot, "xfce"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "xfce/wallpaper.jpg"))
                ctx.runInProot("apk update && apk add --no-cache $packages") == 0
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
