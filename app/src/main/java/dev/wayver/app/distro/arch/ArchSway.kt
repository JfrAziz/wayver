package dev.wayver.app.distro.arch

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ArchSway {
    private val packages =
        listOf(
            "sway", "autotiling", "fuzzel", "foot", "pcmanfm", "ttf-dejavu",
            "waybar", "swaybg", "adwaita-icon-theme", "dbus", "fastfetch",
            "libpulse", "pulseaudio-alsa"
        ).joinToString(" ")

    private val pacmanPrep =
        """
        rm -f /var/lib/pacman/db.lck
        if [ ! -d /etc/pacman.d/gnupg/private-keys-v1.d ]; then
            pacman-key --init && pacman-key --populate archlinuxarm
        fi
        pacman -Sy --noconfirm
        """.trimIndent()

    val spec =
        DesktopSpec(
            id = "sway",
            name = "Sway",
            prepare = { context, rootfsDir ->
                val cfgRoot = File(rootfsDir, "root/.config")
                AssetCopy.copyAssetTree(context, "distros/arch/labwc", cfgRoot)
                AssetCopy.copyAssetTree(context, "distros/arch/sway", cfgRoot)
                AssetCopy.copyAssetFileIfMissing(context, "wallpaper.jpg", File(cfgRoot, "sway/wallpaper.jpg"))
            },
            launch =
            """
                if [ ! -x /usr/bin/sway ] || [ ! -x /usr/bin/autotiling ] || [ ! -x /usr/bin/fuzzel ] || [ ! -x /usr/bin/waybar ] || [ ! -x /usr/bin/swaybg ] || [ ! -x /usr/bin/dbus-run-session ] || [ ! -x /usr/bin/pactl ]; then
                    $pacmanPrep && pacman -S --noconfirm --needed $packages
                fi
                export XDG_CONFIG_HOME=/root/.config
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=sway
                export GLYCIN_DISABLE_SANDBOX=i-know-the-risks
                exec dbus-run-session -- sway
            """.trimIndent()
        )
}
