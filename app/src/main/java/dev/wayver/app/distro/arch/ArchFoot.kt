package dev.wayver.app.distro.arch

import dev.wayver.app.distro.DesktopSpec
import dev.wayver.app.utils.AssetCopy
import java.io.File

object ArchFoot {
    private val pacmanPrep =
        """
        rm -f /var/lib/pacman/db.lck
        if [ ! -d /etc/pacman.d/gnupg/private-keys-v1.d ]; then
            pacman-key --init && pacman-key --populate archlinuxarm
        fi
        pacman -Syy --noconfirm
        """.trimIndent()

    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { ctx ->
                AssetCopy.copyAssetTree(ctx.context, "distros/arch/foot", File(ctx.rootfsDir, "root/.config"))
                ctx.runInProot("$pacmanPrep && pacman -Syu --noconfirm --needed foot ttf-dejavu") == 0
            },
            launch = "exec foot"
        )
}
