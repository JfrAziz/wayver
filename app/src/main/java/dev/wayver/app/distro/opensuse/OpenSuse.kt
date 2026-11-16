package dev.wayver.app.distro.opensuse

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object OpenSuse : Distro {
    override val id = "opensuse"
    override val name = "openSUSE Tumbleweed"
    override val iconRes = R.drawable.ic_distro_opensuse
    override val rootfsUrl =
        "https://download.opensuse.org/ports/aarch64/tumbleweed/appliances/opensuse-tumbleweed-image.aarch64-lxc.tar.xz"
    override val markerRelPath = "etc/os-release"
    override val shellPath = "/bin/bash"
    override val extraEnv = OpenSuseEnv.containerEnv
    override val appEnvs = OpenSuseEnv.appEnvs

    override val desktops =
        listOf(
            OpenSuseLabwc.spec,
            OpenSuseSway.spec,
            OpenSuseXfce.spec,
            OpenSuseLxqt.spec,
            OpenSuseFoot.spec
        )
}
