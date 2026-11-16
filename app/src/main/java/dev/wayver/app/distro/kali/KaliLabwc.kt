package dev.wayver.app.distro.kali

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object KaliLabwc {
    private val aptPrefix = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"
    private val packages =
        listOf(
            "labwc", "foot", "pcmanfm", "fonts-dejavu-core",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus-x11", "fastfetch",
            "pulseaudio-utils", "libasound2-plugins"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "labwc",
            name = "LabWC Desktop",
            prepare = { ctx ->
                val cfgRoot = File(ctx.rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(ctx.context, "distros/kali/labwc", File(cfgRoot, "labwc"))
                AssetCopy.copyAssetFileIfMissing(ctx.context, "wallpaper.jpg", File(cfgRoot, "labwc/wallpaper.jpg"))
                ctx.runInProot(
                    "p=$(ls -1 /usr/bin/perl5.*.* 2>/dev/null | head -n 1); " +
                        "[ -e /usr/bin/perl ] || [ -z \"\$p\" ] || ln -sf \"\$p\" /usr/bin/perl; " +
                        "DEBIAN_FRONTEND=noninteractive dpkg --configure -a && " +
                        "$aptPrefix update && $aptPrefix install -y --no-install-recommends $packages"
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
