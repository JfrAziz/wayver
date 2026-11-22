package dev.wayver.app.distro.arch

import dev.wayver.app.R
import dev.wayver.app.distro.Distro
import java.io.File

object Arch : Distro {
    override val id = "arch"
    override val name = "Arch Linux ARM"
    override val iconRes = R.drawable.ic_distro_arch

    override val rootfsUrl = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"

    override val relPath = "etc/arch-release"
    override val shellPath = "/bin/bash"

    override val appEnvs = ArchEnv.appEnvs
    override val extraEnv = ArchEnv.containerEnv

    override val desktops =
        listOf(
            ArchSway.spec,
            ArchLabwc.spec,
            ArchRiver.spec,
            ArchXfce.spec,
            ArchLxqt.spec,
            ArchFoot.spec
        )

    private val mirrors =
        listOf(
            "http://mirror.archlinuxarm.org",
            "http://tw.mirror.archlinuxarm.org",
            "http://de.mirror.archlinuxarm.org",
            "http://fl.us.mirror.archlinuxarm.org",
            "http://nj.us.mirror.archlinuxarm.org"
        )

    val pacmanPrep =
        """
        rm -f /var/lib/pacman/db.lck
        best=""
        best_t=999
        for m in ${mirrors.joinToString(" ")}; do
            t=$(curl -sL -o /dev/null -w "%{http_code} %{time_total}" --max-time 3 "${'$'}m/aarch64/core/core.db" 2>/dev/null)
            code=${'$'}{t%% *}
            sec=${'$'}{t#* }
            if [ "${'$'}code" = "200" ] && awk "BEGIN{exit !(${'$'}sec < ${'$'}best_t)}"; then
                best="${'$'}m"
                best_t="${'$'}sec"
            fi
        done
        if [ -n "${'$'}best" ]; then
            echo "Selected mirror: ${'$'}best (${'$'}best_t s)"
            printf 'Server = %s/${'$'}arch/${'$'}repo\nServer = http://mirror.archlinuxarm.org/${'$'}arch/${'$'}repo\n' "${'$'}best" > /etc/pacman.d/mirrorlist
        fi
        if [ ! -d /etc/pacman.d/gnupg/private-keys-v1.d ]; then
            pacman-key --init && pacman-key --populate archlinuxarm
        fi
        pacman -Syy --noconfirm
        """.trimIndent()

    override fun prepareRootfs(rootfsDir: File) {
        val lockFile = File(rootfsDir, "var/lib/pacman/db.lck")
        if (lockFile.exists())
            lockFile.delete()

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
        if (text.contains("#ParallelDownloads")) {
            text = text.replace("#ParallelDownloads = 5", "ParallelDownloads = 5")
            text = text.replace("#ParallelDownloads", "ParallelDownloads = 5")
            changed = true
        } else if (!text.contains("\nParallelDownloads")) {
            text = text.replace("[options]", "[options]\nParallelDownloads = 5")
            changed = true
        }
        if (changed) {
            pacmanConf.writeText(text)
        }
    }
}
