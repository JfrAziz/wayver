package dev.wayver.app.distro.chimera

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ChimeraSway {
    private val packages =
        listOf(
            "sway", "fuzzel", "foot", "thunar", "fonts-dejavu-ttf",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "libpulse", "tzdata"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "sway",
            name = "Sway",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/chimera/sway", File(cfgRoot, "sway"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "sway/wallpaper.jpg"))
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
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=sway
                exec dbus-run-session -- sway
            """.trimIndent()
        )
}
