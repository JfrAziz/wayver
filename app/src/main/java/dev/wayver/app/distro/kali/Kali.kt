package dev.wayver.app.distro.kali

import dev.wayver.app.R
import dev.wayver.app.distro.Distro
import dev.wayver.app.utils.TarExtractor
import java.io.File
import java.io.InputStream

object Kali : Distro {
    override val id = "kali"
    override val name = "Kali Linux"
    override val iconRes = R.drawable.ic_distro_kali

    override val rootfsUrl =
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"

    override val relPath = "etc/os-release"
    override val shellPath = "/bin/bash"

    override val appEnvs = KaliEnv.appEnvs
    override val extraEnv = KaliEnv.containerEnv

    override val desktops =
        listOf(
            KaliLabwc.spec,
            KaliXfce.spec,
            KaliFoot.spec
        )

    override fun extractRootfs(stream: InputStream, rootfsDir: File) {
        TarExtractor.extract(stream, rootfsDir)

        val nested = File(rootfsDir, "kali-arm64")

        if (nested.isDirectory) {
            nested.listFiles()?.forEach { child ->
                child.renameTo(File(rootfsDir, child.name))
            }
            nested.delete()
        }
    }
}
