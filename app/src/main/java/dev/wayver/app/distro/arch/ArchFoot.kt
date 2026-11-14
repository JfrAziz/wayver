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
        pacman -Sy --noconfirm
        """.trimIndent()

    val spec =
        DesktopSpec(
            id = "foot",
            name = "Foot Terminal",
            prepare = { context, rootfsDir ->
                AssetCopy.copyAssetTree(context, "distros/arch/foot", File(rootfsDir, "root/.config"))
            },
            launch =
            """
                if [ ! -x /usr/bin/foot ]; then
                    $pacmanPrep && pacman -S --noconfirm --needed foot ttf-dejavu
                fi
                exec foot
            """.trimIndent()
        )
}
