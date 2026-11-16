package dev.wayver.app.distro.ubuntu

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object Ubuntu : Distro {
    override val id = "ubuntu"
    override val name = "Ubuntu Noble"
    override val iconRes = R.drawable.ic_distro_ubuntu

    override val rootfsUrl =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"

    override val relPath = "etc/os-release"
    override val shellPath = "/bin/bash"

    override val appEnvs = UbuntuEnv.appEnvs
    override val extraEnv = UbuntuEnv.containerEnv

    override val desktops =
        listOf(
            UbuntuLabwc.spec,
            UbuntuFoot.spec
        )
}
