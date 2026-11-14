package dev.wayver.app.distro.alpine

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object Alpine : Distro {
    override val id = "alpine"
    override val name = "Alpine 3.23"
    override val iconRes = R.drawable.ic_distro_alpine
    override val rootfsUrl =
        "https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.8-aarch64.tar.gz"
    override val markerRelPath = "etc/alpine-release"
    override val shellPath = "/bin/sh"
    override val extraEnv = AlpineEnv.containerEnv
    override val appEnvs = AlpineEnv.appEnvs

    override val desktops =
        listOf(
            AlpineLabwc.spec,
            AlpineSway.spec,
            AlpineFoot.spec
        )
}
