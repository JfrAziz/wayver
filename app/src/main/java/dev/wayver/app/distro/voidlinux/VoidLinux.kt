package dev.wayver.app.distro.voidlinux

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object VoidLinux : Distro {
    override val id = "void"
    override val name = "Void Linux"
    override val iconRes = R.drawable.ic_distro_void
    override val rootfsUrl =
        "https://repo-default.voidlinux.org/live/current/void-aarch64-ROOTFS-20250202.tar.xz"
    override val markerRelPath = "etc/os-release"
    override val shellPath = "/bin/bash"
    override val extraEnv = VoidEnv.containerEnv
    override val appEnvs = VoidEnv.appEnvs

    override val desktops =
        listOf(
            VoidLabwc.spec,
            VoidSway.spec,
            VoidXfce.spec,
            VoidLxqt.spec,
            VoidRiver.spec,
            VoidFoot.spec
        )
}
