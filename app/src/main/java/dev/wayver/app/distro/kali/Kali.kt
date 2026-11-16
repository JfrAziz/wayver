package dev.wayver.app.distro.kali

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object Kali : Distro {
    override val id = "kali"
    override val name = "Kali Linux"
    override val iconRes = R.drawable.ic_distro_kali
    override val rootfsUrl =
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
    override val rootfsStripComponents = 1
    override val markerRelPath = "etc/os-release"
    override val shellPath = "/bin/bash"
    override val extraEnv = KaliEnv.containerEnv
    override val appEnvs = KaliEnv.appEnvs

    override val desktops =
        listOf(
            KaliLabwc.spec,
            KaliXfce.spec,
            KaliFoot.spec
        )
}
