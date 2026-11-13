package dev.wayver.app.distro.arch

import dev.wayver.app.R
import dev.wayver.app.distro.Distro
import java.io.File

object Arch : Distro {
    override val id = "arch"
    override val name = "Arch Linux ARM"
    override val iconRes = R.drawable.ic_distro_arch
    override val rootfsUrl = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
    override val markerRelPath = "etc/arch-release"
    override val shellPath = "/bin/bash"

    override val desktops =
        listOf(
            ArchLabwc.spec,
            ArchFoot.spec
        )

    override fun prepareRootfs(rootfsDir: File) {
        val lockFile = File(rootfsDir, "var/lib/pacman/db.lck")
        if (lockFile.exists()) {
            lockFile.delete()
        }
        val pacmanConf = File(rootfsDir, "etc/pacman.conf")
        if (!pacmanConf.exists()) return
        var text = pacmanConf.readText()
        var changed = false
        if (text.contains("#DisableSandboxFilesystem")) {
            text = text.replace("#DisableSandboxFilesystem", "DisableSandboxFilesystem")
            changed = true
        }
        if (text.contains("#DisableSandboxSyscalls")) {
            text = text.replace("#DisableSandboxSyscalls", "DisableSandboxSyscalls")
            changed = true
        }
        if (text.contains("#DisableSandbox\n") || text.contains("#DisableSandbox\r")) {
            text = text.replace("#DisableSandbox", "DisableSandbox")
            changed = true
        }
        if (!text.contains("\nDisableSandbox\n") && !text.contains("\nDisableSandboxFilesystem")) {
            text = text.replace("[options]", "[options]\nDisableSandbox")
            changed = true
        }
        if (text.contains("\nCheckSpace")) {
            text = text.replace("\nCheckSpace", "\n#CheckSpace")
            changed = true
        }
        if (changed) {
            pacmanConf.writeText(text)
        }
    }
}
