package dev.wayver.app.distro

import dev.wayver.app.distro.alpine.Alpine
import dev.wayver.app.distro.arch.Arch
import dev.wayver.app.distro.chimera.Chimera
import dev.wayver.app.distro.debian.Debian
import dev.wayver.app.distro.kali.Kali
import dev.wayver.app.distro.opensuse.OpenSuse
import dev.wayver.app.distro.ubuntu.Ubuntu
import dev.wayver.app.distro.voidlinux.VoidLinux

object DistroRegistry {
    val arch: Distro = Arch
    val alpine: Distro = Alpine
    val debian: Distro = Debian
    val ubuntu: Distro = Ubuntu
    val voidLinux: Distro = VoidLinux
    val kali: Distro = Kali
    val openSuse: Distro = OpenSuse
    val chimera: Distro = Chimera

    val all: List<Distro> = listOf(alpine, debian, ubuntu, arch, voidLinux, kali, openSuse, chimera)

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: alpine
}
