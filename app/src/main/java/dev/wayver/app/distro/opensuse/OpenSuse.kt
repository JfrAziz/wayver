package dev.wayver.app.distro.opensuse

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object OpenSuse : Distro {
    override val id = "opensuse"
    override val name = "openSUSE Tumbleweed"
    override val iconRes = R.drawable.ic_distro_opensuse

    override val rootfsUrl =
        "https://download.opensuse.org/ports/aarch64/tumbleweed/appliances/opensuse-tumbleweed-image.aarch64-lxc.tar.xz"

    override val relPath = "etc/os-release"
    override val shellPath = "/bin/bash"

    override val appEnvs = OpenSuseEnv.appEnvs
    override val extraEnv = OpenSuseEnv.containerEnv

    override val desktops =
        listOf(
            OpenSuseSway.spec,
            OpenSuseLabwc.spec,
            OpenSuseXfce.spec,
            OpenSuseLxqt.spec,
            OpenSuseFoot.spec
        )
}
