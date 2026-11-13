package dev.wayver.app.distro

import dev.wayver.app.distro.alpine.Alpine
import dev.wayver.app.distro.arch.Arch
import dev.wayver.app.distro.debian.Debian
import dev.wayver.app.distro.ubuntu.Ubuntu

object DistroRegistry {
    val arch: Distro = Arch
    val alpine: Distro = Alpine
    val debian: Distro = Debian
    val ubuntu: Distro = Ubuntu

    val all: List<Distro> = listOf(alpine, debian, ubuntu, arch)

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: alpine
}
