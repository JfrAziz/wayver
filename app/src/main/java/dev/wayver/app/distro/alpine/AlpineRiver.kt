package dev.wayver.app.distro.alpine

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object AlpineRiver {
    private val packages =
        listOf(
            "fuzzel",
            "foot",
            "pcmanfm",
            "font-dejavu",
            "waybar",
            "swaybg",
            "adwaita-icon-theme",
            "dbus",
            "fastfetch",
            "pulseaudio-utils",
            "alsa-plugins-pulse"
        ).joinToString(" ")

    private const val EDGE_COMMUNITY_REPO = "https://dl-cdn.alpinelinux.org/alpine/edge/community"

    val spec =
        DesktopSpec(
            id = "river",
            name = "River",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/alpine/river", File(cfgRoot, "river"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "river/wallpaper.jpg"))
                File(cfgRoot, "river/init").setExecutable(true, false)
                ctx.runInProot(
                    "apk update && apk del river 2>/dev/null; " +
                        "apk add --no-cache $packages && " +
                        "apk add --no-cache --repository=$EDGE_COMMUNITY_REPO river-classic"
                ) == 0
            },
            launch =
            """
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=river
                exec dbus-run-session -- river -c /root/.config/river/init
            """.trimIndent()
        )
}
