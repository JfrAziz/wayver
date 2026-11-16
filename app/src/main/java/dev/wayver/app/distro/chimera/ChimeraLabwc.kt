package dev.wayver.app.distro.chimera

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ChimeraLabwc {
    private val packages =
        listOf(
            "labwc", "foot", "thunar", "fonts-dejavu-ttf",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "libpulse", "tzdata"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/chimera/labwc", File(cfgRoot, "labwc"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
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
                export XDG_CURRENT_DESKTOP=wlroots
                exec dbus-run-session -- labwc
            """.trimIndent()
        )
}
