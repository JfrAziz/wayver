package dev.wayver.app.distro.debian

import dev.wayver.app.distro.DesktopSpec

object DebianPhosh {
    private val aptPrefix = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"
    private val packages =
        listOf(
            "phosh",
            "phoc",
            "gnome-settings-daemon-common",
            "foot",
            "fonts-dejavu-core",
            "adwaita-icon-theme",
            "dbus-x11",
            "fastfetch",
            "pulseaudio-utils",
            "libasound2-plugins"
        ).joinToString(" ")

    val spec =
        DesktopSpec(
            id = "phosh",
            name = "Phosh (Mobile)",
            prepare = { ctx ->
                ctx.runInProot("$aptPrefix update && $aptPrefix install -y --no-install-recommends $packages") == 0
            },
            launch =
            """
                rm -f /tmp/.X11-unix/X* /tmp/.X*-lock
                export XDG_CONFIG_HOME=/root/.config
                export XDG_SESSION_TYPE=wayland
                export GDK_BACKEND=wayland
                export GSK_RENDERER=cairo
                export WLR_BACKENDS=wayland
                export WLR_RENDERER=pixman
                export WLR_NO_HARDWARE_CURSORS=1
                export XDG_CURRENT_DESKTOP=Phosh:GNOME
                exec dbus-run-session -- phoc -E "/usr/libexec/phosh --unlocked"
            """.trimIndent()
        )
}
