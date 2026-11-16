package dev.wayver.app.distro.chimera

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ChimeraXfce {
    private val packages =
        listOf(
            "labwc", "xfce4-panel", "xfce4-session", "xfconf", "thunar",
            "xfce4-terminal", "swaybg", "adwaita-icon-theme", "fonts-dejavu-ttf",
            "dbus", "fastfetch", "libpulse", "tzdata"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "xfce",
            name = "XFCE (Wayland)",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/chimera/xfce", File(cfgRoot, "xfce"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "xfce/wallpaper.jpg"))
                ctx.runInProot(
                    "apk update && apk add --no-interactive chimera-repo-user && " +
                        "apk update && apk add --no-interactive $packages"
                ) == 0
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
