package dev.wayver.app.distro.debian

import dev.wayver.app.R
import dev.wayver.app.distro.Distro

object Debian : Distro {
    override val id = "debian"
    override val name = "Debian Trixie"
    override val iconRes = R.drawable.ic_distro_debian
    override val rootfsUrl =
        "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/" +
            "dist-arm64v8/trixie/oci/blobs/rootfs.tar.gz"
    override val markerRelPath = "etc/debian_version"
    override val shellPath = "/bin/bash"

    override val desktops =
        listOf(
            DebianLabwc.spec,
            DebianFoot.spec
        )
}
