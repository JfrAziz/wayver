package dev.wayver.app.distro.alpine

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object Alpine : Distro {
    override val id = "alpine"
    override val name = "Alpine 3.20"
    override val iconRes = R.drawable.ic_distro_alpine
    override val rootfsUrl =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.10-aarch64.tar.gz"
    override val markerRelPath = "etc/alpine-release"
    override val shellPath = "/bin/sh"
    override val extraEnv = AlpineEnv.containerEnv
    override val appEnvs = AlpineEnv.appEnvs

    override val desktops =
        listOf(
            AlpineLabwc.spec,
            AlpineFoot.spec
        )
}
