package dev.wayver.app.distro.chimera

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object Chimera : Distro {
    override val id = "chimera"
    override val name = "Chimera Linux"
    override val iconRes = R.drawable.ic_distro_chimera

    override val rootfsUrl =
        "https://repo.chimera-linux.org/live/latest/chimera-linux-aarch64-ROOTFS-20261103-bootstrap.tar.gz"

    override val relPath = "etc/os-release"
    override val shellPath = "/bin/sh"

    override val appEnvs = ChimeraEnv.appEnvs
    override val extraEnv = ChimeraEnv.containerEnv

    override val desktops =
        listOf(
            ChimeraLabwc.spec,
            ChimeraSway.spec,
            ChimeraXfce.spec,
            ChimeraFoot.spec
        )
}
